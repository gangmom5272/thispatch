package com.ssafy.thispatch.client.deepl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.SocketTimeoutException;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thispatch.domain.patch.controller.PatchTranslationController;
import com.ssafy.thispatch.domain.patch.repository.PatchReadRepository;
import com.ssafy.thispatch.domain.patch.repository.PatchReadRepository.TranslationSource;
import com.ssafy.thispatch.domain.patch.service.PatchTranslationService;
import com.ssafy.thispatch.global.exception.GlobalExceptionHandler;

/** 인증은 MVC 테스트에서, 여기서는 실제 공통 클라이언트와 HTTP 오류 연결을 검증한다. */
class PatchTranslationTransportTest {

	private static final String ENDPOINT = "https://api-free.deepl.com/v2/translate";
	private static final String PATH = "/patches/001234/translation";
	private final PatchReadRepository repository = mock(PatchReadRepository.class);
	private MockRestServiceServer server;
	private MockMvc mvc;

	@BeforeEach
	void setup() {
		var builder = RestClient.builder().baseUrl("https://api-free.deepl.com");
		server = MockRestServiceServer.bindTo(builder).build();
		var client = new DeepLClient(builder.build(), new DeepLProperties("test-key:fx", null, null), new ObjectMapper());
		mvc = MockMvcBuilders.standaloneSetup(new PatchTranslationController(new PatchTranslationService(repository, client)))
			.setControllerAdvice(new GlobalExceptionHandler()).build();
	}

	@Test
	void sendsTitleAndRenderedBodyThroughSharedDeepLClient() throws Exception {
		source("Patch", "[b]Fix[/b]");
		server.expect(requestTo(ENDPOINT)).andExpect(header("Authorization", "DeepL-Auth-Key test-key:fx"))
			.andExpect(content().json("{\"text\":[\"Patch\"],\"target_lang\":\"KO\"}"))
			.andRespond(withSuccess("{\"translations\":[{\"text\":\"패치\"}]}", MediaType.APPLICATION_JSON));
		server.expect(requestTo(ENDPOINT))
			.andExpect(content().json("{\"text\":[\"Fix\"],\"target_lang\":\"KO\"}"))
			.andRespond(withSuccess("{\"translations\":[{\"text\":\"수정\"}]}", MediaType.APPLICATION_JSON));
		mvc.perform(get(PATH)).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.translatedTitle").value("패치"))
			.andExpect(jsonPath("$.data.translatedBody").value("수정"));
		server.verify();
	}

	@Test
	void bodyTimeoutAfterTitleSuccessReturns502WithoutPartialDataOrRetry() throws Exception {
		source("Patch", "Fix");
		server.expect(requestTo(ENDPOINT))
			.andRespond(withSuccess("{\"translations\":[{\"text\":\"패치\"}]}", MediaType.APPLICATION_JSON));
		server.expect(requestTo(ENDPOINT)).andRespond(withException(new SocketTimeoutException("private-transport-detail")));
		assertUnavailable();
		server.verify();
	}

	@Test
	void oversizeBodyIsRejectedWithoutTruncationSplittingOrExternalCall() throws Exception {
		source("", "한".repeat(128 * 1024));
		assertUnavailable();
		server.verify();
	}

	@Test
	void unusableBodyResponseRejectsWholeTranslation() throws Exception {
		source("", "Fix");
		server.expect(requestTo(ENDPOINT)).andRespond(withSuccess("{\"translations\":[]}", MediaType.APPLICATION_JSON));
		assertUnavailable();
		server.verify();
	}

	private void source(String title, String body) {
		when(repository.findTranslationSource("001234")).thenReturn(Optional.of(new TranslationSource(title, body)));
	}

	private void assertUnavailable() throws Exception {
		var response = mvc.perform(get(PATH)).andExpect(status().isBadGateway())
			.andExpect(jsonPath("$.code").value("TRANSLATION_UNAVAILABLE"))
			.andExpect(jsonPath("$.message").value("번역 서비스를 이용할 수 없습니다."))
			.andExpect(jsonPath("$.responsedAt").isString())
			.andExpect(jsonPath("$.data").doesNotExist()).andExpect(jsonPath("$.success").doesNotExist())
			.andExpect(jsonPath("$.errors").doesNotExist()).andReturn();
		assertThat(response.getResponse().getContentAsString()).doesNotContain("test-key", "private-transport-detail", "SocketTimeoutException");
	}
}
