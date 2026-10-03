package org.ethereumphone.andyclaw.extensions.execution

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.ethereumphone.andyclaw.extensions.ApkBridgeType
import org.ethereumphone.andyclaw.extensions.ExtensionDescriptor
import org.ethereumphone.andyclaw.extensions.ExtensionResult
import org.ethereumphone.andyclaw.extensions.discovery.ApkExtensionScanner
import kotlin.coroutines.resume

/**
 * Executes functions on APK-based extensions using the appropriate IPC mechanism.
 *
 * ## Bridge selection
 *
 * When multiple bridge types are available, the executor picks the highest-fidelity
 * option in this order:
 *
 * 1. **BOUND_SERVICE** — richest protocol: bidirectional, typed, can stream.
 * 2. **CONTENT_PROVIDER** — synchronous request/response via `call()`.
 * 3. **BROADCAST_RECEIVER** — asynchronous, single-shot.
 * 4. **EXPLICIT_INTENT** — fire-and-forget (no structured return value).
 *
 * ## Bound service wire protocol
 *
 * Rather than requiring a shared AIDL stub, the executor uses a lightweight
 * [Parcel]-based protocol so extensions only need to implement a `Binder`:
 *
 * | Transaction code | Request                                          | Response           |
 * |------------------|--------------------------------------------------|--------------------|
 * | 1 (GET_MANIFEST) | interface token                                  | JSON string        |
 * | 2 (EXECUTE)      | interface token + function (String) + params (String) | JSON string   |
 *
 * Extensions that prefer AIDL can implement `IAndyClawSkill.aidl` — the
 * transaction codes and wire format are intentionally compatible.
 */
