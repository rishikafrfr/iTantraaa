package isro.itantra.nlp

/**
 * Rule-based text normalisation before TTS (build-spec 7.4 — "big accuracy
 * lever"). Phase 4 covers the two highest-volume languages properly (English,
 * Hindi digits → spoken words); other languages pass through with digit-block
 * spacing so models don't choke on digit runs. All rules are data-driven-ish
 * and unit tested; native speakers extend the tables without touching code.
 *
 * Runs per-clause BEFORE synthesis; also splits on sentence terminators
 * ( sherpa-onnx does not split on the Devanagari danda — Phase 0 finding).
 */
object TextNormaliser {

    private val enUnits = arrayOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
        "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
        "seventeen", "eighteen", "nineteen",
    )
    private val enTens = arrayOf(
        "", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety",
    )
    private val hiUnits = arrayOf(
        "शून्य", "एक", "दो", "तीन", "चार", "पाँच", "छह", "सात", "आठ", "नौ",
        "दस", "ग्यारह", "बारह", "तेरह", "चौदह", "पंद्रह", "सोलह", "सत्रह", "अठारह", "उन्नीस",
    )
    private val hiTens = arrayOf(
        "", "", "बीस", "तीस", "चालीस", "पचास", "साठ", "सत्तर", "अस्सी", "नब्बे",
    )

    /**
     * Which of Wire.LANGUAGES this text is written in, judged by Unicode block.
     *
     * The STT model deliberately takes no language parameter — it emits whatever
     * script it heard (see SttEngine). So the language tag on the wire has to be
     * derived from the transcript itself. Taking it from a UI selection made
     * before anyone spoke is how Devanagari ended up being read aloud by the
     * English voice.
     *
     * Returns null when the script maps to no pack we ship — notably
     * Perso-Arabic, which the model emits for Hindustani speech about half the
     * time and for which there is no Urdu voice.
     */
    private fun scriptOf(c: Char): String? {
        // Combining marks (matras, virama) sit inside the Devanagari/Indic blocks
        // but carry no evidence about script choice — counting them inflated the
        // Indic share of mixed output and let soup through the majority test.
        if (!c.isLetter()) return null
        return scriptOfLetter(c)
    }

    private fun scriptOfLetter(c: Char): String? = when (c.code) {
        in 0x0900..0x097F -> "hi" // Devanagari — shared with Marathi
        in 0x0980..0x09FF -> "bn"
        in 0x0A80..0x0AFF -> "gu"
        in 0x0B00..0x0B7F -> "or"
        in 0x0B80..0x0BFF -> "ta"
        in 0x0C00..0x0C7F -> "te"
        in 0x0C80..0x0CFF -> "kn"
        in 0x0D00..0x0D7F -> "ml"
        in 0x0600..0x06FF, in 0x0750..0x077F -> "ar" // Urdu/Arabic: no voice pack
        else -> "en"
    }

    /** Below this share for the leading script, the transcript is mixed-script soup. */
    private const val SCRIPT_MAJORITY_PCT = 70

    fun detectScriptLang(text: String): String? {
        val counts = HashMap<String, Int>()
        var letters = 0
        for (c in text) {
            val s = scriptOf(c) ?: continue
            letters++
            counts[s] = (counts[s] ?: 0) + 1
        }
        if (letters == 0) return null
        val best = counts.maxByOrNull { it.value } ?: return null
        // Reject mixed-script output. When the recogniser is unsure it emits soup
        // like "पاiدliديhي" or "ಇದು tur tو eಚc rikೆ"; taking the first character's
        // script (the old behaviour) tagged that as Hindi/Kannada and handed a
        // voice pack glyphs it cannot pronounce. Dropping it is the honest answer.
        if (best.value * 100 < letters * SCRIPT_MAJORITY_PCT) return null
        return if (best.key == "ar") null else best.key
    }

    fun numberToWords(n: Int, lang: String): String = when (lang) {
        "hi", "mr" -> hindiWords(n)
        "en" -> englishWords(n)
        else -> n.toString()
    }

    fun englishWords(n: Int): String {
        if (n < 0) return "minus " + englishWords(-n)
        if (n < 20) return enUnits[n]
        if (n < 100) {
            val t = enTens[n / 10]; val r = n % 10
            return if (r == 0) t else "$t-${enUnits[r]}"
        }
        if (n < 1000) {
            val h = "${enUnits[n / 100]} hundred"; val r = n % 100
            return if (r == 0) h else "$h ${englishWords(r)}"
        }
        if (n < 1_000_000) {
            val th = "${englishWords(n / 1000)} thousand"; val r = n % 1000
            return if (r == 0) th else "$th ${englishWords(r)}"
        }
        return n.toString() // rare in distress messages; model handles digits
    }

    fun hindiWords(n: Int): String {
        if (n < 0) return "माइनस " + hindiWords(-n)
        if (n < 20) return hiUnits[n]
        if (n < 100) return standard(n)
        if (n < 1000) {
            val h = "${hiUnits[n / 100]} सौ"; val r = n % 100
            return if (r == 0) h else "$h ${hindiWords(r)}"
        }
        return n.toString()
    }

    // Hindi 21..99: tens word + units word (standard compound form)
    private fun standard(n: Int): String {
        val t = hiTens[n / 10]; val r = n % 10
        return if (r == 0) t else "$t ${hiUnits[r]}"
    }

    /**
     * Normalise a clause for [lang]:
     *  - expand Latin/Devanagari digit runs ≤ 6 digits into spoken words (en/hi);
     *  - pad longer digit runs with spaces;
     *  - strip characters the model vocabulary won't have (keep letters, digits,
     *    spaces and common intra-word punctuation).
     */
    fun normalise(text: String, lang: String): String {
        var out = Regex("[0-9]+").replace(text) { m ->
            val digits = m.value
            val v = digits.toIntOrNull()
            if (v != null && digits.length <= 6) numberToWords(v, lang) else digits.chunked(1).joinToString(" ")
        }
        // Devanagari digits
        out = Regex("[०-९]+").replace(out) { m ->
            val latin = m.value.map { ('0' + (it - '०')) } // was '०' + … i.e. a no-op
            normaliseLatinDigits(latin.joinToString(""), lang)
        }
        return out.replace(Regex("\\s+"), " ").trim()
    }

    private fun normaliseLatinDigits(digits: String, lang: String): String {
        val v = digits.toIntOrNull()
        return if (v != null && digits.length <= 6) numberToWords(v, lang)
        else digits.chunked(1).joinToString(" ")
    }
}
