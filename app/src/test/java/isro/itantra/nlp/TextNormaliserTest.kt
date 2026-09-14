package isro.itantra.nlp

import org.junit.Assert.assertEquals
import org.junit.Test

class TextNormaliserTest {

    @Test
    fun `english numbers to words`() {
        assertEquals("zero", TextNormaliser.englishWords(0))
        assertEquals("nineteen", TextNormaliser.englishWords(19))
        assertEquals("twenty-one", TextNormaliser.englishWords(21))
        assertEquals("forty", TextNormaliser.englishWords(40))
        assertEquals("one hundred five", TextNormaliser.englishWords(105))
        assertEquals("three hundred twenty", TextNormaliser.englishWords(320))
        assertEquals("two thousand five hundred", TextNormaliser.englishWords(2500))
    }

    @Test
    fun `hindi numbers to words`() {
        assertEquals("शून्य", TextNormaliser.hindiWords(0))
        assertEquals("उन्नीस", TextNormaliser.hindiWords(19))
        assertEquals("बीस", TextNormaliser.hindiWords(20))
        assertEquals("पचास एक", TextNormaliser.hindiWords(51))
        assertEquals("दो सौ पचास", TextNormaliser.hindiWords(250))
    }

    @Test
    fun `clause normalisation expands digits`() {
        assertEquals(
            "move to gate three now",
            TextNormaliser.normalise("move to gate 3 now", "en"),
        )
        assertEquals(
            "आपातकालीन नंबर दस है",
            TextNormaliser.normalise("आपातकालीन नंबर 10 है", "hi"),
        )
    }

    @Test
    fun `devanagari digits handled`() {
        assertEquals("संख्या चालीस पाँच", TextNormaliser.normalise("संख्या ४५", "hi"))
        assert(!Regex("[०-९]").containsMatchIn(TextNormaliser.normalise("संख्या ४५", "hi")))
    }

    @Test
    fun `script detection routes each transcript to its own voice`() {
        // the live-test failure: Devanagari must never be tagged "en"
        assertEquals("hi", TextNormaliser.detectScriptLang("मदद करो"))
        assertEquals("bn", TextNormaliser.detectScriptLang("জরুরি বার্তা"))
        assertEquals("gu", TextNormaliser.detectScriptLang("કટોકટીનો સંદેશ"))
        assertEquals("ta", TextNormaliser.detectScriptLang("அவசர செய்தி"))
        assertEquals("te", TextNormaliser.detectScriptLang("అత్యవసర సందేశం"))
        assertEquals("kn", TextNormaliser.detectScriptLang("ತುರ್ತು ಸಂದೇಶ"))
        assertEquals("ml", TextNormaliser.detectScriptLang("അടിയന്തര സന്ദേശം"))
        assertEquals("or", TextNormaliser.detectScriptLang("ଜରୁରୀ ବାର୍ତ୍ତା"))
        assertEquals("en", TextNormaliser.detectScriptLang("evacuate immediately"))
        // Perso-Arabic: the model emits this for Hindustani and we ship no Urdu voice
        assertEquals(null, TextNormaliser.detectScriptLang("پلیس ہلپ میں"))
        assertEquals(null, TextNormaliser.detectScriptLang("  123  "))
    }

    @Test
    fun `mixed-script soup is rejected rather than mis-tagged`() {
        // real captures from the live mic test: the recogniser hedged across
        // scripts. Taking the first character tagged these hi / kn and sent
        // unpronounceable glyphs to a voice pack.
        assertEquals(null, TextNormaliser.detectScriptLang("पاiدliديhي"))
        assertEquals(null, TextNormaliser.detectScriptLang("ಇದು tur tو eಚc rikೆ"))
        assertEquals(null, TextNormaliser.detectScriptLang("iदi aत्यव सरा हिच्चाriका"))
        // a clean sentence with one borrowed English word still passes
        assertEquals("hi", TextNormaliser.detectScriptLang("यह एक आपातकालीन चेतावनी है ok"))
    }

    @Test
    fun `long digit runs are spaced not garbled`() {
        val out = TextNormaliser.normalise("code 123456789", "en")
        assertEquals("code 1 2 3 4 5 6 7 8 9", out)
    }
}
