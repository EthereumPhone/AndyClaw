package org.ethereumphone.andyclaw.extensions.clawhub

import java.io.File

/**
 * Path and shell helpers for the places where a name that came from the model, a
 * registry or an archive becomes a file under `filesDir` or a word in a Termux
 * command.
 *
 * Every one of those names used to go straight into `File(parent, name)`: a slug of
 * `..` resolved to `filesDir` itself, so an install or delete wiped the whole
 * sandbox, and a write landed on AndyClaw's own state (the approval queue, the job
 * provenance store) without passing `FileSystemSkill.PROTECTED`. These checks are
 * canonical — they compare where the path *ends up*, after `..` and symlinks — so a
 * name that looks harmless but resolves elsewhere is refused too.
 *
 * Lives in `:AndyClaw` so `ClawHubManager`/`ClawHubApi` here and the `:app` skills
 * share one implementation.
 */
object SafePaths {

    /**
     * ClawHub slugs are lowercase kebab-case (`self-improving-agent`, `gog`). This
     * admits that plus `.`/`_` and upper case, so nothing already on a device stops
     * loading; what it refuses is a separator, a leading dot (`.`, `..`, the
     * `.clawhub` lock dir) and anything long enough to be abuse.
     */
    val CLAWHUB_SLUG_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")

    fun isValidClawHubSlug(slug: String): Boolean = CLAWHUB_SLUG_REGEX.matches(slug)

    /**
     * A single path component that can't name the parent or climb out of it: not
     * empty, not `.`/`..`, no `/`, `\` or NUL.
     */
    fun isSafeName(name: String): Boolean =
        name.isNotEmpty() && name != "." && name != ".." &&
            name.none { it == '/' || it == '\\' || it == '\u0000' }

    /**
     * The direct child [name] of [parent], or null when [name] is not a single safe
     * component or the child canonicalises anywhere but directly under [parent]
     * (for example because it is a symlink — `deleteRecursively` would follow it).
     */
    fun childOf(parent: File, name: String): File? {
        if (!isSafeName(name)) return null
        return try {
            val canonParent = parent.canonicalFile
            val child = File(canonParent, name)
            if (child.canonicalFile.parentFile == canonParent) child else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * [relative] resolved under [root], or null unless it lands strictly inside
     * [root] — `root` itself does not count, and the comparison is on
     * `root + separator`, so `root-other/` is not "inside" `root`.
     */
    fun resolveInside(root: File, relative: String): File? {
        if (relative.isEmpty() || relative.contains('\u0000')) return null
        return try {
            val target = File(root, relative)
            if (isStrictlyInside(root, target)) target else null
        } catch (_: Exception) {
            null
        }
    }

    /** Whether [file] canonicalises to somewhere strictly below [root]. */
    fun isStrictlyInside(root: File, file: File): Boolean = try {
        file.canonicalPath.startsWith(root.canonicalPath + File.separator)
    } catch (_: Exception) {
        false
    }

    /**
     * Quote [value] as one POSIX shell word: wrapped in single quotes, with each
     * embedded `'` closed, escaped and reopened. Nothing inside single quotes is
     * special to the shell, so this is the whole escape.
     */
    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
