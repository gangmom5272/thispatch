package com.ssafy.thispatch.domain.review.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.ssafy.thispatch.client.deepl.DeepLClient;
import com.ssafy.thispatch.client.deepl.TranslationErrorCode;
import com.ssafy.thispatch.domain.review.repository.ReviewReadRepository;
import com.ssafy.thispatch.domain.review.repository.ReviewReadRepository.TranslationSource;
import com.ssafy.thispatch.domain.review.service.ReviewTranslationService;
import com.ssafy.thispatch.global.config.JwtConfig;
import com.ssafy.thispatch.global.config.SecurityConfig;
import com.ssafy.thispatch.global.exception.BusinessException;
import com.ssafy.thispatch.global.exception.GlobalExceptionHandler;
import com.ssafy.thispatch.global.security.SecurityErrorHandler;
import com.ssafy.thispatch.global.security.jwt.JwtTokenProvider;
import com.ssafy.thispatch.support.ActiveMemberWebMvcTest;

@WebMvcTest(ReviewTranslationController.class)
@Import({ReviewTranslationService.class, SecurityConfig.class, JwtConfig.class,
	SecurityErrorHandler.class, GlobalExceptionHandler.class})
@ActiveProfiles("test")
class ReviewTranslationControllerTest extends ActiveMemberWebMvcTest {

	private static final long ID = 4_000_000_001L;
	private static final String PATH = "/reviews/" + ID + "/translation";
	@Autowired MockMvc mvc;
	@Autowired JwtTokenProvider tokens;
	@MockitoBean ReviewReadRepository repository;
	@MockitoBean DeepLClient client;

	@Test
	void translatesOnlyStoredTextAndPreservesSuccessContract() throws Exception {
		source("Stored source\n😀", "english");
		when(client.translateToKorean("Stored source\n😀")).thenReturn("저장된 원문\n😀");
		mvc.perform(get(PATH).header("Authorization", auth())
			.param("text", "arbitrary-query-text").contentType(MediaType.APPLICATION_JSON)
			.content("{\"text\":\"arbitrary-body-text\"}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.code").value("200"))
			.andExpect(jsonPath("$.message").value("성공했습니다."))
			.andExpect(jsonPath("$.responsedAt").isString())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.reviewId").value(ID))
			.andExpect(jsonPath("$.data.translatedText").value("저장된 원문\n😀"))
			.andExpect(jsonPath("$.data.length()").value(2));
		verify(client).translateToKorean("Stored source\n😀");
		verifyNoMoreInteractions(client);
	}

	@ParameterizedTest
	@CsvSource({"korean, 한국어 원문", "koreana, 한국어 원문"})
	void koreanSourceIsUnchangedWithoutExternalCall(String language, String text) throws Exception {
		source(text, language);
		mvc.perform(get(PATH).header("Authorization", auth())).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.translatedText").value(text));
		verifyNoInteractions(client);
	}

	@ParameterizedTest
	@ValueSource(strings = {"", " \t\n"})
	void blankSourceIsUnchangedWithoutExternalCall(String text) throws Exception {
		source(text, "english");
		mvc.perform(get(PATH).header("Authorization", auth())).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.translatedText").value(text));
		verifyNoInteractions(client);
	}

	@Test
	void noCachedTranslationIsUsedAfterSourceChanges() throws Exception {
		when(repository.findTranslationSource(ID)).thenReturn(Optional.of(new TranslationSource("first", "english")))
			.thenReturn(Optional.of(new TranslationSource("second", "english")));
		when(client.translateToKorean("first")).thenReturn("처음");
		when(client.translateToKorean("second")).thenReturn("수정됨");
		mvc.perform(get(PATH).header("Authorization", auth())).andExpect(jsonPath("$.data.translatedText").value("처음"));
		mvc.perform(get(PATH).header("Authorization", auth())).andExpect(jsonPath("$.data.translatedText").value("수정됨"));
		verify(repository, times(2)).findTranslationSource(ID);
	}

	@Test
	void translatesRenderedBodyInsteadOfMarkupAndMediaPaths() throws Exception {
		source("[h4]Balance[/h4]<p>[b]Damage[/b] &amp; health</p>[img]map.jpg[/img]", "english");
		when(client.translateToKorean("Balance\nDamage & health")).thenReturn("밸런스\n피해량과 체력");
		mvc.perform(get(PATH).header("Authorization", auth())).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.translatedText").value("밸런스\n피해량과 체력"));
		verify(client).translateToKorean("Balance\nDamage & health");
		verifyNoMoreInteractions(client);
	}

