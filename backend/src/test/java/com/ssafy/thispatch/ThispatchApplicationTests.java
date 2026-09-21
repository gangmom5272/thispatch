package com.ssafy.thispatch;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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

	@Test
	void steamTagSeedUpdatesExistingTagsAndPreservesLinksAndOtherTags() {
		try (var context = startApplication()) {
			var jdbc = context.getBean(JdbcTemplate.class);
			assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo("thispatch_test");
			new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).executeWithoutResult(status -> {
				status.setRollbackOnly();
				// 같은 컬럼/PK의 임시 테이블로 기존 수집 데이터가 있는 상황을 재현한다.
				// search_path의 임시 테이블을 사용하므로 public 데이터는 변경하지 않는다.
				jdbc.execute("CREATE TEMP TABLE tag (LIKE public.tag INCLUDING ALL) ON COMMIT DROP");
				jdbc.execute("CREATE TEMP TABLE game_tag (LIKE public.game_tag INCLUDING ALL) ON COMMIT DROP");
				jdbc.execute("ALTER TABLE game_tag ADD FOREIGN KEY (tag_id) REFERENCES tag (tag_id)");
				jdbc.update("INSERT INTO tag (tag_id, name_ko) VALUES (9, '예전 이름'), (2147483647, '기존 사용자 태그')");
				jdbc.update("INSERT INTO game_tag (appid, tag_id, weight) VALUES (730, 9, 7)");

				var script = new EncodedResource(new ClassPathResource("db/migration/V9__seed_steam_tags.sql"),
					StandardCharsets.UTF_8);
				for (int attempt = 0; attempt < 2; attempt++) {
					jdbc.execute((ConnectionCallback<Void>) connection -> {
						ScriptUtils.executeSqlScript(connection, script);
						return null;
					});
					assertThat(jdbc.queryForObject("SELECT count(*) FROM tag", Integer.class)).isEqualTo(447);
					assertThat(jdbc.queryForObject("""
						SELECT count(*) FROM tag WHERE collected_at = TIMESTAMPTZ '2026-09-17T01:57:51Z'
						""", Integer.class)).isEqualTo(446);
					assertThat(jdbc.queryForObject("SELECT name_ko FROM tag WHERE tag_id = 9", String.class))
						.isEqualTo("전략");
					assertThat(jdbc.queryForObject("SELECT name_ko FROM tag WHERE tag_id = 2147483647", String.class))
						.isEqualTo("기존 사용자 태그");
					assertThat(jdbc.queryForList("SELECT appid, tag_id, weight FROM game_tag"))
						.containsExactly(Map.of("appid", 730L, "tag_id", 9, "weight", 7));
				}
			});
		}
	}

	private void assertMigrationHistory(JdbcTemplate jdbc) {
		var history = migrationHistory(jdbc);
		assertThat(history).as("V1 through V17, each applied exactly once").hasSize(17);
		assertThat(history).extracting(row -> row.get("version"))
			.containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16", "17");
		assertThat(history).extracting(row -> row.get("script"))
			.containsExactly("V1__init.sql", "V2__add_patch_analysis.sql", "V3__add_member_refresh_token.sql",
				"V4__add_member_steam_id_unique.sql", "V5__rename_news_published_at_to_ts.sql", "V6__add_member_email_unique.sql",
				"V7__add_band_topic_positive_count.sql", "V8__add_game_play_modes.sql", "V9__seed_steam_tags.sql",
				"V10__seed_language.sql", "V11__seed_topic.sql", "V12__index_patch_chunk_change.sql",
				"V13__add_patch_plan_history.sql", "V14__support_patch_plan_staged_storage.sql", "V15__index_patch_chunk_embedding_hnsw.sql",
				"V16__index_service_tables_by_appid.sql", "V17__unique_recent_review_recommendationid.sql");
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
				"patch_chunk", "patch_change", "patch_change_type", "patch_change_direction", "patch_change_target_type",
				"play_mode", "game_play_mode", "patch_plan", "patch_plan_genre", "patch_plan_entity",
				"patch_plan_slot", "patch_plan_restatement", "patch_plan_confirmed_slot");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_extension WHERE extname = 'vector'", Integer.class))
			.isEqualTo(1);
		assertThat(jdbc.queryForObject("""
			SELECT format_type(atttypid, atttypmod) FROM pg_attribute
			WHERE attrelid = 'public.patch_chunk'::regclass AND attname = 'embedding' AND NOT attisdropped
			""", String.class)).isEqualTo("vector(512)");
	}

	private void assertConstraints(JdbcTemplate jdbc) {
		assertThat(jdbc.queryForObject("""
			SELECT data_type || '|' || is_nullable FROM information_schema.columns
			WHERE table_schema = 'public' AND table_name = 'band_topic_stat' AND column_name = 'positive_count'
			""", String.class)).isEqualTo("integer|YES");
		assertThat(jdbc.queryForObject("""
			SELECT pg_get_constraintdef(oid) FROM pg_constraint
			WHERE conrelid = 'public.band_topic_stat'::regclass
			  AND conname = 'ck_band_topic_stat_positive_count' AND convalidated
			""", String.class)).isEqualTo("CHECK ((positive_count >= 0))");
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
		assertPlayModes(jdbc);
		assertLanguages(jdbc);
		assertTopics(jdbc);
		var tags = jdbc.queryForList("SELECT tag_id, name_ko FROM tag");
		assertThat(tags).as("Steam tag snapshot and any existing tags").hasSizeGreaterThanOrEqualTo(446);
		assertThat(tags).extracting(row -> row.get("tag_id") + "|" + row.get("name_ko"))
			.contains("9|전략", "19|액션", "122|RPG", "1352486|카피바라");
	}

	/**
	 * 플레이 모드 13종.
	 *
	 * <p>추측이 아니라 2026-09-16 에 스팀 카탈로그 185,640 개 게임을 전수로 훑어서
	 * 확인한 값이다. 여기가 줄거나 늘면 수집기가 모르는 ID 를 만나 조용히 버리게 되므로
	 * 개수와 이름을 그대로 고정한다.
	 *
	 * <p>{@code assertCodes} 를 쓰지 않는 이유는 이 표에 {@code code} 와
	 * {@code definition} 이 없기 때문이다. 스팀이 정한 숫자 ID 를 그대로 쓴다.
	 */
	private void assertPlayModes(JdbcTemplate jdbc) {
		var rows = jdbc.queryForList("SELECT play_mode_id, name_ko FROM play_mode");
		assertThat(rows).as("play_mode").hasSize(13);
		assertThat(rows).extracting(row -> row.get("play_mode_id") + "|" + row.get("name_ko"))
			.containsExactlyInAnyOrder("1|멀티플레이어", "2|싱글 플레이어", "9|협동", "20|MMO",
				"24|공유 및 분할 화면", "27|크로스 플랫폼 멀티플레이어", "36|온라인 PvP", "37|로컬 PvP",
				"38|온라인 협동", "39|스크린 공유 및 분할 협동", "47|LAN PvP", "48|LAN 협동", "49|PvP");
	}

	/**
	 * 스팀 리뷰 언어 코드 31종 (V10).
	 *
	 * <p>{@code language_stat.language_code} 가 이 표를 참조한다. 표가 비어 있던 2026-09-17 까지는
	 * 집계 적재가 FK 에 막혀 한 줄도 못 들어갔다. 코드는 스팀이 정한 값이라 여기서 그대로 고정한다.
	 * 리뷰에 새 코드가 나타나면 적재기가 건수를 찍고 빼므로, 그때 다음 마이그레이션으로 추가한다.
	 */
	private void assertLanguages(JdbcTemplate jdbc) {
		var rows = jdbc.queryForList("SELECT language_code, name_ko FROM language");
		assertThat(rows).as("language").hasSize(31);
		assertThat(rows).extracting(row -> row.get("language_code") + "|" + row.get("name_ko"))
			.containsExactlyInAnyOrder("english|영어", "schinese|중국어 간체", "tchinese|중국어 번체",
				"russian|러시아어", "spanish|스페인어", "latam|스페인어(중남미)", "brazilian|포르투갈어(브라질)",
				"portuguese|포르투갈어", "german|독일어", "french|프랑스어", "polish|폴란드어", "turkish|터키어",
				"koreana|한국어", "japanese|일본어", "thai|태국어", "italian|이탈리아어", "ukrainian|우크라이나어",
				"czech|체코어", "hungarian|헝가리어", "dutch|네덜란드어", "swedish|스웨덴어", "danish|덴마크어",
				"finnish|핀란드어", "norwegian|노르웨이어", "romanian|루마니아어", "bulgarian|불가리아어",
				"greek|그리스어", "vietnamese|베트남어", "indonesian|인도네시아어", "malay|말레이어", "arabic|아랍어");
	}

	/**
	 * 리뷰 토픽 5종 (V11 · 2026-09-18 진우님 확정).
	 *
	 * <p>{@code review_topic.topic_id} · {@code band_topic_stat.topic_id} 가 이 표를 참조한다. topic_id 는
	 * AI 분류기가 파케이에 쓰는 고정값이라 바꾸면 안 된다. {@code sim_threshold} 는 코사인 유사도가 아니라
	 * 분류기 확률 문턱이다. {@code centroid} 는 아직 쓰지 않아 null.
	 */
	private void assertTopics(JdbcTemplate jdbc) {
		var rows = jdbc.queryForList("SELECT topic_id, name_ko, centroid, sim_threshold FROM topic ORDER BY topic_id");
		assertThat(rows).as("topic").hasSize(5);
		assertThat(rows).extracting(row -> row.get("topic_id") + "|" + row.get("name_ko") + "|" + row.get("sim_threshold"))
			.containsExactly("1|밸런스|0.600", "2|버그·성능|0.575", "3|UI·조작|0.700", "4|운영·서버|0.700", "5|가격·과금|0.600");
		assertThat(rows).allSatisfy(row -> assertThat(row.get("centroid")).isNull());
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
