package com.ssafy.thispatch.client.deepl;

import java.net.http.HttpClient;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thispatch.global.exception.BusinessException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class DeepLClient {

	private static final int MAX_REQUEST_BYTES = 128 * 1024;
	private final RestClient client;
	private final DeepLProperties properties;
	private final ObjectMapper mapper;

	@Autowired
	public DeepLClient(DeepLProperties properties, ObjectMapper mapper) {
		this(createClient(properties), properties, mapper);
	}

	DeepLClient(RestClient client, DeepLProperties properties, ObjectMapper mapper) {
		this.client = client;
		this.properties = properties;
		this.mapper = mapper;
	}

	public String translateToKorean(String text) {
		if (properties.apiKey().isBlank()) {
			throw unavailable("DeepL API key is not configured");
		}
		byte[] body;
		try {
			body = mapper.writeValueAsBytes(new Request(List.of(text), "KO"));
		} catch (JsonProcessingException exception) {
			throw new IllegalStateException("Could not serialize DeepL request", exception);
		}
		if (body.length > MAX_REQUEST_BYTES) {
			throw unavailable("DeepL request exceeds 128 KiB");
		}
		try {
			Result result = client.post().uri("/v2/translate")
				.header(HttpHeaders.AUTHORIZATION, "DeepL-Auth-Key " + properties.apiKey())
				.contentType(MediaType.APPLICATION_JSON).body(body).retrieve()
				.onStatus(status -> !status.is2xxSuccessful(), (request, response) -> {
					throw unavailable("DeepL returned HTTP " + response.getStatusCode().value());
				})
				.body(Result.class);
			if (result == null || result.translations() == null || result.translations().size() != 1
				|| result.translations().get(0) == null || result.translations().get(0).text() == null
				|| result.translations().get(0).text().isBlank()) {
				throw unavailable("Invalid DeepL translation response");
			}
			return result.translations().get(0).text();
		} catch (RestClientException exception) {
			// 외부 응답 본문·요청 헤더가 예외 로그를 통해 노출되지 않도록 원인 종류만 남긴다.
			throw unavailable("DeepL transport or response failure: " + exception.getClass().getSimpleName());
		}
	}

	private static BusinessException unavailable(String reason) {
		return new BusinessException(TranslationErrorCode.TRANSLATION_UNAVAILABLE, new IllegalStateException(reason));
	}

	private static RestClient createClient(DeepLProperties properties) {
		var http = HttpClient.newBuilder().connectTimeout(properties.connectTimeout())
			.followRedirects(HttpClient.Redirect.NEVER).build();
		var requests = new JdkClientHttpRequestFactory(http);
		requests.setReadTimeout(properties.readTimeout());
		return RestClient.builder().baseUrl(properties.baseUrl()).requestFactory(requests).build();
	}

	private record Request(List<String> text, @JsonProperty("target_lang") String targetLanguage) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record Result(List<Translation> translations) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record Translation(String text) {
	}
}
