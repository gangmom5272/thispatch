package com.ssafy.thispatch.domain.member.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.ssafy.thispatch.global.config.AppProperties;

class SteamLoginServiceTest {

	@ParameterizedTest
	@CsvSource({
		"http://localhost:8080, http://localhost:8080/auth/steam/callback",
		"http://localhost:8080/, http://localhost:8080/auth/steam/callback",
		"https://thispatch.example/api, https://thispatch.example/api/auth/steam/callback",
		"https://thispatch.example/api/, https://thispatch.example/api/auth/steam/callback",
		"https://thispatch.example:8443/nested/api, https://thispatch.example:8443/nested/api/auth/steam/callback",
		"https://thispatch.example/a%20b, https://thispatch.example/a%20b/auth/steam/callback",
		"https://thispatch.example/a+b&c, https://thispatch.example/a+b&c/auth/steam/callback"
	})
	void buildsSteamRequestPreservingConfiguredPublicUrl(String backendUrl, String callbackUrl) {
		var frontend = URI.create("https://frontend.example");
		var properties = new AppProperties(frontend, URI.create(backendUrl),
			new AppProperties.Cors(List.of(frontend)));

		URI location = new SteamLoginService(properties).createLoginUrl();

		assertThat(location.getScheme()).isEqualTo("https");
		assertThat(location.getHost()).isEqualTo("steamcommunity.com");
		assertThat(location.getPath()).isEqualTo("/openid/login");
		assertThat(location.getFragment()).isNull();
		Map<String, String> query = Arrays.stream(location.getRawQuery().split("&"))
			.map(parameter -> parameter.split("=", 2))
			.collect(Collectors.toMap(parameter -> parameter[0],
				parameter -> URLDecoder.decode(parameter[1], StandardCharsets.UTF_8)));
		assertThat(query).containsExactlyInAnyOrderEntriesOf(Map.of(
			"openid.ns", "http://specs.openid.net/auth/2.0",
			"openid.mode", "checkid_setup",
			"openid.claimed_id", "http://specs.openid.net/auth/2.0/identifier_select",
			"openid.identity", "http://specs.openid.net/auth/2.0/identifier_select",
			"openid.return_to", callbackUrl,
			"openid.realm", backendUrl));
	}
}