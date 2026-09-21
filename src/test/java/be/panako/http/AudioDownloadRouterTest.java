package be.panako.http;

import be.panako.util.Config;
import be.panako.util.Key;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AudioDownloadRouterTest {

	private static final String CDN_HOST = "cdn-broadcast.ozenx.io";
	private static final String ORIGIN = "https://hel1.your-objectstorage.com/broadcast";

	@AfterEach
	void resetConfiguration() {
		Config.set(Key.AUDIO_CDN_HOST, "");
		Config.set(Key.AUDIO_ORIGIN_BASE_URL, "");
		Config.set(Key.AUDIO_ORIGIN_FALLBACK_TO_CDN, "TRUE");
	}

	@Test
	void rewritesExactCdnHostnameAndPreservesExtension() {
		String source = "https://cdn-broadcast.ozenx.io/recordings/abc/2026/09/audio.m4a";
		assertEquals("https://hel1.your-objectstorage.com/broadcast/recordings/abc/2026/09/audio.m4a",
				AudioDownloadRouter.resolveDownloadUrl(source, CDN_HOST, ORIGIN));
	}

	@Test
	void doesNotDuplicateOriginPathPrefix() {
		String source = "https://cdn-broadcast.ozenx.io/broadcast/recordings/audio.m4a";
		assertEquals("https://hel1.your-objectstorage.com/broadcast/recordings/audio.m4a",
				AudioDownloadRouter.resolveDownloadUrl(source, CDN_HOST, ORIGIN + "/"));
	}

	@Test
	void preservesRawQueryString() {
		String source = "https://cdn-broadcast.ozenx.io/recordings/audio.m4a?token=a%2Fb&part=2";
		assertEquals("https://hel1.your-objectstorage.com/broadcast/recordings/audio.m4a?token=a%2Fb&part=2",
				AudioDownloadRouter.resolveDownloadUrl(source, CDN_HOST, ORIGIN));
	}

	@Test
	void leavesDifferentHostnameUnchanged() {
		String source = "https://cdn-broadcast.ozenx.io.example/recordings/audio.m4a";
		assertEquals(source, AudioDownloadRouter.resolveDownloadUrl(source, CDN_HOST, ORIGIN));
	}

	@Test
	void emptyConfigurationDisablesRewrite() {
		String source = "https://cdn-broadcast.ozenx.io/recordings/audio.m4a";
		assertEquals(source, AudioDownloadRouter.resolveDownloadUrl(source, "", ORIGIN));
		assertEquals(source, AudioDownloadRouter.resolveDownloadUrl(source, CDN_HOST, ""));
	}

	@Test
	void rejectsInvalidOrigin() {
		String source = "https://cdn-broadcast.ozenx.io/recordings/audio.m4a";
		assertThrows(IllegalArgumentException.class,
				() -> AudioDownloadRouter.resolveDownloadUrl(source, CDN_HOST, "not-a-url"));
		assertThrows(IllegalArgumentException.class,
				() -> AudioDownloadRouter.resolveDownloadUrl(source, CDN_HOST, "ftp://origin/broadcast"));
	}

	@Test
	void redactsQueryAndUserInfoFromLogs() {
		assertEquals("https://cdn-broadcast.ozenx.io/recordings/audio.m4a",
				HttpUtil.redactUrl("https://user:password@cdn-broadcast.ozenx.io/recordings/audio.m4a?token=secret#part"));
	}

	@Test
	void originFailureFallsBackToOriginalCdnAndRemovesPartialFile() throws IOException {
		Config.set(Key.AUDIO_CDN_HOST, CDN_HOST);
		Config.set(Key.AUDIO_ORIGIN_BASE_URL, ORIGIN);
		Config.set(Key.AUDIO_ORIGIN_FALLBACK_TO_CDN, "TRUE");
		String source = "https://cdn-broadcast.ozenx.io/recordings/audio.m4a?secret=value";
		Path target = Files.createTempFile("audio-router-test", ".m4a");
		List<String> attempts = new ArrayList<>();

		AudioDownloadRouter.download(source, target, 1024, (url, path, maxBytes) -> {
			attempts.add(url);
			if (url.contains("your-objectstorage.com")) {
				Files.write(path, "partial".getBytes(StandardCharsets.UTF_8));
				throw new IOException("origin unavailable");
			}
			assertFalse(Files.exists(path), "partial origin file must be removed before fallback");
			Files.write(path, "complete".getBytes(StandardCharsets.UTF_8));
		});

		assertEquals(List.of(
				"https://hel1.your-objectstorage.com/broadcast/recordings/audio.m4a?secret=value",
				source), attempts);
		assertEquals("complete", Files.readString(target));
		Files.deleteIfExists(target);
	}

	@Test
	void disabledFallbackRemovesPartialFileAndPreservesFailure() throws IOException {
		Config.set(Key.AUDIO_CDN_HOST, CDN_HOST);
		Config.set(Key.AUDIO_ORIGIN_BASE_URL, ORIGIN);
		Config.set(Key.AUDIO_ORIGIN_FALLBACK_TO_CDN, "FALSE");
		Path target = Files.createTempFile("audio-router-test", ".m4a");

		IOException failure = assertThrows(IOException.class, () -> AudioDownloadRouter.download(
				"https://cdn-broadcast.ozenx.io/recordings/audio.m4a", target, 1024,
				(url, path, maxBytes) -> {
					Files.write(path, "partial".getBytes(StandardCharsets.UTF_8));
					throw new IOException("origin unavailable");
				}));

		assertEquals("origin unavailable", failure.getMessage());
		assertFalse(Files.exists(target));
	}
}
