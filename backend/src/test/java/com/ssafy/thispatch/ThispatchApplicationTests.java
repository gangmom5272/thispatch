package com.ssafy.thispatch;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

class ThispatchApplicationTests {

	@Test
	void migrationsApplyAndRemainUnchangedAfterRestart() {
		List<Map<String, Object>> history;
		try (var context = startApplication()) {
			var jdbc = context.getBean(JdbcTemplate.class);
			assertThat(jdbc.queryForObject("SELECT version()", String.class)).startsWith("PostgreSQL");
			assertMigrationHistory(jdbc);
			assertTablesAndVector(jdbc);
			assertConstraints(jdbc);
			assertSeedData(jdbc);
			history = migrationHistory(jdbc);
		}

		// Close the first application completely, then boot against the same database.
		try (var context = startApplication()) {
			var jdbc = context.getBean(JdbcTemplate.class);
			assertThat(migrationHistory(jdbc)).as("migration history after restart").isEqualTo(history);
			assertThat(context.getBean(Flyway.class).info().pending()).isEmpty();
			context.getBean(Flyway.class).validate();
			assertSeedData(jdbc);
		}
	}

	private ConfigurableApplicationContext startApplication() {
		return new SpringApplicationBuilder(ThispatchApplication.class)
			.web(WebApplicationType.NONE)
			.profiles("test")
			.run();
	}

	private void assertMigrationHistory(JdbcTemplate jdbc) {
		var history = migrationHistory(jdbc);
		assertThat(history).as("V1 through V6, each applied exactly once").hasSize(6);
		assertThat(history).extracting(row -> row.get("version")).containsExactly("1", "2", "3", "4", "5", "6");
		assertThat(history).extracting(row -> row.get("script"))
			.containsExactly("V1__init.sql", "V2__add_patch_analysis.sql", "V3__add_member_refresh_token.sql",
				"V4__add_member_steam_id_unique.sql", "V5__rename_news_published_at_to_ts.sql",
				"V6__add_member_email_unique.sql");
		assertThat(history).allSatisfy(row -> {
			assertThat(row.get("success")).isEqualTo(true);
			assertThat(row.get("checksum")).isNotNull();
		});
	}

	private List<Map<String, Object>> migrationHistory(JdbcTemplate jdbc) {
		return jdbc.queryForList("SELECT * FROM public.flyway_schema_history ORDER BY installed_rank");
	}

	private void assertTablesAndVector(JdbcTemplate jdbc) {
		assertThat(jdbc.queryForList("SELECT tablename FROM pg_tables WHERE schemaname = 'public'", String.class))
			.containsExactlyInAnyOrder(
				"flyway_schema_history", "patch_stat", "tag", "my_game", "daily_stat", "topic", "news",
				"band_topic_stat", "band_stat", "recent_review", "batch_log", "game_tag", "game",
				"language", "language_stat", "member", "batch_job", "review_topic",
				"patch_chunk", "patch_change", "patch_change_type", "patch_change_direction", "patch_change_target_type");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_extension WHERE extname = 'vector'", Integer.class))
			.isEqualTo(1);
		assertThat(jdbc.queryForObject("""
			SELECT format_type(atttypid, atttypmod) FROM pg_attribute
			WHERE attrelid = 'public.patch_chunk'::regclass AND attname = 'embedding' AND NOT attisdropped
			""", String.class)).isEqualTo("vector(512)");
	}

	private void assertConstraints(JdbcTemplate jdbc) {
		assertThat(jdbc.queryForObject("""
			SELECT pg_get_constraintdef(oid) FROM pg_constraint
			WHERE conrelid = 'public.member'::regclass AND conname = 'uk_member_email' AND convalidated
			""", String.class)).isEqualTo("UNIQUE (email)");
		var constraints = jdbc.queryForList("""
			SELECT t.relname AS table_name, c.conname, c.contype::text AS kind,
			       pg_get_constraintdef(c.oid) AS definition, c.convalidated
			FROM pg_constraint c
			JOIN pg_class t ON t.oid = c.conrelid
			JOIN pg_namespace n ON n.oid = t.relnamespace
			WHERE n.nspname = 'public' AND c.contype IN ('p', 'f', 'u', 'c')
			  AND t.relname IN ('patch_chunk', 'patch_change', 'patch_change_type',
			                    'patch_change_direction', 'patch_change_target_type')
			""");
		var expected = Map.ofEntries(
			Map.entry("patch_change_type.pk_patch_change_type", "PRIMARY KEY (change_type_id)"),
			Map.entry("patch_change_direction.pk_patch_change_direction", "PRIMARY KEY (direction_id)"),
			Map.entry("patch_change_target_type.pk_patch_change_target_type", "PRIMARY KEY (target_type_id)"),
			Map.entry("patch_chunk.pk_patch_chunk", "PRIMARY KEY (chunk_id)"),
			Map.entry("patch_change.pk_patch_change", "PRIMARY KEY (patch_change_id)"),
			Map.entry("patch_chunk.fk_patch_chunk_news", "FOREIGN KEY (gid) REFERENCES news(gid)"),
			Map.entry("patch_change.fk_patch_change_chunk", "FOREIGN KEY (chunk_id) REFERENCES patch_chunk(chunk_id)"),
			Map.entry("patch_change.fk_patch_change_change_type", "FOREIGN KEY (change_type_id) REFERENCES patch_change_type(change_type_id)"),
			Map.entry("patch_change.fk_patch_change_direction", "FOREIGN KEY (direction_id) REFERENCES patch_change_direction(direction_id)"),
			Map.entry("patch_change.fk_patch_change_target_type", "FOREIGN KEY (target_type_id) REFERENCES patch_change_target_type(target_type_id)"));
		// Current V2 defines only five PKs and five FKs; no UNIQUE or CHECK constraints.
		assertThat(constraints).hasSize(expected.size());
		assertThat(constraints).allSatisfy(row -> {
			String key = row.get("table_name") + "." + row.get("conname");
			assertThat(row.get("convalidated")).as(key).isEqualTo(true);
			assertThat(expected).containsKey(key);
			assertThat(row.get("definition")).as(key).isEqualTo(expected.get(key));
		});
	}

	private void assertSeedData(JdbcTemplate jdbc) {
		assertCodes(jdbc, "patch_change_type", List.of("add|추가", "remove|제거", "modify|변경", "fix|수정", "deprecate|지원 중단"));
		assertCodes(jdbc, "patch_change_direction", List.of("increase|증가", "decrease|감소", "none|방향 없음",
			"not_applicable|해당 없음", "unknown|알 수 없음"));
		assertCodes(jdbc, "patch_change_target_type", List.of("player|플레이어", "enemy|적", "weapon|무기", "item|아이템",
			"skill|스킬", "map|맵", "system|시스템", "other|기타", "unknown|알 수 없음"));
	}

	private void assertCodes(JdbcTemplate jdbc, String table, List<String> expected) {
		// Table names are fixed test constants, never external input.
		var rows = jdbc.queryForList("SELECT code, name_ko, definition FROM " + table);
		assertThat(rows).as(table).hasSize(expected.size());
		assertThat(rows).extracting(row -> row.get("code") + "|" + row.get("name_ko"))
			.containsExactlyInAnyOrderElementsOf(expected);
		assertThat(rows).allSatisfy(row -> assertThat(row.get("definition")).isNull());
	}

}
