package be.panako.http;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import static org.junit.jupiter.api.Assertions.*;

class RedisRequestStringParsingTest {
    private String extract(String body, String key) throws Exception {
        Method method = PanakoRedisStreamWorker.class.getDeclaredMethod(
                "extractJsonString", String.class, String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, body, key);
    }

    @Test
    void nullFilenameFallsBackToAudioUrlInsteadOfNextFieldName() throws Exception {
        String body = "{\"audio_url\":\"https://example.test/recording.m4a\","
                + "\"filename\":null,\"segments\":null}";
        assertNull(extract(body, "filename"));
        Method fallback = PanakoRedisStreamWorker.class.getDeclaredMethod("filenameFromUrl", String.class);
        fallback.setAccessible(true);
        assertEquals("recording.m4a", fallback.invoke(null, extract(body, "audio_url")));
    }

    @Test
    void nonStringValuesDoNotConsumeFollowingFields() throws Exception {
        for (String value : new String[]{"null", "false", "42", "[]", "{}"}) {
            assertNull(extract("{\"filename\": \n " + value + ",\"segments\":null}", "filename"));
        }
    }

    @Test
    void explicitStringAndEmptyStringRemainStrings() throws Exception {
        assertEquals("recording.m4a", extract("{\"filename\": \n \"recording.m4a\"}", "filename"));
        assertEquals("", extract("{\"filename\":\"\"}", "filename"));
        assertNull(extract("{\"segments\":null}", "filename"));
    }
}
