package com.ssafy.thispatch.domain.game.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thispatch.common.TimeRule;
import com.ssafy.thispatch.domain.game.repository.GameDetailRepository;
import com.ssafy.thispatch.domain.game.repository.GameDetailRepository.GameDetail;
import com.ssafy.thispatch.domain.game.repository.GameDetailRepository.GameTag;
import com.ssafy.thispatch.domain.game.service.GameDetailService;
import com.ssafy.thispatch.global.config.JwtConfig;
import com.ssafy.thispatch.global.config.JwtProperties;
import com.ssafy.thispatch.global.config.SecurityConfig;
import com.ssafy.thispatch.global.exception.GlobalExceptionHandler;
import com.ssafy.thispatch.global.security.SecurityErrorHandler;
import com.ssafy.thispatch.global.security.jwt.JwtTokenProvider;
import com.ssafy.thispatch.support.ActiveMemberWebMvcTest;

@WebMvcTest(GameDetailController.class)
@Import({GameDetailService.class, SecurityConfig.class, JwtConfig.class,
	SecurityErrorHandler.class, GlobalExceptionHandler.class})
@ActiveProfiles("test")
class GameDetailControllerTest extends ActiveMemberWebMvcTest {

	private static final long MEMBER_ID = 3_000_000_000L;
	private static final long GAME_ID = 4_000_000_000L;
	private static final String PATH = "/games/{gameId}";

	@Autowired private MockMvc mvc;
	@Autowired private ObjectMapper mapper;
	@Autowired private JwtTokenProvider tokens;
	@Autowired private JwtProperties properties;
	@MockitoBean private GameDetailRepository repository;

