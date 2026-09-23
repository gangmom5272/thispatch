package com.ssafy.thispatch.domain.statistics.repository;

import java.sql.Timestamp;
import java.sql.Types;
import java.util.List;
import java.util.stream.IntStream;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.ssafy.thispatch.domain.review.repository.ReviewReadRepository.ReviewRow;
import com.ssafy.thispatch.domain.statistics.service.ReviewPeriod;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class SummaryReviewRepository {

	// DB에서도 AI 입력 검증의 String.isBlank()와 같은 공백 문자 집합을 사용한다.
	private static final String BLANK_CHARACTERS = IntStream.rangeClosed(0, Character.MAX_CODE_POINT)
		.filter(Character::isWhitespace)
		.collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();

	private final NamedParameterJdbcTemplate jdbcTemplate;

	public String gameName(long gameId) {
		return jdbcTemplate.queryForObject("SELECT name FROM game WHERE appid = :gameId",
			new MapSqlParameterSource("gameId", gameId), String.class);
	}

	public boolean languageExists(String languageCode) {
		return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
			SELECT EXISTS (SELECT 1 FROM language
			WHERE language_code = :language OR (:language = 'korean' AND language_code = 'koreana'))
			""", new MapSqlParameterSource("language", languageCode), Boolean.class));
	}

	public List<SummaryReview> findSelected(long gameId, ReviewPeriod period, Integer bandNo,
		String languageCode, int limit) {
		var parameters = new MapSqlParameterSource("gameId", gameId)
			.addValue("startTime", Timestamp.from(period.startInclusive()))
			.addValue("endTime", Timestamp.from(period.endExclusive()))
			.addValue("bandNo", bandNo, Types.INTEGER)
			.addValue("language", languageCode, Types.VARCHAR)
			.addValue("blankCharacters", BLANK_CHARACTERS)
			.addValue("limit", limit);
		String scopeFilter = languageCode == null ? """
			AND btrim(r.review_text, :blankCharacters) <> ''
			AND EXISTS (SELECT 1 FROM band_stat b
			    WHERE b.appid = :gameId AND (:bandNo IS NULL OR b.band_no = :bandNo)
			      AND r.playtime_at_review >= b.playtime_from
			      AND (b.playtime_to IS NULL OR r.playtime_at_review < b.playtime_to))
			""" : """
			AND (CASE WHEN r.language_code = 'koreana' THEN 'korean' ELSE r.language_code END) = :language
			""";
		// 최신 버전 선택 후 범위·공백 조건을 적용하고, 유효 건수는 LIMIT 전에 계산한다.
		return jdbcTemplate.query("""
			WITH latest_reviews AS (
			    SELECT DISTINCT ON (recommendationid) * FROM recent_review WHERE appid = :gameId
			    ORDER BY recommendationid, updated_ts DESC, review_id DESC
			)
			SELECT r.*, count(*) OVER () AS target_count FROM latest_reviews r
			WHERE r.updated_ts >= :startTime AND r.updated_ts < :endTime
			""" + scopeFilter + " ORDER BY r.votes_up DESC, r.review_id DESC LIMIT :limit", parameters,
			(row, index) -> new SummaryReview(row.getLong("target_count"), new ReviewRow(
				row.getLong("review_id"), row.getString("review_text"), row.getBoolean("voted_up"),
				row.getInt("votes_up"), row.getObject("playtime_at_review", Integer.class),
				row.getString("language_code"), row.getTimestamp("created_ts").toInstant(),
				row.getTimestamp("updated_ts").toInstant())));
	}

	public record SummaryReview(long targetCount, ReviewRow review) {
	}
}
