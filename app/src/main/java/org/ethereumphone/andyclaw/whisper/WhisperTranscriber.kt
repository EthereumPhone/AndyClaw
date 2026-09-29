package org.ethereumphone.andyclaw.whisper

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Transcribes audio files using whisper.cpp via a custom JNI bridge
 * ([WhisperBridgeNative]) with optimised inference parameters.
 *
 * The English-only base model (ggml-base.en-q5_1.bin, ~60 MB Q5_1 quantized)
 * is bundled in the APK assets and copied to internal storage on first use.
 * Q5_1 preserves near-original accuracy while halving size and RAM usage.
 *
 * Domain-specific vocabulary (crypto/web3) is corrected via post-processing
 * regex replacements in [applyVocabCorrections].
 */
class WhisperTranscriber(private val context: Context) {

    companion object {
        private const val TAG = "WhisperTranscriber"
        private const val ASSET_NAME = "ggml-base.en-q5_1.bin"
        private const val COPY_BUFFER_SIZE = 1024 * 1024 // 1 MB (vs default 8 KB)

        /**
         * Case-insensitive word-boundary replacements for crypto/web3 terms
         * that Whisper's base model commonly misrecognises.
         *
         * Order matters — more specific patterns (e.g. "cronjobs") must come
         * before shorter/broader ones (e.g. "cron") to avoid double-replacing.
         */
        private val VOCAB_CORRECTIONS: List<Pair<Regex, String>> = listOf(
            // ethOS devices — must come before broader Ethereum patterns
            "\\bd[- ]?gen\\s?(?:1|one)\\b"  to "dGEN1",
            "\\bdeje[mn]\\s?(?:1|one)\\b"   to "dGEN1",
            "\\bdj\\s?1\\b"                 to "dGEN1",
            "\\bd[- ]?gen\\b"               to "dGEN",

            // Ethereum ecosystem
            "\\bethe?r[ei]?um\\b"      to "Ethereum",
            "\\beth\\s?os\\b"          to "ethOS",
            "\\bg\\s?wei\\b"           to "gwei",
            "\\bde\\s?fi\\b"           to "DeFi",
            "\\bdefy\\b"               to "DeFi",
            "\\bnfts\\b"               to "NFTs",
            "\\bn\\s?f\\s?t\\b"        to "NFT",

            // Tokens & stablecoins
            "\\bu\\s?s\\s?d\\s?[cs]\\b" to "USDC",
            "\\bu\\s?s\\s?d\\s?t\\b"    to "USDT",
            "\\bw\\s?e\\s?t\\s?h\\b"    to "WETH",
            "\\bw\\s?b\\s?t\\s?c\\b"    to "WBTC",
            "\\bd\\s?a\\s?i\\b"          to "DAI",

            // Protocols & standards
            "\\buni\\s?swap\\b"        to "Uniswap",
            "\\baave\\b"               to "Aave",
            "\\be\\s?n\\s?s\\b"        to "ENS",
            "\\berc\\s?[-\\s]?20\\b"   to "ERC-20",
            "\\berc\\s?[-\\s]?721\\b"  to "ERC-721",
            "\\berc\\s?[-\\s]?1155\\b" to "ERC-1155",

            // Layer 2 / scaling
            "\\bl\\s?2\\b"             to "L2",
            "\\brollups\\b"            to "rollups",
            "\\brollup\\b"             to "rollup",
            "\\barbitrum\\b"           to "Arbitrum",
            "\\boptimism\\b"           to "Optimism",
            "\\bpolygon\\b"            to "Polygon",

            // General crypto
            "\\bblockchain\\b"         to "blockchain",
            "\\bsmart\\s?contract\\b"  to "smart contract",
            "\\bseed\\s?phrase\\b"     to "seed phrase",
            "\\bmnemonic\\b"           to "mnemonic",
            "\\bgas\\s?fee\\b"         to "gas fee",
            "\\bstaking\\b"            to "staking",

            // Scheduling — "cron" is often misheard as "chrome" or "kron"
            "\\bcronjobs\\b"           to "cronjobs",
            "\\bcronjob\\b"            to "cronjob",
            "\\bcron\\s?jobs\\b"       to "cronjobs",
            "\\bcron\\s?job\\b"        to "cronjob",
            "\\bchrome\\s?jobs\\b"     to "cronjobs",
            "\\bchrome\\s?job\\b"      to "cronjob",
            "\\bkron\\s?jobs\\b"       to "cronjobs",
            "\\bkron\\s?job\\b"        to "cronjob",
            "\\bkron\\b"              to "cron",
            "\\bcron\\b"              to "cron",
        ).map { (pattern, replacement) ->
            Regex(pattern, RegexOption.IGNORE_CASE) to replacement
        }
    }

