package org.ethereumphone.andyclaw.llm.reflex

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * What the reflex models have done on this phone, for the settings that say whether Reflex is in
 * use and when: how many requests M1 has read, how many commands ran on the phone, and the last
 * [MAX_RECENT] of those. `filesDir/reflex_activity.json`, on the device only.
 *
 * Labels, outcomes and times — never the user's words: Settings shows this, and Settings is not
 * where a conversation belongs. A missing, damaged or newer file reads as empty.
 */
class ReflexActivity(
    private val file: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** One command run on the phone. [outcome]: [DONE], [READ] or [FAILED]. [ms]: models + run. */
    @Serializable
    data class Event(val atMs: Long, val label: String, val outcome: String, val ms: Long)

    @Serializable
    data class Stats(
        val version: Int = 1,
        /** Requests M1 read: every user turn it ran on. */
        var read: Int = 0,
        /** Commands run on the phone that worked ([DONE] or [READ]). */
        var handled: Int = 0,
        var lastReadMs: Long = 0,
        /** Newest first. */
        val recent: MutableList<Event> = mutableListOf(),
    ) {
        val lastHandled: Event? get() = recent.firstOrNull { it.outcome != FAILED }
    }

    private val lock = Any()
    private var cached: Stats? = null

    /** Told after every change, off the caller's lock: the settings page refreshes on it. */
    @Volatile
    var onChange: (() -> Unit)? = null

    fun stats(): Stats = synchronized(lock) { load().let { it.copy(recent = it.recent.toMutableList()) } }

    /** M1 read a request. */
    fun noteRead() {
        synchronized(lock) {
            val s = load()
            s.read++
            s.lastReadMs = now()
            save(s)
        }
        changed()
    }

    /** A command ran on the phone instead of going to the AI first. */
    fun noteHandled(label: String, outcome: String, ms: Long) {
        synchronized(lock) {
            val s = load()
            if (outcome != FAILED) s.handled++
            s.recent.add(0, Event(now(), label, outcome, ms))
            while (s.recent.size > MAX_RECENT) s.recent.removeAt(s.recent.size - 1)
            save(s)
        }
        changed()
    }

    fun clear() {
        synchronized(lock) {
            cached = Stats()
            file.delete()
        }
        changed()
    }

    private fun changed() {
        runCatching { onChange?.invoke() }
    }

    private fun load(): Stats {
        cached?.let { return it }
        val s = runCatching { JSON.decodeFromString(Stats.serializer(), file.readText()) }.getOrNull() ?: Stats()
        cached = s
        return s
    }

    private fun save(s: Stats) {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(JSON.encodeToString(Stats.serializer(), s))
            tmp.renameTo(file)
        }.onFailure { Log.w(TAG, "reflex activity not saved: ${it.message}") }
    }

    companion object {
        private const val TAG = "ReflexActivity"
        const val MAX_RECENT = 20
        /** Done and answered on the phone, with no AI call. */
        const val DONE = "done"
        /** Run on the phone; the AI only phrased the result. */
        const val READ = "read"
        /** Tried on the phone, failed or refused, and handed to the AI. */
        const val FAILED = "failed"
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** What a label did, in a few words, for "Last used: Set an alarm". */
        fun describe(label: String): String = when (label) {
            "wifi_on" -> "Turned Wi-Fi on"
            "wifi_off" -> "Turned Wi-Fi off"
            "bluetooth_on" -> "Turned Bluetooth on"
            "bluetooth_off" -> "Turned Bluetooth off"
            "airplane_on" -> "Turned airplane mode on"
            "airplane_off" -> "Turned airplane mode off"
            "mobile_data_on" -> "Turned mobile data on"
            "mobile_data_off" -> "Turned mobile data off"
            "hotspot_on" -> "Turned the hotspot on"
            "hotspot_off" -> "Turned the hotspot off"
            "dnd_on", "dnd_priority_on" -> "Turned Do Not Disturb on"
            "dnd_off" -> "Turned Do Not Disturb off"
            "ringer_normal" -> "Turned the ringer on"
            "ringer_silent" -> "Silenced the phone"
            "ringer_vibrate" -> "Set the phone to vibrate"
            "dark_mode_on" -> "Turned dark mode on"
            "dark_mode_off" -> "Turned dark mode off"
            "night_light_on" -> "Turned night light on"
            "night_light_off" -> "Turned night light off"
            "lock_screen" -> "Locked the screen"
            "led_clear", "led_pattern", "led_flash", "led_color" -> "Set the LEDs"
            "device_info" -> "Read device info"
            "connectivity_status" -> "Checked the connection"
            "storage_info" -> "Checked storage"
            "list_reminders" -> "Listed reminders"
            "events_today", "events_tomorrow", "events_week" -> "Read the calendar"
            "price_eth", "price_pol", "price_bnb", "price_avax" -> "Looked up a price"
            "set_alarm" -> "Set an alarm"
            "create_reminder" -> "Set a reminder"
            "set_volume" -> "Changed the volume"
            "launch_app" -> "Opened an app"
            else -> "Ran a command"
        }
    }
}
