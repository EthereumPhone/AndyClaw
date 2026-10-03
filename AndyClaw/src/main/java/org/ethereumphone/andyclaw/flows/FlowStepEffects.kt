package org.ethereumphone.andyclaw.flows

import org.ethereumphone.andyclaw.skills.ToolEffect

/**
 * What one flow step does to the world.
 *
 * This is Phase 1's [ToolEffect] applied one level down. At the *tool* level a display
 * tap is classified `IRREVERSIBLE`, because from outside nobody can tell what a tap
 * does. Inside a flow there is more to go on — the step names the node it targets — and
 * a flow needs the finer answer for one reason only: `agent-os-design.md` §6 says a flow
 * with no `checkpoint:` before an irreversible step must not compile, and the design
 * doc's own canonical example puts its single checkpoint before `send_button` and
 * nowhere else. So navigating is not the same as sending, and this object is where that
 * distinction is drawn.
 *
 * Two rules keep the heuristic from becoming a hole:
 *
 * 1. **A declaration can only raise.** A compiler may mark a step `IRREVERSIBLE`; it
 *    cannot mark `send_button` reversible and walk past the checkpoint rule.
 * 2. **This is not the security gate.** The gate is at the tool level, where every flow
 *    that actuates anything at all is `IRREVERSIBLE` and needs the user's approval
 *    before it runs ([FlowToolEffect]). What this object decides is where the
 *    *checkpoint* has to sit, and whether the flow may compile at all.
 */
object FlowStepEffects {

    /**
     * Tokens that mean "this commits". Matched whole, against the tokens of a view id,
     * so `resend` does not match `send` and `com.sendbird.x` does not either.
     *
     * Not the list of everything that commits — "save", "create", "done" and "publicar" are not
     * in it — and not what stops a replay from doing its task twice: the interpreter counts a
     * flow as committed once its last action has gone out, whatever that action's target is
     * called. Growing this set is not a fix for that either. It decides where a checkpoint must
     * sit, so a new token makes every installed flow that taps it without one fail validation,
     * and stop loading.
     */
    val COMMIT_TOKENS: Set<String> = setOf(
        "send", "submit", "post", "publish", "share", "delete", "remove", "discard",
        "confirm", "accept", "approve", "transfer", "withdraw", "sign", "call", "dial",
        "book", "reserve", "install", "uninstall", "block", "report", "logout",
        "signout", "unfollow", "unsubscribe", "reply", "forward", "invite",
        // German, folded the way [tokenize] folds them (ä -> a, ö -> o, ü -> u, ß -> ss),
        // plus the ae/oe/ue spellings people type when they have no umlaut key.
        "senden", "absenden", "abschicken", "schicken", "loschen", "loeschen", "entfernen",
        "teilen", "posten", "veroffentlichen", "veroeffentlichen", "bestatigen", "bestaetigen",
        "annehmen", "akzeptieren", "uberweisen", "ueberweisen", "antworten", "weiterleiten",
        "einladen", "blockieren", "melden", "abmelden", "installieren", "deinstallieren",
        "buchen", "reservieren", "anrufen", "unterschreiben", "signieren",
    )

