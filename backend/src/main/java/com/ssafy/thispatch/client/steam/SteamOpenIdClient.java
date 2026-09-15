package com.ssafy.thispatch.client.steam;

import java.math.BigInteger;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.ssafy.thispatch.global.config.AppProperties;

import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class SteamOpenIdClient {

	static final String ENDPOINT = "https://steamcommunity.com/openid/login";
	static final String NAMESPACE = "http://specs.openid.net/auth/2.0";
	private static final Pattern CLAIMED_ID = Pattern.compile(
		"https?://steamcommunity\\.com/openid/id/([1-9][0-9]{0,19})");
	private static final BigInteger MAX_STEAM_ID = new BigInteger("18446744073709551615");
	private static final Set<String> SIGNED_FIELDS = Set.of(
		"op_endpoint", "claimed_id", "identity", "return_to", "response_nonce", "assoc_handle");
	private static final Set<String> REQUIRED_FIELDS = Set.of(
		"ns", "mode", "op_endpoint", "claimed_id", "identity", "return_to", "response_nonce",
		"assoc_handle", "signed", "sig");

	private final RestClient restClient;
	private final String returnTo;

	@Autowired
	public SteamOpenIdClient(AppProperties properties) {
		this(createRestClient(), properties);
	}

	SteamOpenIdClient(RestClient restClient, AppProperties properties) {
		this.restClient = restClient;
		this.returnTo = properties.backendPublicUrl().toASCIIString().replaceFirst("/+$", "")
			+ "/auth/steam/callback";
	}

	public Optional<BigInteger> verify(MultiValueMap<String, String> parameters) {
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		for (var entry : parameters.entrySet()) {
			if (!entry.getKey().startsWith("openid.")) {
				continue;
			}
			if (entry.getValue().size() != 1 || entry.getValue().get(0) == null
				|| entry.getValue().get(0).contains("\r") || entry.getValue().get(0).contains("\n")) {
				return rejected();
			}
			form.add(entry.getKey(), entry.getValue().get(0));
		}
		if (REQUIRED_FIELDS.stream().anyMatch(field -> {
			String value = form.getFirst("openid." + field);
			return value == null || value.isBlank();
		})) {
			return rejected();
		}
		if (!NAMESPACE.equals(form.getFirst("openid.ns"))
			|| !"id_res".equals(form.getFirst("openid.mode"))
			|| !ENDPOINT.equals(form.getFirst("openid.op_endpoint"))
			|| !returnTo.equals(form.getFirst("openid.return_to"))
			|| !form.getFirst("openid.claimed_id").equals(form.getFirst("openid.identity"))
			|| !Arrays.asList(form.getFirst("openid.signed").split(",", -1)).containsAll(SIGNED_FIELDS)
			|| !validNonce(form.getFirst("openid.response_nonce"))) {
			return rejected();
		}
		var claimedId = CLAIMED_ID.matcher(form.getFirst("openid.claimed_id"));
		if (!claimedId.matches()) {
			return rejected();
		}
		BigInteger steamId = new BigInteger(claimedId.group(1));
		if (steamId.compareTo(MAX_STEAM_ID) > 0) {
			return rejected();
		}
		form.set("openid.mode", "check_authentication");
		try {
			// 고정된 Steam HTTPS 주소만 호출한다. 서명과 nonce 재사용 검증은 매번 OP에 위임한다.
			var response = restClient.post().uri(ENDPOINT)
				.contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
				.retrieve().toEntity(String.class);
			if (response.getStatusCode().value() != 200) {
				log.warn("Steam OpenID verification failed: unexpected HTTP status");
				return Optional.empty();
			}
			if (!isValidResponse(response.getBody())) {
				return rejected();
			}
			return Optional.of(steamId);
		} catch (RestClientException exception) {
			// 응답 본문·OpenID 서명이 포함될 수 있는 예외 메시지와 원문은 로깅하지 않는다.
			log.warn("Steam OpenID verification unavailable: {}", exception.getClass().getSimpleName());
			return Optional.empty();
		}
	}

	private Optional<BigInteger> rejected() {
		log.warn("Steam OpenID assertion rejected");
		return Optional.empty();
	}

	private boolean validNonce(String nonce) {
		if (nonce.length() <= 20 || nonce.length() > 255
			|| !nonce.substring(0, 20).matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z")) {
			return false;
		}
		try {
			Instant.parse(nonce.substring(0, 20));
			return true;
		} catch (DateTimeParseException exception) {
			return false;
		}
	}

	private boolean isValidResponse(String body) {
		if (body == null) {
			return false;
		}
		Map<String, String> fields = new HashMap<>();
		for (String line : body.split("\\r?\\n")) {
			int separator = line.indexOf(':');
			if (separator <= 0 || fields.putIfAbsent(line.substring(0, separator),
				line.substring(separator + 1)) != null) {
				return false;
			}
		}
		return NAMESPACE.equals(fields.get("ns")) && "true".equals(fields.get("is_valid"));
	}

	private static RestClient createRestClient() {
		var httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
			.followRedirects(HttpClient.Redirect.NEVER).build();
		var requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(Duration.ofSeconds(5));
		return RestClient.builder().requestFactory(requestFactory).build();
	}
}
