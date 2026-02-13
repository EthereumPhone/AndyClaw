package org.ethereumphone.andyclaw.supercharger

data class ScreenContent(
    val packageName: String,
    val activityName: String,
    val viewHierarchy: String,
)

data class AmbientContext(
    val screenContent: ScreenContent?,
    val recentNotifications: List<String>,
    val timestamp: Long,
)

interface SuperchargerApi {
    suspend fun getScreenContent(): ScreenContent?
    suspend fun getAmbientContext(): AmbientContext?
    suspend fun tapElement(resourceId: String): Boolean
    suspend fun typeText(text: String): Boolean

    companion object {
        fun getInstance(): SuperchargerApi? {
            // Returns null on stock Android — only available on privileged OS builds
            return null
        }
    }
}
