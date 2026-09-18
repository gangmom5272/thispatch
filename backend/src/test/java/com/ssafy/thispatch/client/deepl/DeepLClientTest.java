package com.ssafy.thispatch.client.deepl;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thispatch.global.exception.BusinessException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.web.client.RestClient;

class DeepLClientTest {

	private MockRestServiceServer server;
	private RestClient rest;
	private DeepLClient client;

	@BeforeEach
	void setup() {
		var builder = RestClient.builder().baseUrl("https://api-free.deepl.com");
		server = MockRestServiceServer.bindTo(builder).build();
		rest = builder.build();
		client = new DeepLClient(rest, properties("test-key:fx"), new ObjectMapper());
	}

	@Test
	void sendsStoredTextWithHeaderAuthenticationAndAutomaticSourceLanguage() {
		server.expect(requestTo("https://api-free.deepl.com/v2/translate"))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header("Authorization", "DeepL-Auth-Key test-key:fx"))
			.andExpect(content().contentType(MediaType.APPLICATION_JSON))
			.andExpect(content().json("{\"text\":[\"Hello \\\"world\\\"\\n😀\"],\"target_lang\":\"KO\"}", JsonCompareMode.STRICT))
			.andRespond(withSuccess("{\"translations\":[{\"detected_source_language\":\"EN\",\"text\":\"안녕하세요\\n😀\"}]}", MediaType.APPLICATION_JSON));
		assertThat(client.translateToKorean("Hello \"world\"\n😀")).isEqualTo("안녕하세요\n😀");
		server.verify();
	}

	@ParameterizedTest
	@ValueSource(ints = {302, 400, 403, 413, 429, 456, 500, 503})
	void upstreamErrorsBecome502WithoutRetryOrSensitiveBody(int status) {
		server.expect(requestTo("https://api-free.deepl.com/v2/translate"))
			.andRespond(withStatus(HttpStatusCode.valueOf(status)).body("private-key-and-text"));
		assertUnavailable();
		server.verify();
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "invalid-json", "{}", "{\"translations\":null}",
		"{\"translations\":[]}", "{\"translations\":[null]}", "{\"translations\":[{}]}",
		"{\"translations\":[{\"text\":\" \"}]}", "{\"translations\":[{\"text\":\"a\"},{\"text\":\"b\"}]}"})
	void malformedResponsesBecome502(String response) {
		server.expect(requestTo("https://api-free.deepl.com/v2/translate"))
			.andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
		assertUnavailable();
		server.verify();
	}

	@Test
	void timeoutBecomes502WithoutRetry() {
		server.expect(requestTo("https://api-free.deepl.com/v2/translate"))
			.andRespond(withException(new SocketTimeoutException("private-key-and-text")));
		assertUnavailable();
		server.verify();
	}

	@Test
	void missingKeyMakesNoRequest() {
		client = new DeepLClient(rest, properties("  "), new ObjectMapper());
		assertUnavailable();
		server.verify();
	}

	@Test
	void requestLimitUsesActualUtf8JsonBytesIncludingEscapes() throws Exception {
		// Jackson의 보조 평면 문자 이스케이프까지 포함해 실제 전송 바이트로 경계를 만든다.
		String prefix = "한😀\n\"";
		int prefixBytes = new ObjectMapper().writeValueAsBytes(Map.of("text", List.of(prefix), "target_lang", "KO")).length;
		String boundary = prefix + "a".repeat(128 * 1024 - prefixBytes);
		server.expect(requestTo("https://api-free.deepl.com/v2/translate"))
			.andExpect(request -> assertThat(((org.springframework.mock.http.client.MockClientHttpRequest) request)
				.getBodyAsBytes()).hasSize(128 * 1024))
			.andRespond(withSuccess("{\"translations\":[{\"text\":\"번역\"}]}", MediaType.APPLICATION_JSON));
		assertThat(client.translateToKorean(boundary)).isEqualTo("번역");
		assertThatThrownBy(() -> client.translateToKorean(boundary + "x"))
			.isInstanceOf(BusinessException.class).hasMessage("번역 서비스를 이용할 수 없습니다.");
		server.verify();
	}

	@Test
	void keySelectsFreeOrProAndIsRedacted() {
		assertThat(properties(" test-key:fx ").baseUrl()).isEqualTo("https://api-free.deepl.com");
		assertThat(properties("test-pro-key").baseUrl()).isEqualTo("https://api.deepl.com");
		assertThat(properties("test-pro-key").toString()).doesNotContain("test-pro-key");
		assertThat(properties(null).apiKey()).isEmpty();
		assertThat(properties(null).connectTimeout()).isEqualTo(Duration.ofSeconds(3));
		assertThat(properties(null).readTimeout()).isEqualTo(Duration.ofSeconds(10));
	}

	@ParameterizedTest
	@ValueSource(longs = {0, -1})
	void rejectsNonPositiveTimeouts(long millis) {
		assertThatThrownBy(() -> new DeepLProperties("key", Duration.ofMillis(millis), null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new DeepLProperties("key", null, Duration.ofMillis(millis)))
			.isInstanceOf(IllegalArgumentException.class);
	}

	private void assertUnavailable() {
		assertThatThrownBy(() -> client.translateToKorean("stored source"))
			.isInstanceOfSatisfying(BusinessException.class, error -> {
				assertThat(error.getErrorCode()).isEqualTo(TranslationErrorCode.TRANSLATION_UNAVAILABLE);
				assertThat(error.getErrorCode().getStatus().value()).isEqualTo(502);
				assertThat(error.getCause().getMessage()).doesNotContain("private-key-and-text", "test-key:fx");
			});
	}

	private static DeepLProperties properties(String key) {
		return new DeepLProperties(key, null, null);
	}
}
