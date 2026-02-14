package org.ethereumphone.andyclaw.onboarding

import android.content.Context
import java.io.File

class UserStoryManager(context: Context) {

    private val file = File(context.filesDir, "user_story.md")

    fun exists(): Boolean = file.exists() && file.length() > 0

    fun read(): String? = if (exists()) file.readText() else null

    fun write(content: String) {
        file.writeText(content)
    }

    fun getAiName(): String {
        val text = read() ?: return "AndyClaw"
        val match = Regex("""^#\s*Name:\s*(.+)""", RegexOption.MULTILINE).find(text)
        return match?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() } ?: "AndyClaw"
    }
}
