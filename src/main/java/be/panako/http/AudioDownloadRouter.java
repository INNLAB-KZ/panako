package be.panako.http;

import be.panako.util.Config;
import be.panako.util.Key;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Routes configured CDN audio URLs through a regional object-storage origin. */
final class AudioDownloadRouter {

	private static final Logger LOG = Logger.getLogger(AudioDownloadRouter.class.getName());

	@FunctionalInterface
	interface DownloadOperation {
		void download(String url, Path target, long maxBytes) throws IOException;
	}

	private AudioDownloadRouter() {}

	static void download(String sourceUrl, Path target, long maxBytes) throws IOException {
		download(sourceUrl, target, maxBytes, HttpUtil::downloadWithRetry);
	}

	static void download(String sourceUrl, Path target, long maxBytes,
						 DownloadOperation downloadOperation) throws IOException {
		final String actualUrl;
		try {
			actualUrl = resolveDownloadUrl(
					sourceUrl,
					Config.get(Key.AUDIO_CDN_HOST),
					Config.get(Key.AUDIO_ORIGIN_BASE_URL));
		} catch (IllegalArgumentException e) {
			IOException configurationFailure = new IOException("Invalid audio origin configuration: " + e.getMessage(), e);
			deletePartialFile(target, configurationFailure);
			throw configurationFailure;
		}
		boolean usingOrigin = !actualUrl.equals(sourceUrl);

		try {
			downloadOperation.download(actualUrl, target, maxBytes);
			logSuccess(actualUrl, sourceUrl, target);
			return;
		} catch (IOException originFailure) {
			deletePartialFile(target, originFailure);
			if (!usingOrigin || !Config.getBoolean(Key.AUDIO_ORIGIN_FALLBACK_TO_CDN)) {
				throw originFailure;
			}

			LOG.log(Level.WARNING, "audio_origin_download_failed actual_url="
					+ HttpUtil.redactUrl(actualUrl) + " source_url=" + HttpUtil.redactUrl(sourceUrl)
					+ " error_type=" + originFailure.getClass().getSimpleName());
			try {
				downloadOperation.download(sourceUrl, target, maxBytes);
				logSuccess(sourceUrl, sourceUrl, target);
			} catch (IOException fallbackFailure) {
				deletePartialFile(target, fallbackFailure);
				fallbackFailure.addSuppressed(originFailure);
				throw fallbackFailure;
			}
		}
	}

	static String resolveDownloadUrl(String sourceUrl, String cdnHost, String originBaseUrl) {
		if (isBlank(cdnHost) || isBlank(originBaseUrl)) return sourceUrl;

		URI source = parseAbsoluteHttpUri(sourceUrl, "source URL");
		if (!cdnHost.trim().equalsIgnoreCase(source.getHost())) return sourceUrl;

		URI origin = parseAbsoluteHttpUri(originBaseUrl.trim(), "AUDIO_ORIGIN_BASE_URL");
		if (origin.getRawQuery() != null || origin.getRawFragment() != null || origin.getRawUserInfo() != null) {
			throw new IllegalArgumentException("AUDIO_ORIGIN_BASE_URL must not contain user info, query, or fragment");
		}

		String path = joinPathsWithoutDuplicatePrefix(origin.getRawPath(), source.getRawPath());
		StringBuilder resolved = new StringBuilder();
		resolved.append(origin.getScheme()).append("://").append(origin.getRawAuthority()).append(path);
		if (source.getRawQuery() != null) resolved.append('?').append(source.getRawQuery());
		return resolved.toString();
	}

	private static URI parseAbsoluteHttpUri(String value, String settingName) {
		final URI uri;
		try {
			uri = URI.create(value);
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException(settingName + " is not a valid URL", e);
		}
		String scheme = uri.getScheme();
		if (scheme == null || uri.getHost() == null
				|| !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
			throw new IllegalArgumentException(settingName + " must be an absolute HTTP(S) URL");
		}
		return uri;
	}

	private static String joinPathsWithoutDuplicatePrefix(String basePath, String sourcePath) {
		String base = normalizePath(basePath);
		String source = normalizePath(sourcePath);
		if (base.equals("/")) return source;
		if (source.equals(base) || source.startsWith(base + "/")) return source;
		return base + (source.equals("/") ? "" : source);
	}

	private static String normalizePath(String path) {
		if (path == null || path.isEmpty() || path.equals("/")) return "/";
		String normalized = path.startsWith("/") ? path : "/" + path;
		while (normalized.length() > 1 && normalized.endsWith("/")) {
			normalized = normalized.substring(0, normalized.length() - 1);
		}
		return normalized;
	}

	private static boolean isBlank(String value) {
		return value == null || value.trim().isEmpty();
	}

	private static void logSuccess(String actualUrl, String sourceUrl, Path target) {
		try {
			LOG.info("audio_download_succeeded actual_url=" + HttpUtil.redactUrl(actualUrl)
					+ " source_url=" + HttpUtil.redactUrl(sourceUrl)
					+ " size_bytes=" + Files.size(target));
		} catch (IOException e) {
			LOG.log(Level.WARNING, "audio_download_size_unavailable actual_url="
					+ HttpUtil.redactUrl(actualUrl) + " source_url=" + HttpUtil.redactUrl(sourceUrl), e);
		}
	}

	private static void deletePartialFile(Path target, IOException failure) {
		try {
			Files.deleteIfExists(target);
		} catch (IOException cleanupFailure) {
			failure.addSuppressed(cleanupFailure);
		}
	}
}