    private val modelFile = File(context.filesDir, ASSET_NAME)
    private var initialized = false
    private val mutex = Mutex()
    @Volatile private var warmUpJob: Job? = null

    /**
     * Starts loading the Whisper model in the background so it is ready
     * before the first [transcribe] call.  Call this as early as possible
     * (e.g. on service bind) to hide the cold-start latency.
     *
     * Safe to call more than once — subsequent calls are no-ops.
     */
    fun warmUp(scope: CoroutineScope) {
        if (initialized || warmUpJob != null) return
        warmUpJob = scope.launch(Dispatchers.IO) {
            mutex.withLock { doInitialize() }
        }
    }

    /**
     * Copies the model asset and loads it into WhisperBridge.
     * Must be called under [mutex].  No-ops if already initialized.
     */
    private suspend fun doInitialize() {
        if (initialized) return
        withContext(Dispatchers.IO) {
            // Clean up the old F16 model if present (142 MB → 60 MB with Q5_1).
            val oldModel = File(context.filesDir, "ggml-base.en.bin")
            if (oldModel.exists()) {
                oldModel.delete()
                Log.i(TAG, "Deleted old F16 model")
            }

            // The asset's length is known when it is stored uncompressed; then a short file is
            // caught here, before the native loader has to cope with it.
            val assetLength = runCatching { context.assets.openFd(ASSET_NAME).use { it.length } }.getOrNull()
            if (!modelFile.exists() || modelFile.length() == 0L ||
                (assetLength != null && assetLength > 0 && modelFile.length() != assetLength)
            ) {
                copyModelFromAssets()
            }

            Log.i(TAG, "Initializing Whisper model: ${modelFile.absolutePath}")
            if (!WhisperBridgeNative.initModel(modelFile.absolutePath)) {
                // Most likely a partial file: older builds copied straight onto the final name,
                // and a kill mid-copy left it short forever — it "existed", so it was never
                // copied again and every transcription failed until the app data was cleared.
                Log.w(TAG, "Whisper model failed to load; copying it again from the APK once")
                modelFile.delete()
                copyModelFromAssets()
                if (!WhisperBridgeNative.initModel(modelFile.absolutePath)) {
                    throw RuntimeException("Failed to initialize Whisper model")
                }
            }
            initialized = true
            Log.i(TAG, "Whisper model initialized")
        }
    }

    /**
     * Copies the bundled model to [modelFile] through a temp file and a rename, so the final
     * name only ever holds a complete copy. Blocking I/O; call on [Dispatchers.IO].
     */
    private fun copyModelFromAssets() {
        Log.i(TAG, "Copying Whisper model from assets to ${modelFile.absolutePath}")
        val tmp = File(modelFile.parentFile, "$ASSET_NAME.tmp")
        try {
            context.assets.open(ASSET_NAME).use { input ->
                tmp.outputStream().use { output ->
                    input.copyTo(output, COPY_BUFFER_SIZE)
                }
            }
            if (!tmp.renameTo(modelFile)) {
                throw java.io.IOException("Could not move the Whisper model into place")
            }
        } finally {
            tmp.delete() // a no-op after a successful rename
        }
        Log.i(TAG, "Model copied (${modelFile.length()} bytes)")
    }

