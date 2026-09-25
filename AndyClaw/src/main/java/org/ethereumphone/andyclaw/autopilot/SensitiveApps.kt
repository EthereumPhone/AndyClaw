package org.ethereumphone.andyclaw.autopilot

/**
 * Apps whose screens never leave the phone.
 *
 * Whatever the agent reads off the agent display goes to a model: Jev through OpenRouter, the
 * autopilot's planner, or the chat model through the `agent_display_*` tools. FLAG_SECURE
 * blanks a window in frame captures but does nothing for the accessibility tree, so a recovery
 * phrase shown under FLAG_SECURE — WalletApp's Railgun seed, say — is still plain text there.
 *
 * For these packages the agent neither launches the app nor reads its screen. The user does
 * those steps themselves.
 */
object SensitiveApps {

    val PACKAGES: Set<String> = setOf(
        // ethOS
        "org.ethereumphone.walletmanager",  // WalletApp: Railgun recovery phrase
        "app.grapheneos.setupwizard",       // recovery address setup
        "com.android.systemui",             // wallet confirmation
        // Wallets
        "io.metamask",
        "com.wallet.crypto.trustapp",
        "me.rainbow",
        "org.toshi",                        // Coinbase Wallet
        "app.phantom",
        "com.debank.rabbymobile",
        // Password managers and authenticators
        "com.x8bit.bitwarden",
        "com.onepassword.android",
        "com.agilebits.onepassword",
        "proton.android.pass",
        "com.kunzisoft.keepass.free",
        "com.kunzisoft.keepass.libre",
        "com.google.android.apps.authenticator2",
        "com.beemdevelopment.aegis",
        "com.authy.authy",
    )

    fun isSensitive(packageName: String?): Boolean = packageName != null && packageName in PACKAGES

    private val PACKAGE_KEY = Regex("\"package\"\\s*:\\s*\"([^\"]+)\"")

    /**
     * The first sensitive package named anywhere in a raw accessibility-tree JSON, or null.
     * Any window counts, not only the top one: a private app's dialog over another app is
     * still the private app's content.
     */
    fun sensitivePackageIn(treeJson: String?): String? {
        if (treeJson.isNullOrEmpty()) return null
        return PACKAGE_KEY.findAll(treeJson).map { it.groupValues[1] }.firstOrNull(::isSensitive)
    }

    /**
     * The first private package among the app windows on a display, top first, or null. Every
     * window counts, not only the top one: a private app under another app's dialog is still on
     * the screen that would be read.
     */
    fun sensitiveAmong(windowPackages: List<String?>): String? = windowPackages.firstOrNull(::isSensitive)

    /** What the model is told instead of the screen. Names the package, never its content. */
    fun refusal(packageName: String): String =
        "$packageName is a private app (wallet, recovery or passwords). The agent does not open " +
            "it or read its screen. Ask the user to do this step themselves."
}
