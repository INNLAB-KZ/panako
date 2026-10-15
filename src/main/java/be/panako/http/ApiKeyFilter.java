package be.panako.http;

import be.panako.util.Config;
import be.panako.util.Key;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;

/**
 * Wraps an HttpHandler with API key authentication.
 * Checks the X-API-Key header (or api_key query parameter).
 * If API_KEY config is empty, all requests are allowed.
 */
public class ApiKeyFilter implements HttpHandler {

	private final HttpHandler wrapped;

	public ApiKeyFilter(HttpHandler wrapped) {
		this.wrapped = wrapped;
	}

	@Override
	public void handle(HttpExchange exchange) throws IOException {
		String configuredKey = Config.get(Key.API_KEY);

		if (configuredKey == null || configuredKey.isEmpty()) {
			wrapped.handle(exchange);
			return;
		}

		// Check X-API-Key header first
		String headerKey = exchange.getRequestHeaders().getFirst("X-API-Key");
		if (configuredKey.equals(headerKey)) {
			wrapped.handle(exchange);
			return;
		}

		// Fallback: check api_key query parameter
		String query = exchange.getRequestURI().getRawQuery();
		if (query != null) {
			for (String pair : query.split("&")) {
				if (pair.startsWith("api_key=")) {
					String paramKey = pair.substring(8);
					if (configuredKey.equals(paramKey)) {
						wrapped.handle(exchange);
						return;
					}
				}
			}
		}

		HttpUtil.sendError(exchange, 401, "Invalid or missing API key");
	}
}
