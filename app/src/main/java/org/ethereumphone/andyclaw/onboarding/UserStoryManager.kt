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

    fun getAiName(): String = nameIn(read())

    /**
     * Renames the agent where every run and `ILauncherService.getAiName()` read the name: the
     * `# Name:` line of the story (SET-06). Settings used to write only `SecurePrefs.aiName`, so the
     * field showed the new name and everything else kept the old one. False when there is no
     * story yet — writing one would mark the phone as set up — or the name is blank.
     */
    fun rename(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        val story = read() ?: return false
        write(withName(story, trimmed))
        return true
    }

    companion object {
        private val NAME_LINE = Regex("""^#\s*Name:\s*(.+)""", RegexOption.MULTILINE)

        /** The same line, never reaching past its own end: what [withName] replaces. */
        private val NAME_LINE_ONLY = Regex("""^#[ \t]*Name:[^\n]*""", RegexOption.MULTILINE)

        /** The agent's name in [story], or "AndyClaw". */
        fun nameIn(story: String?): String {
            val text = story ?: return "AndyClaw"
            val match = NAME_LINE.find(text)
            return match?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() } ?: "AndyClaw"
        }

        /**
         * [story] with its first `# Name:` line saying [name], or with one put first when it has
         * none. The rest of the story is kept exactly.
         */
        fun withName(story: String, name: String): String {
            val line = "# Name: ${name.trim().lines().first()}"
            val match = NAME_LINE_ONLY.find(story)
                ?: return if (story.isEmpty()) "$line\n" else "$line\n\n$story"
            return story.replaceRange(match.range, line)
        }
    }
}
