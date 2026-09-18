package com.ssafy.thispatch.domain.patch.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PatchReadRepositoryIntegrationTest {

	@Autowired private JdbcTemplate jdbc;
	@Autowired private PatchReadRepository repository;
	private long gameId;
	private String patchId;

	@BeforeEach
	void prepareFixturesInTestDatabase() {
		assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo("thispatch_test");
		do {
			gameId = ThreadLocalRandom.current().nextLong(4_000_000_000L, 8_000_000_000L);
		} while (repository.gameExists(gameId) || repository.gameExists(gameId + 1));
		do {
			patchId = Long.toUnsignedString(ThreadLocalRandom.current().nextLong());
		} while (jdbc.queryForObject("select count(*) from news where gid = ?", Integer.class, patchId) != 0);
		jdbc.update("insert into game (appid, name, collected_at) values (?, 'patch fixture', now()), (?, 'other game', now())", gameId, gameId + 1);
		jdbc.update("""
			insert into news (gid, appid, title, contents, published_ts, is_patch, collected_at)
			values (?, ?, 'Patch', '[b]Fix[/b]', ?, true, now())
			""", patchId, gameId, OffsetDateTime.parse("2026-09-15T15:30:00Z"));
	}

	@Test
	void readsStoredPublicationAndBodyWithoutWritingData() {
		var before = jdbc.queryForMap("select * from news where gid = ?", patchId);
		var patch = repository.find(gameId, patchId).orElseThrow();
		assertThat(repository.gameExists(gameId)).isTrue();
		assertThat(patch.patchId()).isEqualTo(patchId);
		assertThat(patch.publishedAt()).isEqualTo(Instant.parse("2026-09-15T15:30:00Z"));
		assertThat(patch.contents()).isEqualTo("[b]Fix[/b]");
		assertThat(patch.url()).isNull();
		assertThat(jdbc.queryForMap("select * from news where gid = ?", patchId)).isEqualTo(before);
	}

	@Test
	void translationReadsOnlyPatchByExactStringIdWithoutWriting() {
		String stringId = "00018446744073709551";
		jdbc.update("update news set gid = ? where gid = ?", stringId, patchId);
		var before = jdbc.queryForMap("select * from news where gid = ?", stringId);
		assertThat(repository.findTranslationSource(stringId))
			.contains(new PatchReadRepository.TranslationSource("Patch", "[b]Fix[/b]"));
		assertThat(repository.findTranslationSource("18446744073709551")).isEmpty();
		assertThat(repository.findTranslationSource("missing-patch")).isEmpty();
		assertThat(jdbc.queryForMap("select * from news where gid = ?", stringId)).isEqualTo(before);
		jdbc.update("update news set title = '', contents = '' where gid = ?", stringId);
		assertThat(repository.findTranslationSource(stringId))
			.contains(new PatchReadRepository.TranslationSource("", ""));
		jdbc.update("update news set is_patch = false where gid = ?", stringId);
		assertThat(repository.findTranslationSource(stringId)).isEmpty();
	}

	@Test
	void doesNotReadAnotherGamesPatchOrNonPatchAnnouncement() {
		assertThat(repository.find(gameId + 1, patchId)).isEmpty();
		jdbc.update("update news set is_patch = false where gid = ?", patchId);
		assertThat(repository.find(gameId, patchId)).isEmpty();
	}
}
