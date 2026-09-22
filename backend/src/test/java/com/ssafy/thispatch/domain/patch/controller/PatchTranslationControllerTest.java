package com.ssafy.thispatch.domain.patch.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
import com.ssafy.thispatch.domain.patch.repository.PatchReadRepository;
import com.ssafy.thispatch.domain.patch.repository.PatchReadRepository.TranslationSource;
import com.ssafy.thispatch.domain.patch.service.PatchTranslationService;
import com.ssafy.thispatch.global.config.JwtConfig;
import com.ssafy.thispatch.global.config.SecurityConfig;
import com.ssafy.thispatch.global.exception.BusinessException;
import com.ssafy.thispatch.global.exception.GlobalExceptionHandler;
import com.ssafy.thispatch.global.security.SecurityErrorHandler;
import com.ssafy.thispatch.global.security.jwt.JwtTokenProvider;
import com.ssafy.thispatch.support.ActiveMemberWebMvcTest;

@WebMvcTest(PatchTranslationController.class)
@Import({PatchTranslationService.class, SecurityConfig.class, JwtConfig.class,
	SecurityErrorHandler.class, GlobalExceptionHandler.class})
@ActiveProfiles("test")
class PatchTranslationControllerTest extends ActiveMemberWebMvcTest {

	private static final String ID = "00018446744073709551";
	private static final String PATH = "/patches/" + ID + "/translation";
	@Autowired MockMvc mvc;
	@Autowired JwtTokenProvider tokens;
	@MockitoBean PatchReadRepository repository;
	@MockitoBean DeepLClient client;