    /**
     * Payment and authentication. `agent-os-design.md` §6: "**HARD RULE: never automate
     * an auth or payment confirmation step.** Not 'ask first' — never." A flow with one
     * of these does not compile, at all — see [FlowValidator]. Matched through
     * [isSensitiveText], which also knows compounds ([SENSITIVE_PREFIXES]) and the bank's TAN.
     */
    val SENSITIVE_TOKENS: Set<String> = setOf(
        "pay", "payment", "purchase", "buy", "checkout", "card", "cvv", "cvc", "iban",
        "pin", "password", "passcode", "passphrase", "otp", "2fa", "mfa", "totp",
        "biometric", "fingerprint", "faceid", "authenticate", "auth", "login", "signin",
        "seed", "mnemonic", "privatekey", "keystore", "unlock", "verify",
        "paypal", "gpay", "googlepay", "applepay", "klarna", "venmo", "cashapp",
        // "Place order", "Order now", "Top up", "Verification code": [tokenize] joins adjacent
        // words, so a two-word button is one token here.
        "placeorder", "ordernow", "subscribe", "topup", "donate", "verification", "securitycode",
        "passkey", "passkeys", "authorize", "authorise",
        // German, folded as above. "zahlungspflichtig bestellen" is the button German law
        // requires on a checkout, so both halves are here.
        "bezahlen", "zahlen", "zahlung", "zahlungspflichtig", "kaufen", "kauf", "kasse",
        "bestellen", "passwort", "kennwort", "anmelden", "anmeldung", "einloggen",
        "geheimzahl", "kartennummer", "prufziffer", "pruefziffer", "entsperren",
        "verifizieren", "abonnieren", "kostenpflichtig", "transaktionsnummer",
        // The bank's TAN in the forms banks write it. "tan" alone is the surname Tan and a colour;
        // in capitals or in a view id it still counts ([isSensitiveText]).
        "mtan", "smstan", "pushtan", "chiptan", "phototan", "apptan", "tannummer", "tanverfahren", "tanliste",
        // French, Spanish, Portuguese, Italian, Dutch, Scandinavian, Polish, Czech and Turkish,
        // folded the same way. Words that also mean something harmless ("carte" is a map) stay out.
        "payer", "paiement", "acheter", "achat", "commander", "seconnecter",
        "pagar", "pago", "comprar", "compra", "contrasena", "tarjeta", "iniciarsesion",
        "pagamento", "senha", "cartao",
        "paga", "pagare", "acquista", "acquistare", "acquisto", "accedi",
        "betalen", "betaling", "afrekenen", "kopen", "wachtwoord", "inloggen", "aanmelden",
        "betala", "betal", "betale", "losenord", "passord", "adgangskode",
        "zaplac", "platnosc", "zamawiam", "haslo", "zaloguj",
        "zaplatit", "platba", "koupit", "heslo", "prihlasit",
        "odeme", "satinal", "sifre", "girisyap",
    )

    /**
     * Compounds, which German writes as one word: a token that starts with one of these is
     * sensitive — "Kreditkarte", "Zahlungsart", "Bezahlvorgang", a `passwordfield` id.
     */
    val SENSITIVE_PREFIXES: List<String> = listOf(
        "zahlungs", "bezahl", "kreditkart", "debitkart", "passwort", "password", "kennwort", "passcode",
        "checkout", "payment", "paiement", "pagamento", "betaal", "wachtwoord",
    )

    /** ...and one that ends with one of these: "Einmalpasswort", "Firmenkreditkarte". */
    private val SENSITIVE_SUFFIXES: List<String> = listOf("passwort", "password", "kennwort", "kreditkarte")

    /**
     * Whether [text] names payment or authentication: a word of [SENSITIVE_TOKENS], a compound
     * built on one, or a bank's TAN — written in capitals or as a view id, never "Tan" the name.
     */
    fun isSensitiveText(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val tokens = tokenize(text)
        if (tokens.any(::isSensitiveToken)) return true
        if (BANK_TAN.containsMatchIn(text)) return true
        return "tan" in tokens && VIEW_ID_LIKE.matches(text)
    }

    private fun isSensitiveToken(token: String): Boolean =
        token in SENSITIVE_TOKENS ||
            SENSITIVE_PREFIXES.any { token.startsWith(it) } ||
            SENSITIVE_SUFFIXES.any { token.endsWith(it) }

    /**
     * Whether [text] has words in a script [tokenize] cannot read — Cyrillic, Greek, Arabic,
     * Hebrew, CJK, Thai and the rest — so no word list here can say whether it means "send" or
     * "pay". Latin letters read whatever their accents.
     */
    fun unreadable(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        return fold(text).any { it.isLetter() && it !in 'a'..'z' && it !in 'A'..'Z' }
    }

    /** The effect of [step] — the declaration and the classification, whichever is higher. */
    fun of(step: FlowStep): ToolEffect {
        val classified = classify(step)
        val declared = declaredEffect(step)
        return if (declared != null && declared.ordinal > classified.ordinal) declared else classified
    }

    /** What the step itself claims, if anything. Only ever used to raise [classify]. */
    fun declaredEffect(step: FlowStep): ToolEffect? {
        val raw = (step as? TapStep)?.effect?.trim()?.uppercase() ?: return null
        return ToolEffect.entries.firstOrNull { it.name == raw }
    }

    /** What the step looks like from its opcode and its target, ignoring declarations. */
    fun classify(step: FlowStep): ToolEffect = when (step) {
        // Looking at the screen changes nothing, and a checkpoint is a boundary marker.
        is WaitForStep, is AssertStep, is CheckpointStep -> ToolEffect.READ

        // Text in a field is not text that has been sent.
        is TypeStep -> tokenEffect(step.target.viewId, ToolEffect.REVERSIBLE)

        is TapStep -> tokenEffect(step.viewId ?: step.text, ToolEffect.REVERSIBLE)
    }

