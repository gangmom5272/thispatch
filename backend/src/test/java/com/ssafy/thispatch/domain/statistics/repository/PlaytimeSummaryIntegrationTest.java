package com.ssafy.thispatch.domain.statistics.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.thispatch.client.ai.AiReviewClient;
import com.ssafy.thispatch.client.ai.AiReviewClient.Review;
import com.ssafy.thispatch.client.ai.AiReviewClient.Summary;
import com.ssafy.thispatch.client.ai.AiReviewClient.SummaryRequest;
import com.ssafy.thispatch.domain.review.repository.ReviewReadRepository.ReviewRow;
import com.ssafy.thispatch.domain.statistics.repository.PlaytimeAnalysisRepository.BandCount;
import com.ssafy.thispatch.domain.statistics.service.ReviewPeriod;
import com.ssafy.thispatch.domain.statistics.service.ReviewSummaryService;
import com.ssafy.thispatch.domain.statistics.service.SummaryReviewReader;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PlaytimeSummaryIntegrationTest {

	private static final ReviewPeriod PERIOD = new ReviewPeriod(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 14));
	private static final int[] BOUNDARIES = {0, 90, 480, 2100};
	@Autowired JdbcTemplate jdbc;
	@Autowired SummaryReviewReader reader;
	@Autowired ReviewSummaryService service;
	@Autowired PlaytimeAnalysisRepository statistics;
	@MockitoBean AiReviewClient ai;
	private long gameId;
	private long recommendationId;

	@BeforeEach
	void setUp() {
		assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo("thispatch_test");
		do {
			gameId = ThreadLocalRandom.current().nextLong(4_000_000_000L, 8_000_000_000L);
		} while (jdbc.queryForObject("select count(*) from game where appid = ?", Integer.class, gameId) != 0);
		jdbc.update("insert into game (appid, name, collected_at) values (?, 'summary fixture', now())", gameId);
		for (int i = 0; i < 4; i++) {
			jdbc.update("""
				insert into band_stat (appid, band_no, playtime_from, playtime_to, review_count, positive_count)
				values (?, ?, ?, ?, 1000, 500)
				""", gameId, i + 1, BOUNDARIES[i], i == 3 ? null : BOUNDARIES[i + 1]);
		}
		when(ai.summarize(any())).thenAnswer(call -> {
			SummaryRequest request = call.getArgument(0);
			return new Summary(request.appid(), request.scopeType(), request.scopeKey(), "제목", "요약",
				List.of("전투"), List.of(request.reviews().get(0).reviewId()), request.reviews().size(),
				"test", 1, true, 1);
		});
	}

	@ParameterizedTest
	@ValueSource(ints = {0, 29, 30, 39, 40, 45})
	void countsOnlyValidReviewsBeforeApplyingMinimumAndFortyReviewLimit(int validCount) {
		List<Long> validIds = validReviews(validCount, 10);
		// 공백이 상위 40건을 점유해도 그 다음 유효 리뷰를 선택해야 한다.
		for (int i = 0; i < 41; i++) {
			review(" \t\n", 10_000, 10);
		}
		var summary = service.playtime(gameId, PERIOD.startDate(), null).summary();
		assertThat(summary.targetReviewCount()).isEqualTo(validCount);
		if (validCount < 30) {
			assertThat(summary.status()).isEqualTo("SKIPPED");
			assertThat(summary.reasonCode()).isEqualTo("INSUFFICIENT_SAMPLE");
			assertThat(summary.text()).isNull();
			assertThat(summary.usedReviewCount()).isNull();
			assertThat(summary.selection()).isNull();
			verifyNoInteractions(ai);
		} else {
			assertThat(summary.status()).isEqualTo("COMPLETED");
			assertThat(summary.usedReviewCount()).isEqualTo(Math.min(validCount, 40));
			assertThat(summary.selection().limit()).isEqualTo(40);
			SummaryRequest request = capturedRequest();
			assertThat(request.scopeType()).isEqualTo("ALL");
			assertThat(request.scopeKey()).isEmpty();
			assertThat(request.reviews()).extracting(Review::reviewId)
				.containsExactlyElementsOf(topForty(validIds));
			assertThat(request.reviews()).allSatisfy(review -> assertThat(review.reviewText()).isNotBlank());
		}
		assertThat(statistics.findBandCounts(gameId, PERIOD).get(0).reviewCount()).isEqualTo(validCount + 41);
	}

	@ParameterizedTest
	@ValueSource(ints = {1, 2, 3, 4})
	void fillsEachBandWithFortyValidReviewsInHelpfulAndIdOrder(int bandNo) {
		int playtime = BOUNDARIES[bandNo - 1];
		List<Long> validIds = validReviews(45, playtime);
		review(" ", 10_000, playtime);
		review("다른 구간", 20_000, BOUNDARIES[bandNo % 4]);
		var result = service.playtime(gameId, PERIOD.startDate(), bandNo);
		assertThat(result.selectedBand()).isEqualTo("B" + bandNo);
		assertThat(result.summary().status()).isEqualTo("COMPLETED");
		assertThat(result.summary().targetReviewCount()).isEqualTo(45);
		assertThat(result.summary().usedReviewCount()).isEqualTo(40);
		SummaryRequest request = capturedRequest();
		assertThat(request.scopeType()).isEqualTo("BAND");
		assertThat(request.scopeKey()).isEqualTo(Integer.toString(bandNo));
		assertThat(request.reviews()).extracting(Review::reviewId).containsExactlyElementsOf(topForty(validIds));
	}

	@Test
	void excludesJavaBlankCharactersWithoutTrimmingOrDiscardingNonblankOriginals() {
		for (String blank : List.of("", " ", "\t\n\u000b\f\r", "\u001c\u001d\u001e\u001f",
			"\u1680", "\u2000\u2001\u2002\u2003\u2004\u2005\u2006", "\u2008\u2009\u200a",
			"\u2028\u2029", "\u205f", "\u3000", " \t\u3000\n")) {
			assertThat(blank.isBlank()).isTrue();
			review(blank, 1000, 10);
		}
		String original = " \t[quote]리뷰😀[/quote]\n\u3000";
		long textId = review(original, 2, 10);
		// NBSP, narrow NBSP, figure space, zero-width space는 Java isBlank 공백이 아니다.
		String nonJavaBlank = "\u00a0\u202f\u2007\u200b";
		long nonBlankId = review(nonJavaBlank, 1, 10);
		var input = reader.read(gameId, PERIOD, null, null, 40);
		assertThat(input.targetCount()).isEqualTo(2);
		assertThat(input.reviews()).extracting(ReviewRow::id).containsExactly(textId, nonBlankId);
		assertThat(input.reviews()).extracting(ReviewRow::body).containsExactly(original, nonJavaBlank);
	}

	@Test
	void choosesLatestVersionBeforeBlankPeriodAndBandFilters() {
		Instant start = PERIOD.startInclusive();
		insert(++recommendationId, "이전 본문", 100, 10, start);
		insert(recommendationId, " ", 100, 10, start.plusSeconds(1));
		insert(++recommendationId, "동일 시각의 이전 본문", 100, 10, start);
		insert(recommendationId, "\t", 100, 10, start);
		insert(++recommendationId, "이전 본문", 100, 10, start);
		insert(recommendationId, "기간 밖 최신", 100, 10, PERIOD.endExclusive());
		insert(++recommendationId, "이전 구간", 100, 10, start);
		long movedId = insert(recommendationId, "최신 구간", 100, 90, start.plusSeconds(1));
		insert(++recommendationId, " ", 100, 10, start);
		long restoredId = insert(recommendationId, "최신 유효 본문", 100, 10, start.plusSeconds(1));
		var band = reader.read(gameId, PERIOD, 1, null, 40);
		assertThat(band.targetCount()).isEqualTo(1);
		assertThat(band.reviews()).extracting(ReviewRow::id).containsExactly(restoredId);
		var all = reader.read(gameId, PERIOD, null, null, 40);
		assertThat(all.targetCount()).isEqualTo(2);
		assertThat(all.reviews()).extracting(ReviewRow::id).containsExactly(restoredId, movedId);
	}

	@Test
	void preservesGamePeriodAndPlaytimeBoundaries() {
		Instant start = PERIOD.startInclusive();
		List<Long> expected = new ArrayList<>();
		for (int playtime : new int[] {0, 89, 90, 479, 480, 2099, 2100}) {
			expected.add(insert(++recommendationId, "리뷰", 1, playtime, start));
		}
		expected.add(insert(++recommendationId, "마지막 시각", 1, 10, PERIOD.endExclusive().minusSeconds(1)));
		insert(++recommendationId, "시작 전", 100, 10, start.minusSeconds(1));
		insert(++recommendationId, "종료 후", 100, 10, PERIOD.endExclusive());
		insert(++recommendationId, "미지정", 100, null, start);
		insert(++recommendationId, "음수", 100, -1, start);
		// 다른 게임의 동일 recommendationid·최신 시각은 현재 게임 버전 선택에 영향을 주지 않는다.
		long otherGame = gameId + 8_000_000_000L;
		jdbc.update("insert into game (appid, name, collected_at) values (?, 'other fixture', now())", otherGame);
		jdbc.update("""
			insert into recent_review (recommendationid, appid, review_text, voted_up, votes_up,
			    playtime_at_review, language_code, created_ts, updated_ts)
			values (1, ?, 'other game', true, 1000, 10, 'english', ?, ?)
			""", otherGame, Timestamp.from(start), Timestamp.from(start.plusSeconds(1)));
		var input = reader.read(gameId, PERIOD, null, null, 40);
		assertThat(input.targetCount()).isEqualTo(8);
		assertThat(input.reviews()).extracting(ReviewRow::id).containsExactlyElementsOf(topForty(expected));
		for (int band = 1; band <= 4; band++) {
			assertThat(reader.read(gameId, PERIOD, band, null, 40).targetCount())
				.isEqualTo(new long[] {3, 2, 2, 1}[band - 1]);
		}
	}

	@Test
	void leavesStoredTextLanguageSelectionAndStatisticsCountsUnchanged() {
		validReviews(45, 10);
		long blankId = review(" ", 1000, 10);
		for (int limit : new int[] {4, 20}) {
			var language = reader.read(gameId, PERIOD, null, "english", limit);
			assertThat(language.targetCount()).isEqualTo(46);
			assertThat(language.reviews()).hasSize(limit);
			assertThat(language.reviews().get(0).id()).isEqualTo(blankId);
			assertThat(language.reviews().get(0).body()).isEqualTo(" ");
		}
		assertThat(reader.read(gameId, PERIOD, null, null, 40).targetCount()).isEqualTo(45);
		assertThat(jdbc.queryForObject("select review_text from recent_review where review_id = ?", String.class, blankId))
			.isEqualTo(" ");
		assertThat(statistics.findBandCounts(gameId, PERIOD)).extracting(BandCount::reviewCount)
			.containsExactly(46L, 0L, 0L, 0L);
		assertThat(statistics.findBandCounts(gameId, PERIOD)).extracting(BandCount::allTimeCount)
			.containsExactly(1000L, 1000L, 1000L, 1000L);
		verifyNoInteractions(ai);
	}

	private SummaryRequest capturedRequest() {
		var captor = ArgumentCaptor.forClass(SummaryRequest.class);
		verify(ai).summarize(captor.capture());
		return captor.getValue();
	}

	private List<Long> validReviews(int count, int playtime) {
		List<Long> ids = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			ids.add(review("유효 리뷰 " + i, i / 2, playtime)); // 도움됨 동률의 ID 내림차순도 검증
		}
		return ids;
	}

	private static List<Long> topForty(List<Long> ids) {
		return ids.stream().sorted(Comparator.reverseOrder()).limit(40).toList();
	}

	private long review(String body, int votes, int playtime) {
		return insert(++recommendationId, body, votes, playtime, PERIOD.startInclusive());
	}

	private long insert(long recommendation, String body, int votes, Integer playtime, Instant updatedAt) {
		return jdbc.queryForObject("""
			insert into recent_review (recommendationid, appid, review_text, voted_up, votes_up,
			    playtime_at_review, language_code, created_ts, updated_ts)
			values (?, ?, ?, true, ?, ?, 'english', ?, ?) returning review_id
			""", Long.class, recommendation, gameId, body, votes, playtime,
			Timestamp.from(PERIOD.startInclusive().minusSeconds(60)), Timestamp.from(updatedAt));
	}
}
