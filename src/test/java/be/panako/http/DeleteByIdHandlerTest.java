package be.panako.http;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeleteByIdHandlerTest {

	private HttpServer server;

	@BeforeEach
	void start() throws Exception {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/api/v1/delete/by_ids", new DeleteByIdHandler(true));
		server.createContext("/api/v1/delete/by_id", new DeleteByIdHandler());
		server.start();
	}

	@AfterEach
	void stop() {
		server.stop(0);
	}

	@Test
	void batchRejectsMissingEmptyAndOversizedLists() throws Exception {
		assertEquals(400, post("/api/v1/delete/by_ids", "{}"));
		assertEquals(400, post("/api/v1/delete/by_ids", "{\"identifiers\": []}"));
		assertEquals(400, post("/api/v1/delete/by_ids", "{\"identifiers\": [1, \"x\"]}"));
		StringBuilder ids = new StringBuilder();
		for (int i = 1; i <= DeleteByIdHandler.MAX_IDS + 1; i++) ids.append(i > 1 ? "," : "").append(i);
		assertEquals(400, post("/api/v1/delete/by_ids", "{\"identifiers\": [" + ids + "]}"));
	}

	@Test
	void singleDeleteRequiresIdentifier() throws Exception {
		assertEquals(400, post("/api/v1/delete/by_id", "{}"));
		assertEquals(405, get("/api/v1/delete/by_ids"));
	}

	private int post(String path, String body) throws Exception {
		HttpURLConnection conn = open(path);
		conn.setRequestMethod("POST");
		conn.setDoOutput(true);
		try (OutputStream out = conn.getOutputStream()) {
			out.write(body.getBytes(StandardCharsets.UTF_8));
		}
		return status(conn);
	}

	private int get(String path) throws Exception {
		HttpURLConnection conn = open(path);
		conn.setRequestMethod("GET");
		return status(conn);
	}

	private HttpURLConnection open(String path) throws Exception {
		return (HttpURLConnection) new URL("http://127.0.0.1:" + server.getAddress().getPort() + path).openConnection();
	}

	private static int status(HttpURLConnection conn) throws Exception {
		int code = conn.getResponseCode();
		InputStream body = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
		String text = body == null ? "" : new String(body.readAllBytes(), StandardCharsets.UTF_8);
		if (code >= 400) assertTrue(text.contains("\"status\":\"error\""), text);
		return code;
	}
}
