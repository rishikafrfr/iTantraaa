package isro.itantra.nlp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every input here is a real transcript captured from the live microphone test,
 * where the recogniser heard Hindi/Marathi correctly and wrote it in Urdu.
 */
class UrduToDevanagariTest {

    @Test
    fun `abjad maps onto abugida without restoring short vowels`() {
        // the whole premise: Urdu omits 'a', Devanagari implies it
        assertEquals("मदद", UrduToDevanagari.convert("مدد"))
        assertEquals("करो", UrduToDevanagari.convert("کرو"))
        assertEquals("कालीन", UrduToDevanagari.convert("کالین"))
    }

    @Test
    fun `captured emergency sentences become speakable Devanagari`() {
        val out = UrduToDevanagari.convert("یہ ایک آپت کالین چتاونی ہے")
        assertEquals("यह एक आपत कालीन चतावनी है", out)
        assertFalse(Regex("[\\u0600-\\u06FF]").containsMatchIn(out))
    }

    @Test
    fun `marathi help request survives`() {
        val out = UrduToDevanagari.convert("مالا مدد کرا")
        assertEquals("मला मदद करा", out)
    }

    @Test
    fun `ya-before-vowel is a glide not a matra`() {
        // captured live: کرپیا is "kripya"; a matra reading gave करपीआ
        assertEquals("करपया", UrduToDevanagari.convert("کرپیا"))
        // the surrounding cases must not regress
        assertEquals("कालीन", UrduToDevanagari.convert("کالین"))
        assertEquals("करो", UrduToDevanagari.convert("کرو"))
        assertEquals("चतावनी", UrduToDevanagari.convert("چتاونی"))
    }

    @Test
    fun `aspirates are single letters not consonant plus ha`() {
        assertEquals("भाई", UrduToDevanagari.convert("بھای"))
        assertEquals("खतरा", UrduToDevanagari.convert("خطرہ"))
    }

    @Test
    fun `script detection gates the conversion`() {
        assertTrue(UrduToDevanagari.looksUrdu("یہ ایک آپت کالین چتاونی ہے"))
        assertFalse(UrduToDevanagari.looksUrdu("यह एक आपातकालीन चेतावनी है"))
        assertFalse(UrduToDevanagari.looksUrdu("evacuate immediately"))
        assertFalse(UrduToDevanagari.looksUrdu("12345"))
    }

    @Test
    fun `converted text is then detected as hindi`() {
        val out = UrduToDevanagari.convert("پلیس ہلپ میں")
        assertEquals("hi", TextNormaliser.detectScriptLang(out))
    }
}
