package com.ssafy.thispatch.domain.review.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Set;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.ssafy.thispatch.domain.statistics.service.ReviewPeriod;
import com.ssafy.thispatch.domain.review.dto.response.ReviewItem.Tag;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class ReviewReadRepository {

	private static final int REPRESENTATIVE_REVIEW_LIMIT = 4;
	private static final String LATEST_REVIEWS = """
		WITH latest_reviews AS (
		    SELECT DISTINCT ON (recommendationid)
		           review_id, review_text, voted_up, votes_up, playtime_at_review,
		           language_code, created_ts, updated_ts
		    FROM recent_review WHERE appid = :gameId
		    ORDER BY recommendationid, updated_ts DESC, review_id DESC
		)
		""";

	private final NamedParameterJdbcTemplate jdbcTemplate;

	public Optional<TranslationSource> findTranslationSource(long reviewId) {
		return jdbcTemplate.query("""
			SELECT review_text, language_code FROM recent_review WHERE review_id = :reviewId
			""", new MapSqlParameterSource("reviewId", reviewId),
			(row, index) -> new TranslationSource(row.getString("review_text"), row.getString("language_code")))
			.stream().findFirst();
	}

	public record TranslationSource(String text, String languageCode) {
	}

	public List<ReviewRow> findRepresentatives(long gameId, ReviewPeriod period) {
		return findHelpfulReviews(gameId, period, Set.of(), null, REPRESENTATIVE_REVIEW_LIMIT);
	}

	public List<ReviewRow> findHelpfulReviews(long gameId, ReviewPeriod period,
		Set<Integer> topicIds, HelpfulPosition after, int limit) {
		if (limit < 1 || limit > 101) {
			// 목록의 최대 100건과 다음 페이지 유무를 확인할 추가 1건까지 조회한다.
			throw new IllegalArgumentException("Review query limit must be between 1 and 101");
		}
		MapSqlParameterSource parameters = new MapSqlParameterSource("gameId", gameId)
			.addValue("startTime", Timestamp.from(period.startInclusive()))
			.addValue("endTime", Timestamp.from(period.endExclusive()))
			.addValue("limit", limit);
		// 최신 버전을 먼저 선택해야 예전 본문이나 예전 도움됨 수로 리뷰가 다시 노출되지 않는다.
		StringBuilder sql = new StringBuilder(LATEST_REVIEWS).append("""
			SELECT r.* FROM latest_reviews r
			WHERE r.updated_ts >= :startTime AND r.updated_ts < :endTime
			""");
		if (!topicIds.isEmpty()) {
			sql.append("""
				AND EXISTS (
				    SELECT 1 FROM review_topic rt
				    WHERE rt.review_id = r.review_id AND rt.topic_id IN (:topicIds)
				)
				""");
			parameters.addValue("topicIds", topicIds);
		}
		if (after != null) {
			sql.append("""
				AND (r.votes_up < :helpfulCount
				     OR (r.votes_up = :helpfulCount AND r.review_id < :reviewId))
				""");
			parameters.addValue("helpfulCount", after.helpfulCount())
				.addValue("reviewId", after.reviewId());
		}
		sql.append("ORDER BY r.votes_up DESC, r.review_id DESC LIMIT :limit");
		return jdbcTemplate.query(sql.toString(), parameters, (row, index) -> new ReviewRow(
			row.getLong("review_id"), row.getString("review_text"), row.getBoolean("voted_up"),
			row.getInt("votes_up"), row.getObject("playtime_at_review", Integer.class),
			row.getString("language_code"), row.getTimestamp("created_ts").toInstant(),
			row.getTimestamp("updated_ts").toInstant()));
	}

	public long countWithinPeriod(long gameId, ReviewPeriod period, Set<Integer> topicIds) {
		var parameters = new MapSqlParameterSource("gameId", gameId)
			.addValue("startTime", Timestamp.from(period.startInclusive()))
			.addValue("endTime", Timestamp.from(period.endExclusive()));
		String sql = LATEST_REVIEWS + """
			SELECT count(*) FROM latest_reviews r
			WHERE r.updated_ts >= :startTime AND r.updated_ts < :endTime
			""";
		if (!topicIds.isEmpty()) {
			sql += """
				AND EXISTS (SELECT 1 FROM review_topic rt
				            WHERE rt.review_id = r.review_id AND rt.topic_id IN (:topicIds))
				""";
			parameters.addValue("topicIds", topicIds);
		}
		return jdbcTemplate.queryForObject(sql, parameters, Long.class);
	}

	public boolean topicsExist(Set<Integer> topicIds) {
		return topicIds.isEmpty() || jdbcTemplate.queryForObject(
			"SELECT count(*) FROM topic WHERE topic_id IN (:ids)",
			new MapSqlParameterSource("ids", topicIds), Integer.class) == topicIds.size();
	}

	public Map<Long, List<Tag>> findTags(List<Long> reviewIds) {
		Map<Long, List<Tag>> tags = new HashMap<>();
		if (reviewIds.isEmpty()) {
			return tags;
		}
		jdbcTemplate.query("""
			SELECT rt.review_id, t.topic_id, t.name_ko FROM review_topic rt
			JOIN topic t ON t.topic_id = rt.topic_id
			WHERE rt.review_id IN (:ids) ORDER BY rt.review_id, t.topic_id
			""", new MapSqlParameterSource("ids", reviewIds), (org.springframework.jdbc.core.RowCallbackHandler) row ->
				tags.computeIfAbsent(row.getLong("review_id"), ignored -> new ArrayList<>())
					.add(new Tag(row.getInt("topic_id"), row.getString("name_ko"))));
		return tags;
	}

	public record HelpfulPosition(int helpfulCount, long reviewId) {
	}

	public record ReviewRow(long id, String body, boolean positive, int helpfulCount,
		Integer playtimeMinutes, String languageCode, Instant createdAt, Instant updatedAt) {
	}
}
