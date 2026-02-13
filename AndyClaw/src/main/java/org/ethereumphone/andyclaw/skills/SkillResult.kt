package org.ethereumphone.andyclaw.skills

sealed class SkillResult {
    data class Success(val data: String) : SkillResult()
    data class Error(val message: String) : SkillResult()
    data class RequiresApproval(val description: String) : SkillResult()
}