class ApkExtensionExecutor(
    private val context: Context,
    @Suppress("unused")
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    companion object {
        /**
         * Interface descriptor for the extension Binder protocol.
         * Must match the token used by extension APKs.
         */
        const val INTERFACE_DESCRIPTOR = "org.ethereumphone.andyclaw.IExtension"

        /** Transaction: no extra args → manifest JSON string. */
        const val TRANSACTION_GET_MANIFEST = 1

        /** Transaction: function (String) + paramsJson (String) → result JSON string. */
        const val TRANSACTION_EXECUTE = 2
    }

    /** Active service connections, one per call in flight (package name + a call id). */
    private val activeConnections = java.util.concurrent.ConcurrentHashMap<String, ServiceConnection>()

    /**
     * Where a bound extension's transact runs. A binder call blocks its thread and no coroutine
     * timeout reaches into it, so the call waits here and the caller waits only until its deadline.
     */
    private val transactScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Public API ───────────────────────────────────────────────────

    /**
     * Execute [function] on the APK extension described by [descriptor].
     *
     * Automatically selects the best available bridge type.
     */
    suspend fun execute(
        descriptor: ExtensionDescriptor,
        function: String,
        params: JsonObject,
        timeoutMs: Long,
    ): ExtensionResult {
        if (descriptor.packageName == null) {
            return ExtensionResult.Error("APK extension ${descriptor.id} has no package name")
        }

        return when {
            ApkBridgeType.BOUND_SERVICE in descriptor.bridgeTypes ->
                executeViaService(descriptor, function, params, timeoutMs)

            ApkBridgeType.CONTENT_PROVIDER in descriptor.bridgeTypes ->
                executeViaContentProvider(descriptor, function, params)

            ApkBridgeType.BROADCAST_RECEIVER in descriptor.bridgeTypes ->
                executeViaBroadcast(descriptor, function, params, timeoutMs)

            ApkBridgeType.EXPLICIT_INTENT in descriptor.bridgeTypes ->
                executeViaIntent(descriptor, function, params)

            else -> ExtensionResult.Error(
                "Extension ${descriptor.id} has no supported bridge type"
            )
        }
    }

    /**
     * Unbind all active service connections. Call during shutdown.
     */
    fun unbindAll() {
        activeConnections.keys.toList().forEach { unbindService(it) }
    }

    // ── Bound Service (highest fidelity) ─────────────────────────────

    private suspend fun executeViaService(
        descriptor: ExtensionDescriptor,
        function: String,
        params: JsonObject,
        timeoutMs: Long,
    ): ExtensionResult {
        val packageName = descriptor.packageName!!
        val key = "$packageName#${java.util.UUID.randomUUID()}"

        return try {
            val binder = withTimeout(timeoutMs) {
                bindToService(packageName, key)
            } ?: return ExtensionResult.Error("Failed to bind to service for ${descriptor.id}")

            val call = transactScope.async { transactExecute(binder, function, params.toString()) }
            val result = withTimeout(timeoutMs) { call.await() }

            if (result != null) {
                ExtensionResult.Success(result)
            } else {
                ExtensionResult.Error("Null response from bound service ${descriptor.id}")
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            ExtensionResult.Timeout(timeoutMs)
        } catch (e: Exception) {
            // STOP ends the wait as a cancel, not as an extension's error.
            org.ethereumphone.andyclaw.ExecutionEngine.rethrowIfCancelled(e)
            ExtensionResult.Error("Bound service invocation failed: ${e.message}", e)
        } finally {
            // One call, one binding, let go when the call ends: kept, every call added another.
            unbindService(key)
        }
    }

    /**
     * Bind to the extension's service and suspend until connected.
     */
    private suspend fun bindToService(packageName: String, key: String): IBinder? {
        return suspendCancellableCoroutine { cont ->
            val intent = Intent(ApkExtensionScanner.ACTION_EXTENSION_SERVICE).apply {
                setPackage(packageName)
            }

            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    // Called again when the extension's process restarts under BIND_AUTO_CREATE:
                    // resuming twice threw on the main thread and took AndyClaw down with it.
                    if (cont.isActive) cont.resume(service)
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    // Connection lost after successful bind — handled at call site
                }

                override fun onBindingDied(name: ComponentName?) {
                    if (cont.isActive) cont.resume(null)
                }

                override fun onNullBinding(name: ComponentName?) {
                    if (cont.isActive) cont.resume(null)
                }
            }

            activeConnections[key] = connection
            val bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)

            if (!bound) {
                unbindService(key)
                if (cont.isActive) cont.resume(null)
            }

            cont.invokeOnCancellation { unbindService(key) }
        }
    }

    /**
     * Invoke the EXECUTE transaction on a bound extension Binder.
     *
     * Wire format (compatible with IAndyClawSkill AIDL):
     *   Request:  writeInterfaceToken + writeString(function) + writeString(paramsJson)
     *   Response: readException + readString → result JSON
     */
    private fun transactExecute(binder: IBinder, function: String, paramsJson: String): String? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(INTERFACE_DESCRIPTOR)
            data.writeString(function)
            data.writeString(paramsJson)
            binder.transact(TRANSACTION_EXECUTE, data, reply, 0)
            reply.readException()
            reply.readString()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun unbindService(key: String) {
        activeConnections.remove(key)?.let {
            try {
                context.unbindService(it)
            } catch (_: Exception) { /* already unbound */ }
        }
    }

    // ── ContentProvider ──────────────────────────────────────────────

    private fun executeViaContentProvider(
        descriptor: ExtensionDescriptor,
        function: String,
        params: JsonObject,
    ): ExtensionResult {
        val packageName = descriptor.packageName!!
        val authority = "$packageName${ApkExtensionScanner.PROVIDER_AUTHORITY_SUFFIX}"
        val uri = Uri.parse("content://$authority")

        return try {
            val extras = Bundle().apply {
                putString("function", function)
                putString("params", params.toString())
            }

            val result = context.contentResolver.call(uri, "execute", null, extras)
                ?: return ExtensionResult.Error(
                    "ContentProvider returned null for ${descriptor.id}"
                )

            val error = result.getString("error")
            if (error != null) {
                ExtensionResult.Error(error)
            } else {
                ExtensionResult.Success(result.getString("result") ?: "")
            }
        } catch (e: Exception) {
            ExtensionResult.Error("ContentProvider invocation failed: ${e.message}", e)
        }
    }

    // ── BroadcastReceiver ────────────────────────────────────────────

    private suspend fun executeViaBroadcast(
        descriptor: ExtensionDescriptor,
        function: String,
        params: JsonObject,
        timeoutMs: Long,
    ): ExtensionResult {
        val packageName = descriptor.packageName!!

        return try {
            withTimeout(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    // The reply comes from another app, so the receiver must be exported — which a
                    // NOT_EXPORTED one never was, so every call timed out. The action is unguessable
                    // and goes only to the extension (setPackage below), so only it can answer.
                    val responseAction = "${packageName}.andyclaw.EXTENSION_RESPONSE.${java.util.UUID.randomUUID()}"

                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(ctx: Context?, intent: Intent?) {
                            val error = intent?.getStringExtra("error")
                            val data = intent?.getStringExtra("result")

                            val result = when {
                                error != null -> ExtensionResult.Error(error)
                                data != null -> ExtensionResult.Success(data)
                                else -> ExtensionResult.Error("Empty broadcast response")
                            }

                            // A second reply must not resume it twice: that throws on the main thread.
                            if (cont.isActive) cont.resume(result)
                            try { context.unregisterReceiver(this) } catch (_: Exception) {}
                        }
                    }

                    // Listen for the response
                    context.registerReceiver(
                        receiver,
                        IntentFilter(responseAction),
                        Context.RECEIVER_EXPORTED,
                    )

                    // Fire the request
                    val intent = Intent(ApkExtensionScanner.ACTION_EXTENSION_BROADCAST).apply {
                        setPackage(packageName)
                        putExtra("function", function)
                        putExtra("params", params.toString())
                        putExtra("response_action", responseAction)
                    }
                    context.sendBroadcast(intent)

                    cont.invokeOnCancellation {
                        try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
                    }
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            ExtensionResult.Timeout(timeoutMs)
        } catch (e: Exception) {
            org.ethereumphone.andyclaw.ExecutionEngine.rethrowIfCancelled(e)
            ExtensionResult.Error("Broadcast invocation failed: ${e.message}", e)
        }
    }

    // ── Explicit Intent (fire-and-forget) ────────────────────────────

    private fun executeViaIntent(
        descriptor: ExtensionDescriptor,
        function: String,
        params: JsonObject,
    ): ExtensionResult {
        val packageName = descriptor.packageName!!

        return try {
            val intent = Intent(ApkExtensionScanner.ACTION_EXTENSION_ACTION).apply {
                setPackage(packageName)
                putExtra("function", function)
                putExtra("params", params.toString())
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ExtensionResult.Success("Activity launched for ${descriptor.id}:$function")
        } catch (e: Exception) {
            ExtensionResult.Error("Intent launch failed: ${e.message}", e)
        }
    }
}
