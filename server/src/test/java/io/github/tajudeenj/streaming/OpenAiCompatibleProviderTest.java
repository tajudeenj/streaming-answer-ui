package io.github.tajudeenj.streaming;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class OpenAiCompatibleProviderTest {

    @Test
    void extractsContentDelta() {
        assertEquals("Hello", OpenAiCompatibleProvider.extractDelta("{\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}"));
    }

    @Test
    void ignoresRoleOnlyEmptyAndMalformedChunks() {
        assertNull(OpenAiCompatibleProvider.extractDelta("{\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}"));
        assertNull(OpenAiCompatibleProvider.extractDelta("{\"choices\":[]}"));
        assertNull(OpenAiCompatibleProvider.extractDelta("{not json"));
    }
}
