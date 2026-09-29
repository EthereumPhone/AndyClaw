package org.ethereumphone.andyclaw.services

/**
 * Who may call `ILauncherService`.
 *
 * A package name proves nothing on its own: on any phone that is not a dgen1 an unrelated app
 * can call itself `org.ethosmobile.ethoslauncher`, bind the exported service and read every API
 * key, flip YOLO on, or run a queued approval. What stops that on a dgen1 is the system image, so
 * the name only counts together with something another app cannot claim: the system uid (the
 * dgen1 launcher is `android.uid.system`) or the signing key AndyClaw itself is signed with (the
 * ethOS build re-signs AndyClaw, the launcher and SystemUI with the platform key).
 *
 * Pure, so the whole decision is tested on the JVM; the service supplies the uid, the uid's
 * packages and whether `PackageManager.checkSignatures` matched.
 */
object CallerPolicy {

    /** `android.os.Process.SYSTEM_UID`, spelled out so this stays free of Android. */
    const val SYSTEM_UID = 1000

    const val LAUNCHER_PACKAGE = "org.ethosmobile.ethoslauncher"
    const val SYSTEMUI_PACKAGE = "com.android.systemui"

    /** The names that may call at all. Never enough by themselves; see [isAllowed]. */
    val CALLER_PACKAGES: Set<String> = setOf(LAUNCHER_PACKAGE, SYSTEMUI_PACKAGE)

    /**
     * True when [packages] (the calling uid's) name an allowed caller and the caller is either the
     * system uid or signed like AndyClaw. [debugPackages] are let in by name alone; the service
     * passes the agentbench client there in debug builds only, as before.
     */
    fun isAllowed(
        uid: Int,
        packages: Collection<String>?,
        signatureMatch: Boolean,
        debugPackages: Set<String> = emptySet(),
    ): Boolean {
        val names = packages.orEmpty()
        if (names.any { it in debugPackages }) return true
        if (names.none { it in CALLER_PACKAGES }) return false
        return uid == SYSTEM_UID || signatureMatch
    }

    /**
     * `sendLockscreenPrompt` is SystemUI's, and only SystemUI's: the real SystemUI package, and
     * authentic by the same test as [isAllowed]. A launcher turn goes through `sendPrompt`.
     */
    fun lockscreenAllowed(uid: Int, packages: Collection<String>?, signatureMatch: Boolean): Boolean =
        packages.orEmpty().contains(SYSTEMUI_PACKAGE) && (uid == SYSTEM_UID || signatureMatch)

    /** Whether a signature check is worth a binder round trip: only for a name that could pass. */
    fun needsSignatureCheck(uid: Int, packages: Collection<String>?): Boolean =
        uid != SYSTEM_UID && packages.orEmpty().any { it in CALLER_PACKAGES }
}
