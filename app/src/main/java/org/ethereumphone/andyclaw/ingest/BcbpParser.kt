package org.ethereumphone.andyclaw.ingest

/**
 * The IATA Bar Coded Boarding Pass (Resolution 792, format M) mandatory section.
 *
 * This is the string inside the Aztec square on a boarding pass, and it is a fixed-width
 * record — 60 characters, every field at a known offset. Reading it is substring
 * arithmetic. That is worth stating plainly because the alternative that suggests itself
 * — hand the PDF to a model and ask what the seat is — is precisely what
 * `agent-os-design.md` §5 forbids, and this is the concrete case it has in mind.
 *
 * ```
 * M1DESMARAIS/LUC       EABC123 YULFRAAC 0834 226F001A0025 100
 * ^^^                    ^^^^^^^ ^^^^^^^^^^^ ^^^^^^^^^^^^^^^^
 * | | passenger (20)     | PNR   from to     flight  date …
 * | legs                 electronic ticket
 * format
 * ```
 *
 * The layout is validated rather than assumed: a 60-character run of text that happens to
 * start with `M1` is common in a PDF, and reading a seat number out of one would be worse
 * than reading nothing. Every structural field has to be the right shape before any of it
 * is believed.
 *
 * **The payload is never rewritten.** [BoardingPass.payload] is the exact substring that
 * was found, because that string is what a gate scanner has to see; the parsed fields are a
 * convenience on top of it and never a replacement for it.
 */
object BcbpParser {

    /** Mandatory-section length. Conditional items follow it and are not needed here. */
    const val MANDATORY_LENGTH = 60

    /**
     * Parse a BCBP string that starts at index 0 of [raw].
     *
     * [nowMs] resolves the Julian day of flight, which carries no year — see
     * [IsoDates.julianDayToDate].
     */
    fun parse(raw: String, nowMs: Long): BoardingPass? {
        val s = raw.trimStart()
        if (s.length < MANDATORY_LENGTH) return null

        if (s[0] != 'M') return null
        if (s[1] !in '1'..'4') return null
        if (s[22] != 'E') return null

        val name = s.substring(2, 22).trim()
        val pnr = s.substring(23, 30).trim()
        val from = s.substring(30, 33).trim()
        val to = s.substring(33, 36).trim()
        val carrier = s.substring(36, 39).trim()
        val flight = s.substring(39, 44).trim()
        val julian = s.substring(44, 47).trim()
        val compartment = s.substring(47, 48).trim()
        val seat = s.substring(48, 52).trim()
        val sequence = s.substring(52, 57).trim()
        val variableSize = s.substring(58, 60)

        // Structure, not plausibility. Each of these is what the spec fixes, and a run of
        // text that fails any of them is not a boarding pass however much it looks like one.
        if (name.isEmpty() || !name.all { it.isLetter() || it in " /.'-" }) return null
        if (pnr.isEmpty() || !pnr.all { it.isLetterOrDigit() }) return null
        if (from.length != 3 || !from.all { it.isLetter() }) return null
        if (to.length != 3 || !to.all { it.isLetter() }) return null
        if (carrier.isEmpty() || carrier.length > 3 || !carrier.all { it.isLetterOrDigit() }) return null
        if (flight.isEmpty() || !flight.all { it.isLetterOrDigit() }) return null
        if (julian.length != 3 || !julian.all { it.isDigit() }) return null
        if (!variableSize.all { it.isDigit() || it in "abcdefABCDEF" }) return null

        val day = julian.toInt()
        val date = IsoDates.julianDayToDate(day, nowMs)

        return BoardingPass(
            payload = s.substring(0, MANDATORY_LENGTH),
            format = FORMAT,
            passengerName = name.ifBlank { null },
            recordLocator = pnr,
            fromAirport = from.uppercase(),
            toAirport = to.uppercase(),
            carrier = carrier.uppercase(),
            // "0834" is flight 834. The zero padding is the format's, not the airline's.
            flightNumber = flight.trimStart('0').ifBlank { flight },
            flightDate = date?.let { IsoDates.formatDay(it) },
            cabin = compartment.ifBlank { null },
            seat = seat.trimStart('0').ifBlank { null },
            sequenceNumber = sequence.trimStart('0').ifBlank { sequence.ifBlank { null } },
        )
    }

    /**
     * The first BCBP record anywhere in [text].
     *
     * Extracted text from a PDF or a mail runs the payload together with whatever surrounds
     * it, so every `M` followed by a leg count is tried and the first one that validates
     * wins. Scanning candidates is cheap; [parse] is what does the deciding.
     */
    fun find(text: String, nowMs: Long): BoardingPass? {
        var i = 0
        while (i >= 0 && i <= text.length - MANDATORY_LENGTH) {
            val at = text.indexOf('M', i)
            if (at < 0 || at > text.length - MANDATORY_LENGTH) return null
            parse(text.substring(at), nowMs)?.let { return it }
            i = at + 1
        }
        return null
    }

    /** Every distinct BCBP record in [text], for a PDF holding a whole family's passes. */
    fun findAll(text: String, nowMs: Long): List<BoardingPass> {
        val out = mutableListOf<BoardingPass>()
        var i = 0
        while (i <= text.length - MANDATORY_LENGTH) {
            val at = text.indexOf('M', i)
            if (at < 0 || at > text.length - MANDATORY_LENGTH) break
            val pass = parse(text.substring(at), nowMs)
            if (pass != null) {
                out += pass
                i = at + MANDATORY_LENGTH
            } else {
                i = at + 1
            }
        }
        return out.distinctBy { it.payload }
    }

    const val FORMAT = "BCBP-M1"
}
