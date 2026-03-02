package org.ethereumphone.andyclaw.skills

sealed class SkillResult {
    data class Success(val data: String) : SkillResult()
    data class ImageSuccess(
        val text: String,
        val base64: String,
        val mediaType: String = "image/jpeg",
    ) : SkillResult()
    data class Error(val message: String) : SkillResult()
    data class RequiresApproval(val description: String) : SkillResult()
}
