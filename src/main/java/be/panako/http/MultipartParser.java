package be.panako.http;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Minimal multipart/form-data parser that extracts a single file upload along
 * with any text-only form fields supplied alongside it.
 */
public class MultipartParser {

	/**
	 * Result of parsing a multipart request. {@link #formFields} contains every
	 * non-file text part keyed by its {@code name} attribute (for example
	 * {@code title} or {@code audio_url}). Callers that only need the uploaded
	 * file can ignore the map.
	 */
	public static class UploadedFile {
		public final String fieldName;
		public final String fileName;
		public final Path tempFile;
		public final Map<String, String> formFields;

		public UploadedFile(String fieldName, String fileName, Path tempFile, Map<String, String> formFields) {
			this.fieldName = fieldName;
			this.fileName = fileName;
			this.tempFile = tempFile;
			this.formFields = formFields == null ? new HashMap<>() : formFields;
		}
	}

	/**
	 * Parse a multipart/form-data request body and extract the first file part.
	 *
	 * @param inputStream  the request body
	 * @param contentType  the Content-Type header (must contain boundary)
	 * @param maxSizeBytes maximum allowed upload size in bytes
	 * @return the uploaded file, or null if no file part was found
	 * @throws IOException if reading fails or the upload exceeds the size limit
	 */
	public static UploadedFile parse(InputStream inputStream, String contentType, long maxSizeBytes) throws IOException {
		String boundary = extractBoundary(contentType);
		if (boundary == null) {
			throw new IOException("Missing boundary in Content-Type");
		}

		byte[] boundaryBytes = ("--" + boundary).getBytes(StandardCharsets.US_ASCII);
		byte[] body = readLimited(inputStream, maxSizeBytes);

		int pos = indexOf(body, boundaryBytes, 0);
		if (pos == -1) return null;

		Map<String, String> formFields = new HashMap<>();
		String foundFieldName = null;
		String foundFileName = null;
		Path foundTempFile = null;

		while (pos < body.length) {
			// Move past boundary
			pos += boundaryBytes.length;
			// Skip CRLF after boundary
			if (pos + 2 <= body.length && body[pos] == '\r' && body[pos + 1] == '\n') {
				pos += 2;
			} else if (pos + 2 <= body.length && body[pos] == '-' && body[pos + 1] == '-') {
				// End boundary
				break;
			}

			// Read headers until blank line
			int headerEnd = indexOf(body, "\r\n\r\n".getBytes(StandardCharsets.US_ASCII), pos);
			if (headerEnd == -1) break;

			String headers = new String(body, pos, headerEnd - pos, StandardCharsets.US_ASCII);
			pos = headerEnd + 4; // skip past \r\n\r\n

			// Find next boundary = end of this part's content
			int nextBoundary = indexOf(body, boundaryBytes, pos);
			if (nextBoundary == -1) break;

			// Content ends 2 bytes before boundary (CRLF)
			int contentEnd = nextBoundary - 2;
			if (contentEnd < pos) contentEnd = pos;

			// Parse Content-Disposition
			String fileName = extractHeaderParam(headers, "filename");
			String fieldName = extractHeaderParam(headers, "name");

			if (fileName != null && !fileName.isEmpty()) {
				// File part: write content to temp file. Capture only the first
				// file part; subsequent file parts are ignored so callers that
				// expect a single upload behave as before.
				if (foundTempFile == null) {
					String extension = "";
					int dot = fileName.lastIndexOf('.');
					if (dot >= 0) extension = fileName.substring(dot);
					Path tempFile = Files.createTempFile("panako_upload_", extension);
					try (OutputStream out = Files.newOutputStream(tempFile)) {
						out.write(body, pos, contentEnd - pos);
					}
					foundFieldName = fieldName;
					foundFileName = fileName;
					foundTempFile = tempFile;
				}
			} else if (fieldName != null && !fieldName.isEmpty()) {
				// Text form field. UTF-8 decoded because callers expect human strings.
				String value = new String(body, pos, contentEnd - pos, StandardCharsets.UTF_8);
				formFields.put(fieldName, value);
			}

			pos = nextBoundary;
		}

		if (foundTempFile == null) return null;
		return new UploadedFile(foundFieldName, foundFileName, foundTempFile, formFields);
	}

	private static String extractBoundary(String contentType) {
		if (contentType == null) return null;
		for (String part : contentType.split(";")) {
			String trimmed = part.trim();
			if (trimmed.startsWith("boundary=")) {
				String b = trimmed.substring("boundary=".length()).trim();
				if (b.startsWith("\"") && b.endsWith("\"")) {
					b = b.substring(1, b.length() - 1);
				}
				return b;
			}
		}
		return null;
	}

	private static String extractHeaderParam(String headers, String paramName) {
		String search = paramName + "=\"";
		int idx = headers.indexOf(search);
		if (idx == -1) return null;
		int start = idx + search.length();
		int end = headers.indexOf('"', start);
		if (end == -1) return null;
		return headers.substring(start, end);
	}

	private static byte[] readLimited(InputStream in, long maxBytes) throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		byte[] buf = new byte[8192];
		long total = 0;
		int read;
		while ((read = in.read(buf)) != -1) {
			total += read;
			if (total > maxBytes) {
				throw new IOException("Upload exceeds maximum size of " + (maxBytes / (1024 * 1024)) + " MB");
			}
			baos.write(buf, 0, read);
		}
		return baos.toByteArray();
	}

	private static int indexOf(byte[] haystack, byte[] needle, int from) {
		outer:
		for (int i = from; i <= haystack.length - needle.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (haystack[i + j] != needle[j]) continue outer;
			}
			return i;
		}
		return -1;
	}
}
