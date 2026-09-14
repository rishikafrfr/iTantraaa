package isro.itantra.nlp

/**
 * Perso-Arabic → Devanagari transliteration for Hindustani.
 *
 * Why this exists: the omnilingual recogniser hears Hindi correctly but writes
 * it in Urdu script roughly half the time (both are the same spoken language,
 * and the model has no language argument to constrain). No voice pack here can
 * pronounce Perso-Arabic, so those messages were being dropped. Converting is
 * the only way to keep them.
 *
 * Why it works better than it sounds: Urdu is an abjad that omits the short
 * vowel 'a'; Devanagari is an abugida where every bare consonant already
 * carries it. They line up — مدد maps consonant-for-consonant to मदद with
 * nothing to restore. What does need care is aspirates (do-chashmi he),
 * word-initial vowels, and the handful of function words that carry vowels the
 * script doesn't write.
 *
 * This is a pronunciation aid, not a spelling engine. चतावनी instead of
 * चेतावनी is fine — the voice says the same thing. Exactness is not the goal;
 * intelligibility is.
 */
object UrduToDevanagari {

    /** Consonant + do-chashmi he (ھ) is one aspirated Devanagari letter. */
    private val ASPIRATES = mapOf(
        "بھ" to "भ", "پھ" to "फ", "تھ" to "थ", "ٹھ" to "ठ", "جھ" to "झ",
        "چھ" to "छ", "دھ" to "ध", "ڈھ" to "ढ", "کھ" to "ख", "گھ" to "घ",
        "ڑھ" to "ढ़", "رھ" to "र्ह", "لھ" to "ल्ह", "مھ" to "म्ह", "نھ" to "न्ह",
        "سھ" to "स्ह",
    )

    /** Nukta forms folded to their plain letter: a TTS voice pronounces them alike. */
    private val CONSONANTS = mapOf(
        'ب' to "ब", 'پ' to "प", 'ت' to "त", 'ٹ' to "ट", 'ث' to "स",
        'ج' to "ज", 'چ' to "च", 'ح' to "ह", 'خ' to "ख", 'د' to "द",
        'ڈ' to "ड", 'ذ' to "ज", 'ر' to "र", 'ڑ' to "ड़", 'ز' to "ज",
        'ژ' to "झ", 'س' to "स", 'ش' to "श", 'ص' to "स", 'ض' to "ज",
        'ط' to "त", 'ظ' to "ज", 'غ' to "ग", 'ف' to "फ", 'ق' to "क",
        'ک' to "क", 'گ' to "ग", 'ل' to "ल", 'م' to "म", 'ن' to "न",
        'ہ' to "ह", 'ه' to "ह", 'ھ' to "ह", 'ع' to "", 'ء' to "",
    )

    /** Vowel carriers take one form at the start of a word and another inside it. */
    private val INITIAL_VOWELS = mapOf(
        "آ" to "आ", "ای" to "ई", "اے" to "ए", "او" to "ओ",
        "ا" to "अ", "ی" to "य", "و" to "व", "ے" to "ए",
    )
    private val DIACRITICS = setOf('ٰ', 'ّ', 'ْ', 'ٓ', 'ٔ', 'ً', 'ٌ', 'ٍ', 'َ', 'ُ', 'ِ')
    private val MEDIAL_VOWELS = mapOf(
        'ا' to "ा", 'ی' to "ी", 'و' to "ो", 'ے' to "े", 'آ' to "ा",
    )

    /**
     * ی and و are ambiguous: vowel or glide. After a consonant they are the
     * matra (کرو → करो). After another vowel they are not — word-finally they
     * become an independent vowel (بھای → भाई), otherwise a glide consonant
     * (چتاونی → चतावनी, never चताोनी).
     */
    private val AFTER_VOWEL_FINAL = mapOf('ی' to "ई", 'و' to "ऊ", 'ے' to "ए", 'ا' to "आ")
    private val AFTER_VOWEL_GLIDE = mapOf('ی' to "य", 'و' to "व", 'ے' to "ए", 'ا' to "ा")

    /** Devanagari independent vowels and matras. */
    private fun isVowelish(c: Char) = c.code in 0x0905..0x0914 || c.code in 0x093E..0x094C

