package org.ethereumphone.andyclaw.skills.tier

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import org.ethereumphone.andyclaw.skills.Capability
import org.ethereumphone.andyclaw.skills.Tier

object OsCapabilities {

    private var appContext: Context? = null
    private var _isPrivilegedOs: Boolean = false
    private var _isSystemApp: Boolean = false

    fun init(context: Context) {
        appContext = context.applicationContext
        _isPrivilegedOs = detectPrivilegedOs()
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
            Capability.PROACTIVE_TRIGGERS -> hasPrivilegedAccess

            Capability.CONTACTS_READ,
            Capability.CONTACTS_WRITE,
            Capability.SMS_READ,
            Capability.SMS_SEND,
            Capability.NOTIFICATIONS_READ,
            Capability.SETTINGS_READ,
            Capability.CAMERA_CAPTURE,
            Capability.LOCATION_ACCESS -> true // Guarded by runtime permissions
        }
    }

    @SuppressLint("PrivateApi")
    private fun detectPrivilegedOs(): Boolean {
        return try {
            val prop = Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java, String::class.java)
            val brand = prop.invoke(null, "ro.product.brand", "") as String
            brand.lowercase().contains("etheos") || brand.lowercase().contains("youros")
        } catch (_: Exception) {
            false
        }
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
