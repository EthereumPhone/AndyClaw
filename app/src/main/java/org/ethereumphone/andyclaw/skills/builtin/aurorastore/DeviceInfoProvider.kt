/*
 * Ported from Aurora Store
 * Copyright (C) 2021, Rahul Kumar Patel <whyorean@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.ethereumphone.andyclaw.skills.builtin.aurorastore

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import androidx.core.content.getSystemService
import androidx.core.content.pm.PackageInfoCompat
import java.util.Locale
import java.util.Properties

/**
 * Provider for native device information used for Play Store authentication.
 * Creates the device fingerprint sent to Google's servers.
 */
class DeviceInfoProvider(private val context: Context) {

    companion object {
        private const val GOOGLE_SERVICES_PACKAGE_ID = "com.google.android.gms"
        private const val GOOGLE_VENDING_PACKAGE_ID = "com.android.vending"
        private const val DEFAULT_GSF_VERSION_CODE = 203019037L
        private const val DEFAULT_VENDING_VERSION_CODE = 82151710L
        private const val DEFAULT_VENDING_VERSION_STRING = "21.5.17-21 [0] [PR] 326734551"
    }

    val locale: Locale get() = Locale.getDefault()

    val deviceProperties: Properties
        get() = Properties().apply {
            setProperty("UserReadableName", "${Build.MANUFACTURER} ${Build.MODEL}")
            setProperty("Build.HARDWARE", Build.HARDWARE)
            setProperty("Build.RADIO", Build.getRadioVersion() ?: "unknown")
            setProperty("Build.FINGERPRINT", Build.FINGERPRINT)
            setProperty("Build.BRAND", Build.BRAND)
            setProperty("Build.DEVICE", Build.DEVICE)
            setProperty("Build.VERSION.SDK_INT", "${Build.VERSION.SDK_INT}")
            setProperty("Build.VERSION.RELEASE", Build.VERSION.RELEASE)
            setProperty("Build.MODEL", Build.MODEL)
            setProperty("Build.MANUFACTURER", Build.MANUFACTURER)
            setProperty("Build.PRODUCT", Build.PRODUCT)
            setProperty("Build.ID", Build.ID)
            setProperty("Build.BOOTLOADER", Build.BOOTLOADER)

            val config = context.resources.configuration
            setProperty("TouchScreen", "${config.touchscreen}")
            setProperty("Keyboard", "${config.keyboard}")
            setProperty("Navigation", "${config.navigation}")
            setProperty("ScreenLayout", "${config.screenLayout and 15}")
            setProperty("HasHardKeyboard", "${config.keyboard == Configuration.KEYBOARD_QWERTY}")
            setProperty(
                "HasFiveWayNavigation",
                "${config.navigation == Configuration.NAVIGATIONHIDDEN_YES}"
            )

            val metrics = context.resources.displayMetrics
            setProperty("Screen.Density", "${metrics.densityDpi}")
            setProperty("Screen.Width", "${metrics.widthPixels}")
            setProperty("Screen.Height", "${metrics.heightPixels}")

            setProperty("Platforms", Build.SUPPORTED_ABIS.joinToString(separator = ","))
            setProperty("Features", getFeatures().joinToString(separator = ","))
            setProperty("Locales", getLocales().joinToString(separator = ","))
            setProperty("SharedLibraries", getSharedLibraries().joinToString(separator = ","))

            val activityManager = context.getSystemService<ActivityManager>()
            setProperty(
                "GL.Version",
                activityManager!!.deviceConfigurationInfo.reqGlEsVersion.toString()
            )
            setProperty(
                "GL.Extensions",
                EglExtensionProvider.eglExtensions.joinToString(separator = ",")
            )

            setProperty("Client", "android-google")

            val gsfVersionCode = getGsfVersionCode()
            val (vendingVersionCode, vendingVersionString) = getVendingVersion()

            setProperty("GSF.version", gsfVersionCode.toString())
            setProperty("Vending.version", vendingVersionCode.toString())
            setProperty("Vending.versionString", vendingVersionString)

            setProperty("Roaming", "mobile-notroaming")
            setProperty("TimeZone", "UTC-10")

            setProperty("CellOperator", "310")
            setProperty("SimOperator", "38")
        }

    private fun getFeatures(): List<String> {
        return context.packageManager.systemAvailableFeatures.mapNotNull { it.name }
    }

    private fun getLocales(): List<String> {
        return context.assets.locales.mapNotNull { it.replace("-", "_") }
    }

    private fun getSharedLibraries(): List<String> {
        return context.packageManager.systemSharedLibraryNames?.toList() ?: emptyList()
    }

    private fun getGsfVersionCode(): Long {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(
                GOOGLE_SERVICES_PACKAGE_ID,
                PackageManager.PackageInfoFlags.of(0)
            )
            PackageInfoCompat.getLongVersionCode(packageInfo)
        } catch (_: PackageManager.NameNotFoundException) {
            DEFAULT_GSF_VERSION_CODE
        }
    }

    private fun getVendingVersion(): Pair<Long, String> {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(
                GOOGLE_VENDING_PACKAGE_ID,
                PackageManager.PackageInfoFlags.of(0)
            )
            val versionCode = PackageInfoCompat.getLongVersionCode(packageInfo)
            val versionString = packageInfo.versionName ?: DEFAULT_VENDING_VERSION_STRING
            Pair(versionCode, versionString)
        } catch (_: PackageManager.NameNotFoundException) {
            Pair(DEFAULT_VENDING_VERSION_CODE, DEFAULT_VENDING_VERSION_STRING)
        }
    }
}
