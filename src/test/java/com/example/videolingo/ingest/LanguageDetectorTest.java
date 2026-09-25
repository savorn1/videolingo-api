package com.example.videolingo.ingest;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LanguageDetectorTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "How to order coffee in a cafe — learn the words you need for your first trip|en",
            "Comment commander un café : les mots dont vous avez besoin pour le voyage|fr",
            "Cómo pedir un café en España y qué decir en la cafetería|es",
            "Wie man im Café einen Kaffee bestellt und was man sagen muss|de",
            "Học tiếng Việt: cách gọi cà phê ở quán|vi",
            "コーヒーを注文する方法 — 旅行で使える日本語|ja",
            "如何在咖啡馆点咖啡 旅行中文|zh",
            "커피 주문하는 방법 여행 한국어|ko",
            "របៀបកម្ម៉ង់កាហ្វេ ភាសាខ្មែរ|km",
            "วิธีสั่งกาแฟ ภาษาไทยสำหรับการเดินทาง|th",
            "Как заказать кофе в кафе|ru"
    })
    void detects(String text, String code) {
        LanguageDetector.Guess g = LanguageDetector.detect(text);
        assertEquals(code, g == null ? null : g.code(), text);
        assertTrue(g.confidence() > 0.3 && g.confidence() < 1);
    }

    @Test
    void staysQuietWithoutSignal() {
        assertNull(LanguageDetector.detect("Vlog #12 🎥"));
        assertNull(LanguageDetector.detect((String) null));
        assertNull(LanguageDetector.detect("https://example.com @someone #tag"));
    }

    @Test
    void ignoresLinksAndHashtagsInDescriptions() {
        LanguageDetector.Guess g = LanguageDetector.detect("Lección 5", "Aprende cómo presentarte en español con ejemplos. https://example.com/the-and-you #english");
        assertEquals("es", g.code());
    }
}