	@Test
	void returnsExactContractWithLongIdKoreanDateUtcInstantAndCapsuleSubpath() throws Exception {
		when(repository.findByGameId(MEMBER_ID, GAME_ID)).thenReturn(Optional.of(new GameDetail(
			GAME_ID, "hash/capsule_616x353.jpg", "게임 제목", "게임 설명",
			Instant.parse("2026-09-08T15:30:00Z"), 1234, 70, Instant.parse("2026-09-09T08:00:00Z"),
			true, List.of(new GameTag(7, "액션"), new GameTag(2, "RPG")))));
		Instant before = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		var result = request(Long.toString(GAME_ID)).andExpect(status().isOk())
			.andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE)).andReturn();
		var body = mapper.readTree(result.getResponse().getContentAsByteArray());
		assertThat(body.size()).isEqualTo(5);
		assertThat(body.get("code").asText()).isEqualTo("200");
		assertThat(body.get("message").asText()).isEqualTo("성공했습니다.");
		assertThat(body.get("success").asBoolean()).isTrue();
		assertThat(body.get("data")).isEqualTo(mapper.readTree("""
			{"id":4000000000,"capsuleImageUrl":"https://shared.fastly.steamstatic.com/store_item_assets/steam/apps/4000000000/hash/capsule_616x353.jpg",
			 "title":"게임 제목","tags":[{"id":7,"name":"액션"},{"id":2,"name":"RPG"}],
			 "positiveRate":70,"isMine":true,"description":"게임 설명","releasedOn":"2026-09-09",
			 "reviewCount":1234,"lastCollectedAt":"2026-09-09T08:00:00Z"}
			"""));
		Instant respondedAt = LocalDateTime.parse(body.get("responsedAt").asText(),
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).atZone(TimeRule.ZONE).toInstant();
		assertThat(respondedAt).isBetween(before, Instant.now());
		assertThat(result.getRequest().getSession(false)).isNull();
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {" "})
	void keepsNullFieldsPresentAndReturnsEmptyTagsWithoutFallbackImage(String capsulePath) throws Exception {
		when(repository.findByGameId(MEMBER_ID, GAME_ID)).thenReturn(Optional.of(new GameDetail(
			GAME_ID, capsulePath, "게임", null, null, null, null, null, false, List.of())));
		var result = request(Long.toString(GAME_ID)).andExpect(status().isOk()).andReturn();
		var data = mapper.readTree(result.getResponse().getContentAsByteArray()).get("data");
		assertThat(data.size()).isEqualTo(10);
		for (String field : List.of("capsuleImageUrl", "description", "releasedOn", "reviewCount",
			"positiveRate", "lastCollectedAt")) {
			assertThat(data.has(field)).as(field).isTrue();
			assertThat(data.get(field).isNull()).as(field).isTrue();
		}
		assertThat(data.get("tags").isArray()).isTrue();
		assertThat(data.get("tags").size()).isZero();
		assertThat(data.get("isMine").asBoolean()).isFalse();
	}

	@Test
	void usesAuthenticatedMemberEvenWhenQuerySuppliesAnotherMember() throws Exception {
		when(repository.findByGameId(MEMBER_ID, GAME_ID)).thenReturn(Optional.of(new GameDetail(
			GAME_ID, null, "게임", null, null, 0, 0, null, false, List.of())));
		mvc.perform(get(PATH, GAME_ID).queryParam("memberId", "99")
			.header(HttpHeaders.AUTHORIZATION, authorization()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.reviewCount").value(0))
			.andExpect(jsonPath("$.data.positiveRate").value(0)).andExpect(jsonPath("$.data.isMine").value(false));
		verify(repository).findByGameId(MEMBER_ID, GAME_ID);
	}

	@ParameterizedTest
	@ValueSource(longs = {0, -1, 4_000_000_000L, Long.MAX_VALUE})
	void missingGameReturns404(long id) throws Exception {
		assertError(request(Long.toString(id)), 404, "GAME_NOT_FOUND", "게임을 찾을 수 없습니다.");
	}

	@ParameterizedTest
	@ValueSource(strings = {"private-invalid-id", "9223372036854775808", "1.5"})
	void invalidLongReturns400WithoutLookup(String id) throws Exception {
		assertError(request(id), 400, "INVALID_REQUEST", "올바르지 않은 요청입니다.");
		verifyNoInteractions(repository);
	}

	@Test
	void missingInvalidExpiredRefreshAndDuplicateTokensAreRejectedBeforeLookup() throws Exception {
		assertUnauthorized(mvc.perform(get(PATH, GAME_ID)));
		var past = new JwtTokenProvider(properties, Clock.offset(Clock.systemUTC(), Duration.ofDays(-1)));
		for (String token : List.of("private-invalid-token", past.issueAccessToken(MEMBER_ID),
			tokens.issueRefreshToken(MEMBER_ID))) {
			assertUnauthorized(mvc.perform(get(PATH, GAME_ID)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)));
		}
		assertUnauthorized(mvc.perform(get(PATH, GAME_ID)
			.header(HttpHeaders.AUTHORIZATION, authorization(), authorization())));
		verifyNoInteractions(repository);
	}

	@Test
	void inactiveOrMissingMemberIsRejectedBeforeLookup() throws Exception {
		when(memberAccessService.isActive(MEMBER_ID)).thenReturn(false);
		assertUnauthorized(request(Long.toString(GAME_ID)));
		verifyNoInteractions(repository);
	}

	@Test
	void authenticationDatabaseFailureIs500WithoutGameLookup() throws Exception {
		when(memberAccessService.isActive(MEMBER_ID))
			.thenThrow(new DataAccessResourceFailureException("private-auth-db-detail"));
		assertError(request(Long.toString(GAME_ID)), 500, "INTERNAL_SERVER_ERROR", "서버 내부 오류가 발생했습니다.");
		verifyNoInteractions(repository);
	}

	@Test
	void gameDatabaseFailureAndUnexpectedErrorAreSafe500Responses() throws Exception {
		for (RuntimeException failure : List.of(new DataAccessResourceFailureException("private-db-detail"),
			new IllegalStateException("private-code-detail"))) {
			when(repository.findByGameId(MEMBER_ID, GAME_ID)).thenThrow(failure);
			assertError(request(Long.toString(GAME_ID)), 500, "INTERNAL_SERVER_ERROR", "서버 내부 오류가 발생했습니다.");
			org.mockito.Mockito.reset(repository);
		}
	}

	private String authorization() {
		return "Bearer " + tokens.issueAccessToken(MEMBER_ID);
	}

	private ResultActions request(String gameId) throws Exception {
		return mvc.perform(get(PATH, gameId).header(HttpHeaders.AUTHORIZATION, authorization()));
	}

	private void assertUnauthorized(ResultActions result) throws Exception {
		assertError(result, 401, "UNAUTHORIZED", "인증이 필요합니다.")
			.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
	}

	private ResultActions assertError(ResultActions result, int expectedStatus, String code, String message)
		throws Exception {
		result.andExpect(status().is(expectedStatus)).andExpect(jsonPath("$.code").value(code))
			.andExpect(jsonPath("$.message").value(message));
		var body = mapper.readTree(result.andReturn().getResponse().getContentAsByteArray());
		assertThat(body.size()).isEqualTo(3);
		assertThat(body.has("data")).isFalse();
		assertThat(body.has("success")).isFalse();
		assertThat(body.has("errors")).isFalse();
		assertThat(body.path("responsedAt").asText()).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
		assertThat(body.toString()).doesNotContain("private-", "Exception", "rejectedValue", "java.lang");
		return result;
	}
}
