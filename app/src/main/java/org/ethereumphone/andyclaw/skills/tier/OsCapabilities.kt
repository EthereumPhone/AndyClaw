package org.ethereumphone.andyclaw.skills.tier

import android.content.Context
import android.content.pm.PackageManager
import org.ethereumphone.andyclaw.skills.Capability
import org.ethereumphone.andyclaw.skills.Tier

object OsCapabilities {

    private var appContext: Context? = null
    private var _isPrivilegedOs: Boolean = false
    private var _isSystemApp: Boolean = false

    fun init(context: Context) {
        appContext = context.applicationContext
        _isPrivilegedOs = detectPrivilegedOs(context)
        _isSystemApp = detectSystemApp(context)
    }

    val isPrivilegedOs: Boolean get() = _isPrivilegedOs

    val isSystemApp: Boolean get() = _isSystemApp

    val hasPrivilegedAccess: Boolean get() = _isPrivilegedOs || _isSystemApp

    fun currentTier(): Tier {
        return if (hasPrivilegedAccess) Tier.PRIVILEGED else Tier.OPEN
    }

    fun hasCapability(cap: Capability): Boolean {
        return when (cap) {
            Capability.SHELL_BASIC,
            Capability.FILE_READ,
            Capability.FILE_WRITE,
            Capability.CLIPBOARD_READ,
            Capability.CLIPBOARD_WRITE,
            Capability.DEVICE_INFO,
            Capability.APPS_LIST,
            Capability.APPS_LAUNCH -> true

            Capability.SHELL_ROOT,
            Capability.CAMERA_SILENT,
            Capability.SCREEN_READ,
            Capability.APPS_MANAGE,
            Capability.SETTINGS_WRITE,
            Capability.NOTIFICATIONS_MANAGE,
            Capability.PROACTIVE_TRIGGERS,
            Capability.CONNECTIVITY_MANAGE,
            Capability.PHONE_CALL,
            Capability.CALENDAR_WRITE,
            Capability.SCREEN_TIME_READ,
            Capability.STORAGE_MANAGE,
            Capability.PACKAGE_MANAGE,
            Capability.AUDIO_MANAGE,
            Capability.DEVICE_POWER_MANAGE,
            Capability.CODE_EXECUTE,
            Capability.HEARTBEAT_ON_NOTIFICATION -> hasPrivilegedAccess

            Capability.CONTACTS_READ,
            Capability.CONTACTS_WRITE,
            Capability.SMS_READ,
            Capability.SMS_SEND,
            Capability.NOTIFICATIONS_READ,
            Capability.SETTINGS_READ,
            Capability.CAMERA_CAPTURE,
            Capability.LOCATION_ACCESS,
            Capability.CONNECTIVITY_READ,
            Capability.PHONE_READ,
            Capability.CALENDAR_READ -> true // Guarded by runtime permissions
        }
    }

    private fun detectPrivilegedOs(context: Context): Boolean {
        return context.getSystemService("wallet") != null
    }

    private fun detectSystemApp(context: Context): Boolean {
        return try {
            val appInfo = context.packageManager.getApplicationInfo(context.packageName, 0)
            (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }
}
