package org.ethereumphone.andyclaw.skills.external

import android.content.Context
import android.content.pm.PackageManager

data class ExternalSkillInfo(
    val packageName: String,
    val serviceName: String,
    val skillId: String,
)

object ExternalSkillDiscovery {

    private const val META_KEY = "org.ethereumphone.andyclaw.SKILL"

    fun discover(context: Context): List<ExternalSkillInfo> {
        val pm = context.packageManager
        val results = mutableListOf<ExternalSkillInfo>()

        try {
            val packages = pm.getInstalledPackages(
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SERVICES.toLong() or PackageManager.GET_META_DATA.toLong())
            )
            for (pkg in packages) {
                val services = pkg.services ?: continue
                for (service in services) {
                    val meta = service.metaData ?: continue
                    val skillId = meta.getString(META_KEY) ?: continue
                    results.add(
                        ExternalSkillInfo(
                            packageName = pkg.packageName,
                            serviceName = service.name,
                            skillId = skillId,
                        )
                    )
                }
            }
        } catch (_: Exception) {
            // Silently handle discovery failures
        }

        return results
    }
}