	@ParameterizedTest
	@ValueSource(strings = {"korean", "koreana"})
	void koreanMarkupIsRenderedWithoutCallingDeepL(String language) throws Exception {
		source("[b]좋아요[/b]<br>재미있어요[img src='map.jpg']", language);
		mvc.perform(get(PATH).header("Authorization", auth())).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.translatedText").value("좋아요\n재미있어요"));
		verifyNoInteractions(client);
	}

	@ParameterizedTest
	@ValueSource(strings = {"[b][/b]", "[img]{STEAM_CLAN_IMAGE}/map.jpg[/img]", "<p>&nbsp;</p>"})
	void bodyThatBecomesEmptyNeedsNoExternalTranslation(String text) throws Exception {
		source(text, "english");
		mvc.perform(get(PATH).header("Authorization", auth())).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.translatedText").value(""));
		verifyNoInteractions(client);
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "Bearer invalid-token"})
	void authenticationIsRequiredBeforeReadingOrTranslating(String authorization) throws Exception {
		var request = get(PATH);
		if (!authorization.isEmpty()) request.header("Authorization", authorization);
		mvc.perform(request).andExpect(status().isUnauthorized())
			.andExpect(header().string("WWW-Authenticate", "Bearer"))
			.andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.data").doesNotExist()).andExpect(jsonPath("$.success").doesNotExist())
			.andExpect(jsonPath("$.errors").doesNotExist());
		verifyNoInteractions(repository, client);
	}

	@Test
	void missingReviewUses404WithoutCallingDeepL() throws Exception {
		mvc.perform(get(PATH).header("Authorization", auth())).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("REVIEW_NOT_FOUND"))
			.andExpect(jsonPath("$.message").value("리뷰를 찾을 수 없습니다."))
			.andExpect(jsonPath("$.responsedAt").isString())
			.andExpect(jsonPath("$.data").doesNotExist()).andExpect(jsonPath("$.success").doesNotExist())
			.andExpect(jsonPath("$.errors").doesNotExist());
		verifyNoInteractions(client);
	}

	@Test
	void externalFailureUses502WithoutExposingDetails() throws Exception {
		source("source", "english");
		when(client.translateToKorean("source")).thenThrow(new BusinessException(
			TranslationErrorCode.TRANSLATION_UNAVAILABLE, new IllegalStateException("private-api-key")));
		var response = mvc.perform(get(PATH).header("Authorization", auth())).andExpect(status().isBadGateway())
			.andExpect(jsonPath("$.code").value("TRANSLATION_UNAVAILABLE"))
			.andExpect(jsonPath("$.message").value("번역 서비스를 이용할 수 없습니다."))
			.andExpect(jsonPath("$.responsedAt").isString())
			.andExpect(jsonPath("$.data").doesNotExist()).andExpect(jsonPath("$.success").doesNotExist())
			.andExpect(jsonPath("$.errors").doesNotExist()).andReturn();
		assertThat(response.getResponse().getContentAsString()).doesNotContain("private-api-key", "IllegalStateException");
	}

	@Test
	void databaseFailureRemains500() throws Exception {
		when(repository.findTranslationSource(ID)).thenThrow(new DataAccessResourceFailureException("private-db-detail"));
		var response = mvc.perform(get(PATH).header("Authorization", auth())).andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
			.andExpect(jsonPath("$.data").doesNotExist()).andExpect(jsonPath("$.success").doesNotExist())
			.andExpect(jsonPath("$.errors").doesNotExist()).andReturn();
		assertThat(response.getResponse().getContentAsString()).doesNotContain("private-db-detail");
		verifyNoInteractions(client);
	}

	@ParameterizedTest
	@ValueSource(strings = {"invalid", "9223372036854775808"})
	void invalidLongUsesCommon400(String id) throws Exception {
		mvc.perform(get("/reviews/" + id + "/translation").header("Authorization", auth()))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		verifyNoInteractions(repository, client);
	}

	private void source(String text, String language) {
		when(repository.findTranslationSource(ID)).thenReturn(Optional.of(new TranslationSource(text, language)));
	}

	private String auth() {
		return "Bearer " + tokens.issueAccessToken(1L);
	}
}