	@Test
	void translatesStoredTitleAndPlainTextBodyAndPreservesStringId() throws Exception {
		source("Balance update", "[h1]Balance[/h1][list][*][b]Damage[/b] +20% 😀[/list]");
		when(client.translateToKorean("Balance update")).thenReturn("밸런스 업데이트");
		when(client.translateToKorean("Balance\n- Damage +20% 😀")).thenReturn("밸런스\n- 공격력 +20% 😀");
		mvc.perform(get(PATH).header("Authorization", auth()).param("text", "arbitrary-query")
			.contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"arbitrary-title\",\"body\":\"arbitrary-body\"}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.code").value("200"))
			.andExpect(jsonPath("$.message").value("성공했습니다."))
			.andExpect(jsonPath("$.responsedAt").isString()).andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.patchId").isString()).andExpect(jsonPath("$.data.patchId").value(ID))
			.andExpect(jsonPath("$.data.translatedTitle").value("밸런스 업데이트"))
			.andExpect(jsonPath("$.data.translatedBody").value("밸런스\n- 공격력 +20% 😀"))
			.andExpect(jsonPath("$.data.length()").value(3));
		verify(repository).findTranslationSource(ID);
		verify(client).translateToKorean("Balance update");
		verify(client).translateToKorean("Balance\n- Damage +20% 😀");
		verifyNoMoreInteractions(repository, client);
	}

	@Test
	void koreanTextStillUsesDeepLWithoutLocalLanguageGuessing() throws Exception {
		source("한국어 제목", "한국어 본문");
		when(client.translateToKorean("한국어 제목")).thenReturn("한국어 제목");
		when(client.translateToKorean("한국어 본문")).thenReturn("한국어 본문");
		mvc.perform(get(PATH).header("Authorization", auth())).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.translatedTitle").value("한국어 제목"))
			.andExpect(jsonPath("$.data.translatedBody").value("한국어 본문"));
		verify(client).translateToKorean("한국어 제목");
		verify(client).translateToKorean("한국어 본문");
	}

	@Test
	void translatesCaptionAndTableWithoutSteamMediaOrHtmlMarkup() throws Exception {
		source("", "[img src='{STEAM_CLAN_IMAGE}/map.jpg']"
			+ "[previewimg=123;sizeFull;map.jpg]New map[/previewimg]"
			+ "<table><tr><td>Damage</td><td>20 &amp; 30</td></tr></table>");
		String rendered = "New map\nDamage\t20 & 30";
		when(client.translateToKorean(rendered)).thenReturn("신규 지도\n피해량\t20 & 30");
		mvc.perform(get(PATH).header("Authorization", auth())).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.translatedBody").value("신규 지도\n피해량\t20 & 30"));
		verify(client).translateToKorean(rendered);
		verifyNoMoreInteractions(client);
	}

	@ParameterizedTest
	@ValueSource(strings = {"", " \t\n", "[b][/b]", "[img]map.jpg[/img]", "<p>&nbsp;</p>"})
	void blankTitleAndRenderedBodyNeedNoTranslation(String contents) throws Exception {
		source(" \t", contents);
		mvc.perform(get(PATH).header("Authorization", auth())).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.translatedTitle").value(" \t"))
			.andExpect(jsonPath("$.data.translatedBody").value(""));
		verifyNoInteractions(client);
	}

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void translatesOnlyNonBlankField(boolean blankTitle) throws Exception {
		source(blankTitle ? "" : "title", blankTitle ? "body" : "");
		when(client.translateToKorean(blankTitle ? "body" : "title")).thenReturn("번역");
		mvc.perform(get(PATH).header("Authorization", auth())).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.translatedTitle").value(blankTitle ? "" : "번역"))
			.andExpect(jsonPath("$.data.translatedBody").value(blankTitle ? "번역" : ""));
		verify(client).translateToKorean(blankTitle ? "body" : "title");
		verifyNoMoreInteractions(client);
	}

	@Test
	void rereadsAndRetranslatesAfterSourceChanges() throws Exception {
		when(repository.findTranslationSource(ID)).thenReturn(Optional.of(new TranslationSource("first", "")))
			.thenReturn(Optional.of(new TranslationSource("second", "")));
		when(client.translateToKorean("first")).thenReturn("처음");
		when(client.translateToKorean("second")).thenReturn("수정됨");
		mvc.perform(get(PATH).header("Authorization", auth())).andExpect(jsonPath("$.data.translatedTitle").value("처음"));
		mvc.perform(get(PATH).header("Authorization", auth())).andExpect(jsonPath("$.data.translatedTitle").value("수정됨"));
		verify(repository, times(2)).findTranslationSource(ID);
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "Bearer invalid-token"})
	void requiresAuthenticationBeforeReadingOrTranslating(String authorization) throws Exception {
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
	void missingPatchReturns404WithoutTranslation() throws Exception {
		mvc.perform(get(PATH).header("Authorization", auth())).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PATCH_NOT_FOUND"))
			.andExpect(jsonPath("$.message").value("패치를 찾을 수 없습니다."))
			.andExpect(jsonPath("$.responsedAt").isString())
			.andExpect(jsonPath("$.data").doesNotExist()).andExpect(jsonPath("$.success").doesNotExist())
			.andExpect(jsonPath("$.errors").doesNotExist());
		verifyNoInteractions(client);
	}

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void eitherTranslationFailureRejectsWholeResponse(boolean titleFails) throws Exception {
		source("title", "body");
		if (!titleFails) when(client.translateToKorean("title")).thenReturn("번역된 제목");
		when(client.translateToKorean(titleFails ? "title" : "body")).thenThrow(new BusinessException(
			TranslationErrorCode.TRANSLATION_UNAVAILABLE, new IllegalStateException("private-api-key")));
		var response = mvc.perform(get(PATH).header("Authorization", auth())).andExpect(status().isBadGateway())
			.andExpect(jsonPath("$.code").value("TRANSLATION_UNAVAILABLE"))
			.andExpect(jsonPath("$.message").value("번역 서비스를 이용할 수 없습니다."))
			.andExpect(jsonPath("$.responsedAt").isString())
			.andExpect(jsonPath("$.data").doesNotExist()).andExpect(jsonPath("$.success").doesNotExist())
			.andExpect(jsonPath("$.errors").doesNotExist()).andReturn();
		assertThat(response.getResponse().getContentAsString()).doesNotContain("private-api-key", "IllegalStateException", "번역된 제목");
		verify(client).translateToKorean("title");
		if (!titleFails) verify(client).translateToKorean("body");
		verifyNoMoreInteractions(client);
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

	private void source(String title, String contents) {
		when(repository.findTranslationSource(ID)).thenReturn(Optional.of(new TranslationSource(title, contents)));
	}

	private String auth() {
		return "Bearer " + tokens.issueAccessToken(1L);
	}
}
