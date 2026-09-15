package com.ssafy.thispatch.global.config;

import java.net.URI;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(URI frontendBaseUrl, URI backendPublicUrl, Cors cors) {

	public AppProperties {
		validateHttpUrl(frontendBaseUrl, "app.frontend-base-url", false);
		validateHttpUrl(backendPublicUrl, "app.backend-public-url", false);
		if (cors == null) {
			throw new IllegalArgumentException("app.cors.allowed-origins must be configured");
		}
	}

	public record Cors(List<URI> allowedOrigins) {

		public Cors {
			if (allowedOrigins == null || allowedOrigins.isEmpty()) {
				throw new IllegalArgumentException("app.cors.allowed-origins must not be empty");
			}
			for (URI origin : allowedOrigins) {
				validateHttpUrl(origin, "app.cors.allowed-origins", true);
			}
			allowedOrigins = List.copyOf(allowedOrigins);
		}
	}

	private static void validateHttpUrl(URI uri, String property, boolean originOnly) {
		if (uri == null || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
			|| uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
			|| uri.getFragment() != null || uri.getPort() < -1 || uri.getPort() == 0 || uri.getPort() > 65535) {
			throw new IllegalArgumentException(property + " must be an absolute HTTP(S) URL without credentials, query or fragment");
		}
		if (originOnly && uri.getRawPath() != null && !uri.getRawPath().isEmpty()) {
			throw new IllegalArgumentException(property + " must contain only scheme, host and optional port (no path or trailing slash)");
		}
	}
}
