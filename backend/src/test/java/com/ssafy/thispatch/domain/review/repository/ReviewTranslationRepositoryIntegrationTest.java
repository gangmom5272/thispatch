package com.ssafy.thispatch.domain.review.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ReviewTranslationRepositoryIntegrationTest {

	@Autowired JdbcTemplate jdbc;
	@Autowired ReviewReadRepository repository;

	@Test
	void readsExactPrimaryKeyWithoutPeriodOrLatestVersionFilterAndDoesNotWrite() {
		assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo("thispatch_test");
		long gameId;
		do {
			gameId = ThreadLocalRandom.current().nextLong(4_000_000_000L, 8_000_000_000L);
		} while (jdbc.queryForObject("select count(*) from game where appid = ?", Integer.class, gameId) != 0);
		jdbc.update("insert into game (appid, name, collected_at) values (?, 'translation fixture', now())", gameId);
		long oldId = insertReview(gameId, "old source\n😀", "2020-01-01");
		long newId = insertReview(gameId, "new source", "2020-01-02");
		var before = jdbc.queryForMap("select * from recent_review where review_id = ?", oldId);
		assertThat(repository.findTranslationSource(oldId)).contains(new ReviewReadRepository.TranslationSource("old source\n😀", "english"));
		assertThat(repository.findTranslationSource(newId)).contains(new ReviewReadRepository.TranslationSource("new source", "english"));
		assertThat(repository.findTranslationSource(-1)).isEmpty();
		assertThat(jdbc.queryForMap("select * from recent_review where review_id = ?", oldId)).isEqualTo(before);
	}

	private long insertReview(long gameId, String text, String date) {
		return jdbc.queryForObject("""
			insert into recent_review (recommendationid, appid, review_text, voted_up, votes_up,
			    language_code, created_ts, updated_ts)
			values (?, ?, ?, true, 0, 'english', cast(? as timestamptz), cast(? as timestamptz))
			returning review_id
			""", Long.class, gameId, gameId, text, date, date);
	}
}