    /** Perso-Arabic vowel carriers. */
    private val VOWEL_LETTERS = setOf('ا', 'آ', 'و', 'ی', 'ے')

    /**
     * Words whose vowels the abjad simply does not write. Small on purpose —
     * these few carry a large share of any emergency sentence.
     */
    private val WORDS = mapOf(
        "ہے" to "है", "ہیں" to "हैं", "میں" to "में", "ہی" to "ही", "یہ" to "यह",
        "وہ" to "वह", "اور" to "और", "نہیں" to "नहीं", "کا" to "का", "کی" to "की",
        "کے" to "के", "کو" to "को", "سے" to "से", "پر" to "पर", "نے" to "ने",
        "ایک" to "एक", "مدد" to "मदद", "کرو" to "करो", "کرا" to "करा",
        "کریں" to "करें", "مجھے" to "मुझे", "مجھ" to "मुझ", "ہم" to "हम",
        "تم" to "तुम", "آپ" to "आप", "کیا" to "क्या", "جلدی" to "जल्दी",
        "خطرہ" to "खतरा", "پانی" to "पानी", "مالا" to "मला", "چاہیے" to "चाहिए",
    )

    private fun isUrduLetter(c: Char) =
        c.code in 0x0600..0x06FF || c.code in 0x0750..0x077F

    /** True if [text] is written mainly in Perso-Arabic. */
    fun looksUrdu(text: String): Boolean {
        val letters = text.count { it.isLetter() }
        if (letters == 0) return false
        return text.count { it.isLetter() && isUrduLetter(it) } * 2 > letters
    }

    fun convert(text: String): String =
        text.split(' ').joinToString(" ") { word ->
            if (word.isBlank()) word else WORDS[word] ?: convertWord(word)
        }

    private fun convertWord(word: String): String {
        val out = StringBuilder()
        var i = 0
        var atStart = true
        while (i < word.length) {
            val pair = if (i + 1 < word.length) word.substring(i, i + 2) else null
            // aspirate digraph must win, or the do-chashmi he becomes its own ह
            val aspirate = pair?.let { ASPIRATES[it] }
            val initialVowel = if (atStart && pair != null) INITIAL_VOWELS[pair] else null
            when {
                aspirate != null -> { out.append(aspirate); i += 2; atStart = false }
                initialVowel != null -> { out.append(initialVowel); i += 2; atStart = false }
                else -> {
                    val c = word[i]
                    when {
                        c == 'ں' -> out.append("ं")
                        c in DIACRITICS -> {}
                        atStart && INITIAL_VOWELS.containsKey(c.toString()) ->
                            out.append(INITIAL_VOWELS.getValue(c.toString()))
                        !atStart && MEDIAL_VOWELS.containsKey(c) -> {
                            val afterVowel = out.isNotEmpty() && isVowelish(out.last())
                            val isFinal = i == word.length - 1
                            val beforeVowel = !isFinal && word[i + 1] in VOWEL_LETTERS
                            out.append(
                                when {
                                    // a vowel letter on either side means this is a
                                    // glide: کرپیا is karpya, not karpee-aa
                                    beforeVowel -> AFTER_VOWEL_GLIDE[c] ?: MEDIAL_VOWELS.getValue(c)
                                    !afterVowel -> MEDIAL_VOWELS.getValue(c)
                                    isFinal -> AFTER_VOWEL_FINAL[c] ?: MEDIAL_VOWELS.getValue(c)
                                    else -> AFTER_VOWEL_GLIDE[c] ?: MEDIAL_VOWELS.getValue(c)
                                }
                            )
                        }
                        // word-final he is the -aa ending (کمرہ -> कमरा), not ह;
                        // the exceptions (یہ, وہ, ہے) are in WORDS
                        c == 'ہ' && i == word.length - 1 && out.isNotEmpty() &&
                            !isVowelish(out.last()) -> out.append("ा")
                        CONSONANTS.containsKey(c) -> out.append(CONSONANTS.getValue(c))
                        // digits, punctuation, anything already Devanagari
                        else -> out.append(c)
                    }
                    if (isUrduLetter(c)) atStart = false
                    i++
                }
            }
        }
        return out.toString()
    }
}