    private fun tokenEffect(target: String?, floor: ToolEffect): ToolEffect {
        val tokens = tokenize(target)
        return when {
            isSensitiveText(target) -> ToolEffect.SENSITIVE
            tokens.any { it in COMMIT_TOKENS } -> ToolEffect.IRREVERSIBLE
            else -> floor
        }
    }

    /**
     * The words to match against, whole: `com.android.mms:id/send_button` ->
     * `[com, android, mms, id, send, button]`.
     *
     * Three things beyond splitting on punctuation, each of which let a payment button through:
     * - camelCase is split first — `btnPay` and `confirmPurchase` are two words each, and a
     *   lowercase-first tokenizer saw one word it did not know;
     * - letters are folded to ASCII (`Löschen` -> `loschen`, `ß` -> `ss`) instead of being
     *   treated as separators, which cut German words in half;
     * - adjacent pairs are joined too, so `privateKey`, `sign_in` and `check-out` still meet
     *   `privatekey`, `signin` and `checkout`. The unsplit word is kept as well.
     */
    fun tokenize(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        val folded = fold(value)
        val words = CAMEL_BOUNDARY.replace(ACRONYM_BOUNDARY.replace(folded, "$1 $2"), "$1 $2")
            .lowercase()
            .split(NON_WORD)
            .filter { it.isNotEmpty() }
        val unsplit = folded.lowercase().split(NON_WORD).filter { it.isNotEmpty() }
        val pairs = words.zipWithNext { a, b -> a + b }
        return (words + unsplit + pairs).distinct()
    }

    /**
     * `Löschen` -> `Loschen`, `Straße` -> `Strasse`, full-width `Ｐａｙ` -> `Pay`: diacritics
     * dropped, ligatures and compatibility forms spelled out.
     */
    private fun fold(value: String): String {
        val spelled = value.replace("ß", "ss").replace("ẞ", "SS")
            .replace("æ", "ae").replace("Æ", "AE").replace("œ", "oe").replace("Œ", "OE")
            .replace("ø", "o").replace("Ø", "O").replace("ł", "l").replace("Ł", "L")
            .replace("ı", "i").replace("đ", "d").replace("Đ", "D")
        return COMBINING_MARKS.replace(java.text.Normalizer.normalize(spelled, java.text.Normalizer.Form.NFKD), "")
    }

    private val CAMEL_BOUNDARY = Regex("([a-z0-9])([A-Z])")
    private val ACRONYM_BOUNDARY = Regex("([A-Z]+)([A-Z][a-z])")
    private val NON_WORD = Regex("[^a-z0-9]+")
    private val COMBINING_MARKS = Regex("\\p{M}+")
    private val BANK_TAN = Regex("(?<![\\p{L}\\p{N}])TAN(?![\\p{L}\\p{N}])")
    /** No spaces, and a separator a resource name has: `com.bank:id/tan`, `tan_field`. */
    private val VIEW_ID_LIKE = Regex("[A-Za-z0-9_.:/-]*[_:/][A-Za-z0-9_.:/-]*")

    /** The highest effect any step in [flow] reaches. */
    fun highestOf(flow: Flow): ToolEffect =
        flow.steps.map { of(it) }.maxByOrNull { it.ordinal } ?: ToolEffect.READ
}

/**
 * The effect the *tool* a flow is registered as carries — the one Phase 1's
 * `ProvenanceGate` and the approval check act on.
 *
 * Deliberately blunter than [FlowStepEffects]: **anything that actuates is
 * `IRREVERSIBLE`.** No verb table, no heuristic. A flow that taps or types gets the
 * one-tap confirmation `agent-os-design.md` §6 mandates for irreversible actions, and a
 * flow that only waits and asserts is a read. The finer classification exists to place
 * the checkpoint, not to decide whether the user is asked.
 */
object FlowToolEffect {

    fun of(flow: Flow): ToolEffect {
        if (flow.steps.any { it is TapStep || it is TypeStep }) return ToolEffect.IRREVERSIBLE
        return ToolEffect.READ
    }

    /** Whether replaying [flow] must be confirmed by the user first. */
    fun requiresApproval(flow: Flow): Boolean = of(flow) != ToolEffect.READ
}
