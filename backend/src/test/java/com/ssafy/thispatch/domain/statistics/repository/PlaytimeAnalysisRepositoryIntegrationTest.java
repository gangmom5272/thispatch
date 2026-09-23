package com.ssafy.thispatch.domain.statistics.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.thispatch.domain.statistics.repository.PlaytimeAnalysisRepository.BandCount;
import com.ssafy.thispatch.domain.statistics.repository.PlaytimeAnalysisRepository.TopicCount;
import com.ssafy.thispatch.domain.statistics.service.ReviewPeriod;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PlaytimeAnalysisRepositoryIntegrationTest {

	private static final ReviewPeriod PERIOD = new ReviewPeriod(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 14));
	@Autowired JdbcTemplate jdbc;
	@Autowired PlaytimeAnalysisRepository repository;
	private long gameId;
	private long recommendationId;

	@BeforeEach
	void setUp() {
		assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo("thispatch_test");
		do {
			gameId = ThreadLocalRandom.current().nextLong(4_000_000_000L, 8_000_000_000L);
		} while (jdbc.queryForObject("select count(*) from game where appid = ?", Integer.class, gameId) != 0);
		jdbc.update("insert into game (appid, name, collected_at) values (?, 'playtime fixture', now())", gameId);
		int[] boundaries = {0, 90, 480, 2100};
		for (int i = 0; i < 4; i++) {
			jdbc.update("""
				insert into band_stat (appid, band_no, playtime_from, playtime_to, review_count, positive_count)
				values (?, ?, ?, ?, 1000, 500)
				""", gameId, i + 1, boundaries[i], i == 3 ? null : boundaries[i + 1]);
		}
	}

	@Test
	void multipleTopicsCountReviewOnceAndUnclassifiedReviewsDoNotChangeTopicDenominator() {
		long first = review(10, true);
		classify(first, 1, 2);
		classify(review(20, false), 1);
		review(30, true);
		var before = repository.findBandCounts(gameId, PERIOD).get(0);
		assertThat(before.reviewCount()).isEqualTo(3);
		assertThat(before.classifiedReviewCount()).isEqualTo(2);
		assertThat(before.positiveCount()).isEqualTo(2);
		var mentions = repository.findTopicCounts(gameId, PERIOD);
		assertThat(mentions).filteredOn(t -> t.bandNo() == 1 && t.topicId() == 1)
			.extracting(TopicCount::mentionCount).containsExactly(2L);
		assertThat(mentions).filteredOn(t -> t.bandNo() == 1 && t.topicId() == 2)
			.extracting(TopicCount::mentionCount).containsExactly(1L);
		review(40, false);
		var after = repository.findBandCounts(gameId, PERIOD).get(0);
		assertThat(after.reviewCount()).isEqualTo(4);
		assertThat(after.classifiedReviewCount()).isEqualTo(before.classifiedReviewCount());
		assertThat(after.positiveCount()).isEqualTo(before.positiveCount());
		assertThat(repository.findTopicCounts(gameId, PERIOD)).isEqualTo(mentions);
	}

	@Test
	void usesLatestReviewBeforeFilteringPeriodAndUsesReviewIdToBreakTimestampTies() {
		Instant start = PERIOD.startInclusive();
		classify(insert(++recommendationId, 10, true, start), 1);
		insert(recommendationId, 90, true, start.plusSeconds(1)); // 최신 버전은 미분류
		insert(++recommendationId, 10, true, start);
		classify(insert(recommendationId, 480, true, start.plusSeconds(1)), 2);
		classify(insert(++recommendationId, 10, true, start), 1);
		classify(insert(recommendationId, 10, true, PERIOD.endExclusive()), 1); // 최신이 기간 밖이면 제외
		classify(insert(++recommendationId, 2100, true, start), 1);
		insert(recommendationId, 2100, true, start); // 같은 시각의 높은 ID가 최신
		var bands = repository.findBandCounts(gameId, PERIOD);
		assertThat(bands).extracting(BandCount::reviewCount).containsExactly(0L, 1L, 1L, 1L);
		assertThat(bands).extracting(BandCount::classifiedReviewCount).containsExactly(0L, 0L, 1L, 0L);
		assertThat(repository.findTopicCounts(gameId, PERIOD)).filteredOn(t -> t.mentionCount() != 0)
			.extracting(TopicCount::bandNo, TopicCount::topicId, TopicCount::mentionCount)
			.containsExactly(org.assertj.core.groups.Tuple.tuple(3, 2, 1L));
	}

	@Test
	void appliesSameGamePeriodAndPlaytimeBoundariesToClassifiedAndAllReviews() {
		Instant start = PERIOD.startInclusive();
		for (int playtime : new int[] {0, 90, 480, 2100}) {
			classify(insert(++recommendationId, playtime, true, start), 1);
		}
		classify(insert(++recommendationId, 89, false, PERIOD.endExclusive().minusSeconds(1)), 2);
		classify(insert(++recommendationId, 10, true, start.minusSeconds(1)), 1);
		classify(insert(++recommendationId, 10, true, PERIOD.endExclusive()), 1);
		classify(insert(++recommendationId, null, true, start), 1);
		classify(insert(++recommendationId, -1, true, start), 1);
		var bands = repository.findBandCounts(gameId, PERIOD);
		assertThat(bands).extracting(BandCount::reviewCount).containsExactly(2L, 1L, 1L, 1L);
		assertThat(bands).extracting(BandCount::classifiedReviewCount).containsExactly(2L, 1L, 1L, 1L);
		assertThat(bands).extracting(BandCount::positiveCount).containsExactly(1L, 1L, 1L, 1L);
		assertThat(bands).extracting(BandCount::allTimeCount).containsExactly(1000L, 1000L, 1000L, 1000L);
		assertThat(repository.findTopicCounts(gameId, PERIOD).stream().mapToLong(TopicCount::mentionCount).sum())
			.isEqualTo(5);
		assertThat(repository.findBandCounts(-1, PERIOD)).isEmpty();
		assertThat(repository.findTopicCounts(-1, PERIOD)).isEmpty();
	}

	@Test
	void emptyAndUnclassifiedBandsHaveZeroClassifiedCountAndCollapsedBoundariesDoNotDuplicateReviews() {
		assertThat(repository.findBandCounts(gameId, PERIOD)).extracting(BandCount::classifiedReviewCount)
			.containsExactly(0L, 0L, 0L, 0L);
		review(10, true);
		assertThat(repository.findBandCounts(gameId, PERIOD)).extracting(BandCount::reviewCount)
			.containsExactly(1L, 0L, 0L, 0L);
		assertThat(repository.findBandCounts(gameId, PERIOD)).extracting(BandCount::classifiedReviewCount)
			.containsExactly(0L, 0L, 0L, 0L);
		jdbc.update("""
			update band_stat set playtime_from = case when band_no = 1 then 0 else 100 end,
			playtime_to = case when band_no = 4 then null else 100 end where appid = ?
			""", gameId);
		classify(review(100, false), 1, 2);
		var bands = repository.findBandCounts(gameId, PERIOD);
		assertThat(bands).extracting(BandCount::reviewCount).containsExactly(1L, 0L, 0L, 1L);
		assertThat(bands).extracting(BandCount::classifiedReviewCount).containsExactly(0L, 0L, 0L, 1L);
	}

	private long review(int playtime, boolean positive) {
		return insert(++recommendationId, playtime, positive, PERIOD.startInclusive());
	}

	private long insert(long recommendation, Integer playtime, boolean positive, Instant updatedAt) {
		return jdbc.queryForObject("""
			insert into recent_review (recommendationid, appid, review_text, voted_up, votes_up,
			    playtime_at_review, language_code, created_ts, updated_ts)
			values (?, ?, 'review fixture', ?, 0, ?, 'english', ?, ?) returning review_id
			""", Long.class, recommendation, gameId, positive, playtime,
			Timestamp.from(PERIOD.startInclusive().minusSeconds(60)), Timestamp.from(updatedAt));
	}

	private void classify(long reviewId, int... topicIds) {
		for (int topicId : topicIds) {
			jdbc.update("insert into review_topic (review_id, topic_id) values (?, ?)", reviewId, topicId);
		}
	}
}
