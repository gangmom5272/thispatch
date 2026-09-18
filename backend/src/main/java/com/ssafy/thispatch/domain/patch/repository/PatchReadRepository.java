package com.ssafy.thispatch.domain.patch.repository;

import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class PatchReadRepository {

	private final JdbcTemplate jdbcTemplate;

	public boolean gameExists(long gameId) {
		return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
			"SELECT EXISTS (SELECT 1 FROM game WHERE appid = ?)", Boolean.class, gameId));
	}

	public Optional<PatchRow> find(long gameId, String patchId) {
		// 화면의 패치 날짜는 공지 게시일이다. 적용일 집계와 관계없이 공지 원문을 조회한다.
		return jdbcTemplate.query("""
			SELECT gid, appid, title, published_ts, contents, url
			FROM news
			WHERE appid = ? AND gid = ? AND is_patch = true
			""", (row, index) -> new PatchRow(row.getString("gid"), row.getLong("appid"),
				row.getString("title"), row.getTimestamp("published_ts").toInstant(),
				row.getString("contents"), row.getString("url")),
			gameId, patchId).stream().findFirst();
	}

	public Optional<TranslationSource> findTranslationSource(String patchId) {
		return jdbcTemplate.query("""
			SELECT title, contents FROM news WHERE gid = ? AND is_patch = true
			""", (row, index) -> new TranslationSource(row.getString("title"), row.getString("contents")),
			patchId).stream().findFirst();
	}

	public record TranslationSource(String title, String contents) {
	}

	public record PatchRow(String patchId, long gameId, String title, Instant publishedAt,
		String contents, String url) {
	}
}
