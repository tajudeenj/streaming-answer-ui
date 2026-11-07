package io.github.tajudeenj.streaming;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonTest {

    @Test
    void roundTripsNestedValues() {
        Object v = Json.parse("{\"q\":\"Murabaha \\u00e9\",\"n\":[1,2.5,true,null],\"o\":{}}");
        Map<?, ?> m = (Map<?, ?>) v;
        assertEquals("Murabaha \u00e9", m.get("q"));
        assertEquals(List.of(1.0, 2.5, true), ((List<?>) m.get("n")).subList(0, 3));
    }

    @Test
    void escapesHtmlAndControlCharacters() {
        assertEquals("{\"t\":\"\\u003cscript\\u003e\\n\"}", Json.obj("t", "<script>\n"));
    }

    @Test
    void rejectsMalformedAndTrailingInput() {
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\":"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{} x"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("\"a\u0001\""));
    }

    @Test
    void rejectsExcessiveNesting() {
        String deep = "[".repeat(100) + "]".repeat(100);
        assertThrows(IllegalArgumentException.class, () -> Json.parse(deep));
    }
}
