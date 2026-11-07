package io.github.tajudeenj.streaming;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedactorTest {

    @Test
    void masksUaeIdentifiersAndContactDetails() {
        Redactor.Result r = Redactor.redact(
                "ID 784-1990-1234567-1, IBAN AE07 0331 2345 6789 0123 456, mail me at x.y@bank.ae or +971 50 123 4567");
        assertFalse(r.text().contains("784-1990"));
        assertFalse(r.text().contains("AE07"));
        assertFalse(r.text().contains("x.y@bank.ae"));
        assertFalse(r.text().contains("123 4567"));
        assertEquals(4, r.count());
    }

    @Test
    void masksOnlyLuhnValidCardNumbers() {
        assertTrue(Redactor.redact("card 4111 1111 1111 1111").text().contains("[CARD]"));
        assertEquals(0, Redactor.redact("reference 1234 5678 9012 3456").count());
    }

    @Test
    void leavesOrdinaryQuestionsUntouched() {
        String q = "How is profit calculated on a 5 year Ijarah?";
        Redactor.Result r = Redactor.redact(q);
        assertEquals(q, r.text());
        assertEquals(0, r.count());
    }
}