    /**
     * Transcribes the audio file at [audioPath].
     *
     * The file must be a 16 kHz mono 16-bit PCM WAV (which is what
     * [org.ethosmobile.ethoslauncher.dgent.AudioRecorder] produces).
     *
     * @return The transcribed text, or throws on failure.
     */
    suspend fun transcribe(audioPath: String): String = mutex.withLock {
        // File I/O on IO dispatcher
        withContext(Dispatchers.IO) {
            val audioFile = File(audioPath)
            require(audioFile.exists()) { "Audio file does not exist: $audioPath" }
            doInitialize() // no-ops if already done via warmUp()
            Log.i(TAG, "Transcribing: $audioPath (${audioFile.length()} bytes)")
        }

        // CPU-bound transcription on Default dispatcher (not IO)
        withContext(Dispatchers.Default) {
            Log.i(TAG, "Calling WhisperBridgeNative.transcribeWav(\"$audioPath\", \"en\")")
            val startMs = System.currentTimeMillis()
            val raw = WhisperBridgeNative.transcribeWav(audioPath, "en").trim()
            val elapsedMs = System.currentTimeMillis() - startMs

            Log.i(TAG, "Native transcribeWav returned in ${elapsedMs}ms, raw length=${raw.length} chars")
            Log.d(TAG, "Raw output (first 500 chars): ${raw.take(500)}")

            if (raw.startsWith("ERROR:")) {
                Log.e(TAG, "Whisper error: $raw")
                throw RuntimeException("Whisper transcription failed: $raw")
            }

            // Detect and strip repetition loops
            val repetitionInfo = detectRepetition(raw)
            val deduped = if (repetitionInfo != null) {
                Log.w(TAG, "REPETITION DETECTED: phrase=\"${repetitionInfo.first}\" repeated ${repetitionInfo.second} times in output of ${raw.length} chars — stripping to single occurrence")
                repetitionInfo.first
            } else {
                raw
            }

            val result = applyVocabCorrections(deduped)

            if (result != raw) {
                Log.i(TAG, "Vocab corrected: \"${raw.take(200)}\" -> \"${result.take(200)}\"")
            }
            Log.i(TAG, "Transcription result (${result.length} chars): ${result.take(300)}")
            result
        }
    }

    /**
     * Detects if the output contains a repeating phrase loop.
     * Returns the repeated phrase and count, or null if no repetition found.
     *
     * Handles both patterns:
     *  - Exact prefix repeat: "What's the time? What's the time? What's the time?"
     *  - Sliding repeat: "What is what is what is what is"
     */
    private fun detectRepetition(text: String): Pair<String, Int>? {
        if (text.length < 40) return null

        // Strategy 1: check if a prefix substring repeats from the start
        for (len in 8..minOf(120, text.length / 2)) {
            val candidate = text.substring(0, len)
            var count = 0
            var pos = 0
            while (pos + len <= text.length) {
                if (text.substring(pos, pos + len) == candidate) {
                    count++
                    pos += len
                } else {
                    break
                }
            }
            if (count >= 3) {
                return candidate.trim() to count
            }
        }

        // Strategy 2: split into words and look for repeating n-gram sequences
        val words = text.trim().split("\\s+".toRegex())
        if (words.size < 6) return null
        for (n in 2..minOf(8, words.size / 3)) {
            val ngram = words.subList(0, n).joinToString(" ")
            var count = 0
            var i = 0
            while (i + n <= words.size) {
                val chunk = words.subList(i, i + n).joinToString(" ")
                if (chunk.equals(ngram, ignoreCase = true)) {
                    count++
                    i += n
                } else {
                    break
                }
            }
            if (count >= 3) {
                return ngram to count
            }
        }

        return null
    }

    /**
     * Applies domain-specific vocabulary corrections to raw Whisper output.
     * Fixes common misrecognitions of crypto/web3 and scheduling terms.
     */
    private fun applyVocabCorrections(text: String): String {
        var corrected = text
        for ((pattern, replacement) in VOCAB_CORRECTIONS) {
            corrected = pattern.replace(corrected, replacement)
        }
        return corrected
    }

    /** Releases native resources. Call when the service is destroyed. */
    suspend fun release(): Unit = mutex.withLock {
        warmUpJob?.cancel()
        warmUpJob = null
        if (initialized) {
            withContext(Dispatchers.IO) {
                WhisperBridgeNative.release()
                initialized = false
                Log.i(TAG, "Whisper model released")
            }
        }
    }
}
