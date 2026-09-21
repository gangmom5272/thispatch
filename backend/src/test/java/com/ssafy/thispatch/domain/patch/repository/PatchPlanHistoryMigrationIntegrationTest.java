package com.ssafy.thispatch.domain.patch.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.thispatch.domain.patch.dto.PatchChangeCodes.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PatchPlanHistoryMigrationIntegrationTest {
	private static final String MIGRATION = "db/migration/V13__add_patch_plan_history.sql";
	private static final String RAW_TEXT = "Axebot 체력 20%, 공격력 10% 증가. Wraith 등장 빈도 조정.";
	private static final OffsetDateTime CREATED_AT = OffsetDateTime.parse("2026-09-21T14:32:01.123456+09:00");
	private static final List<String> HISTORY_TABLES = List.of("patch_plan", "patch_plan_genre",
		"patch_plan_entity", "patch_plan_slot", "patch_plan_restatement", "patch_plan_confirmed_slot");

	@Autowired private JdbcTemplate jdbc;
	private long memberId;
	private long gameId;
	private int genreId;
	private long planId;
	private long entityId;

	@BeforeEach
	void prepareOnlyInTestDatabase() {
		assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo("thispatch_test");
		memberId = jdbc.queryForObject("""
			INSERT INTO member (login_type, status, created_at) VALUES ('LOCAL', 'ACTIVE', now())
			RETURNING member_id
			""", Long.class);
		gameId = ThreadLocalRandom.current().nextLong(8_000_000_000L, 9_000_000_000L);
		genreId = ThreadLocalRandom.current().nextInt(1_000_000_000, 2_000_000_000);
		jdbc.update("INSERT INTO game (appid, name, collected_at) VALUES (?, '기획안 테스트 게임', now())", gameId);
		jdbc.update("INSERT INTO tag (tag_id, name_ko) VALUES (?, '기획안 테스트 장르')", genreId);
		planId = plan(CREATED_AT);
		entityId = entity(planId, 1, "Axebot", "ENEMY");
	}

	@Test
	void preservesIndependentSearchHistoriesAndOriginalAndConfirmedSlots() {
		long secondPlan = plan(CREATED_AT); // 같은 원문·사용자·게임·시각도 다른 검색 내역이다.
		long secondEntity = entity(secondPlan, 1, "Axebot", "ENEMY");
		long unknownEntity = entity(planId, 2, "Wraith", "UNKNOWN");
		jdbc.update("""
			UPDATE patch_plan_entity SET warning_code = 'UNKNOWN_ENTITY', warning_message = ?
			WHERE patch_plan_entity_id = ?
			""", "당시 표시한 미확인 대상 안내", unknownEntity);
		slot(entityId, 1, "체력", "MODIFY", "INCREASE", "20%", "고통 4 이상");
		slot(unknownEntity, 2, "등장 빈도", "MODIFY", "UNKNOWN", null, null);
		slot(entityId, 3, "공격력", "MODIFY", "INCREASE", "10%", "고통 4 이상");
		slot(secondEntity, 1, "체력", "MODIFY", "INCREASE", "20%", "고통 4 이상");
		confirmed(planId, 1, "Axebot", "ENEMY", "체력", "MODIFY", "INCREASE", "20%", "고통 4 이상");
		confirmed(secondPlan, 1, "사용자가 바꾼 이름", "PLAYER", "체력", "MODIFY", "INCREASE", "10%", null);
		confirmed(secondPlan, 2, "새로운 대상", "ITEM", "효과", "ADD", "NOT_APPLICABLE", null, null);
		jdbc.update("INSERT INTO patch_plan_genre VALUES (?, ?), (?, ?)", planId, genreId, planId, 9);
		jdbc.update("""
			INSERT INTO patch_plan_restatement (patch_plan_id, text, created_at) VALUES (?, ?, ?)
			""", planId, "최초 재진술\n체력과 공격력을 증가시킨다.", CREATED_AT);

		assertThat(jdbc.queryForList("""
			SELECT patch_plan_id FROM patch_plan WHERE member_id = ?
			ORDER BY created_at DESC, patch_plan_id DESC
			""", Long.class, memberId)).containsExactly(secondPlan, planId);
		assertThat(jdbc.queryForList("SELECT raw_text FROM patch_plan WHERE member_id = ?", String.class, memberId))
			.containsExactly(RAW_TEXT, RAW_TEXT);
		assertThat(jdbc.queryForObject("SELECT created_at FROM patch_plan WHERE patch_plan_id = ?",
			OffsetDateTime.class, planId).toInstant()).isEqualTo(CREATED_AT.toInstant());
		assertThat(jdbc.queryForList("""
			SELECT e.name || ':' || s.attribute || ':' || s.slot_order AS value
			FROM patch_plan_slot s JOIN patch_plan_entity e USING (patch_plan_entity_id)
			WHERE e.patch_plan_id = ? ORDER BY s.slot_order
			""", String.class, planId)).containsExactly("Axebot:체력:1", "Wraith:등장 빈도:2", "Axebot:공격력:3");
		assertThat(jdbc.queryForList("""
			SELECT magnitude FROM patch_plan_confirmed_slot WHERE patch_plan_id = ? ORDER BY slot_order
			""", String.class, secondPlan)).containsExactly("10%", null);
		assertThat(jdbc.queryForObject("""
			SELECT magnitude FROM patch_plan_confirmed_slot WHERE patch_plan_id = ?
			""", String.class, planId)).isEqualTo("20%");
		assertThat(jdbc.queryForObject("""
			SELECT magnitude FROM patch_plan_slot WHERE patch_plan_entity_id = ?
			""", String.class, secondEntity)).isEqualTo("20%");
		assertThat(jdbc.queryForList("""
			SELECT target_name FROM patch_plan_confirmed_slot WHERE patch_plan_id = ? ORDER BY slot_order
			""", String.class, secondPlan)).containsExactly("사용자가 바꾼 이름", "새로운 대상");
		assertThat(jdbc.queryForObject("""
			SELECT warning_message FROM patch_plan_entity WHERE patch_plan_entity_id = ?
			""", String.class, unknownEntity)).isEqualTo("당시 표시한 미확인 대상 안내");
		assertThat(jdbc.queryForObject("""
			SELECT text FROM patch_plan_restatement WHERE patch_plan_id = ?
			""", String.class, planId)).isEqualTo("최초 재진술\n체력과 공격력을 증가시킨다.");
		assertThat(jdbc.queryForList("""
			SELECT genre_id FROM patch_plan_genre WHERE patch_plan_id = ? ORDER BY genre_id
			""", Integer.class, planId)).containsExactly(9, genreId);
		assertThat(jdbc.queryForObject("""
			SELECT count(*) FROM patch_plan_genre WHERE patch_plan_id = ?
			""", Integer.class, secondPlan)).isZero(); // 빈 선택 배열(전체 장르)
	}

	@Test
	void acceptsEmptyOriginalResultAndPreservesGlobalWarningAndNullableValues() {
		long emptyPlan = plan(CREATED_AT);
		jdbc.update("""
			INSERT INTO patch_plan_restatement (patch_plan_id, text, warning_code, warning_message, created_at)
			VALUES (?, '', 'NO_CHANGES', '당시 변경점을 찾지 못했다는 안내', ?)
			""", emptyPlan, CREATED_AT);
		long blankEntity = entity(planId, 2, "", "ENEMY");
		jdbc.update("""
			UPDATE patch_plan_entity SET warning_code = 'UNKNOWN_ENTITY', warning_message = '대상 이름 확인'
			WHERE patch_plan_entity_id = ?
			""", blankEntity);
		slot(blankEntity, 1, "", "FIX", "UNKNOWN", null, null);
		confirmed(emptyPlan, 1, "", "UNKNOWN", "", "FIX", "UNKNOWN", null, null);
		assertThat(jdbc.queryForObject("""
			SELECT count(*) FROM patch_plan_entity WHERE patch_plan_id = ?
			""", Integer.class, emptyPlan)).isZero();
		assertThat(jdbc.queryForMap("""
			SELECT text, warning_code, warning_message FROM patch_plan_restatement WHERE patch_plan_id = ?
			""", emptyPlan)).containsEntry("text", "").containsEntry("warning_code", "NO_CHANGES")
			.containsEntry("warning_message", "당시 변경점을 찾지 못했다는 안내");
		assertThat(jdbc.queryForMap("""
			SELECT attribute, magnitude, scope FROM patch_plan_slot WHERE patch_plan_entity_id = ?
			""", blankEntity)).containsEntry("attribute", "").containsEntry("magnitude", null).containsEntry("scope", null);
	}

	@Test
	void acceptsAllExistingApiCodesAndLongUnindexedText() {
		int order = 1;
		for (TargetRole role : TargetRole.values()) {
			long id = entity(planId, ++order, role.name(), role.name());
			slot(id, order, "속성", "MODIFY", "UNKNOWN", null, null);
			confirmed(planId, order, role.name(), role.name(), "속성", "MODIFY", "UNKNOWN", null, null);
		}
		for (ChangeType type : ChangeType.values()) {
			slot(entityId, ++order, "속성", type.name(), "UNKNOWN", null, null);
			confirmed(planId, order, "대상", "OTHER", "속성", type.name(), "UNKNOWN", null, null);
		}
		for (Direction direction : Direction.values()) {
			slot(entityId, ++order, "속성", "MODIFY", direction.name(), null, null);
			confirmed(planId, order, "대상", "OTHER", "속성", "MODIFY", direction.name(), null, null);
		}
		String longName = UUID.randomUUID().toString().repeat(200);
		long longEntity = entity(planId, ++order, longName, "OTHER");
		assertThat(jdbc.queryForObject("""
			SELECT name FROM patch_plan_entity WHERE patch_plan_entity_id = ?
			""", String.class, longEntity)).isEqualTo(longName);
	}

	@ParameterizedTest
	@ValueSource(strings = {"genre-duplicate", "entity-order-duplicate", "slot-order-duplicate",
		"confirmed-order-duplicate", "restatement-duplicate", "missing-member", "missing-game",
		"missing-plan", "missing-genre", "missing-entity", "missing-confirmed-plan", "missing-restatement-plan",
		"delete-member", "delete-game", "delete-genre", "delete-plan", "delete-entity",
		"entity-order-zero", "slot-order-zero", "confirmed-order-zero", "role-invalid",
		"change-type-invalid", "direction-invalid", "confirmed-role-invalid",
		"confirmed-type-invalid", "confirmed-direction-invalid", "warning-code-only",
		"warning-message-only", "entity-warning-invalid", "global-warning-code-only",
		"global-warning-message-only", "global-warning-invalid", "null-original-text"})
	void rejectsBrokenReferencesDuplicatesAndInvalidValues(String scenario) {
		jdbc.update("INSERT INTO patch_plan_genre VALUES (?, ?)", planId, genreId);
		slot(entityId, 1, "체력", "MODIFY", "INCREASE", null, null);
		confirmed(planId, 1, "Axebot", "ENEMY", "체력", "MODIFY", "INCREASE", null, null);
		jdbc.update("INSERT INTO patch_plan_restatement (patch_plan_id, text, created_at) VALUES (?, '', ?)",
			planId, CREATED_AT);
		var failure = assertThrows(DataIntegrityViolationException.class, () -> {
			switch (scenario) {
				case "genre-duplicate" -> jdbc.update("INSERT INTO patch_plan_genre VALUES (?, ?)", planId, genreId);
				case "entity-order-duplicate" -> entity(planId, 1, "다른 대상", "OTHER");
				case "slot-order-duplicate" -> slot(entityId, 1, "공격력", "MODIFY", "INCREASE", null, null);
				case "confirmed-order-duplicate" -> confirmed(planId, 1, "다른 대상", "OTHER", "", "ADD", "NONE", null, null);
				case "restatement-duplicate" -> jdbc.update(
					"INSERT INTO patch_plan_restatement (patch_plan_id, text, created_at) VALUES (?, '', now())", planId);
				case "missing-member" -> jdbc.update("UPDATE patch_plan SET member_id = -1 WHERE patch_plan_id = ?", planId);
				case "missing-game" -> jdbc.update("UPDATE patch_plan SET appid = -1 WHERE patch_plan_id = ?", planId);
				case "missing-plan" -> entity(-1, 1, "대상", "OTHER");
				case "missing-genre" -> jdbc.update("INSERT INTO patch_plan_genre VALUES (?, -1)", planId);
				case "missing-entity" -> slot(-1, 1, "", "ADD", "NONE", null, null);
				case "missing-confirmed-plan" -> confirmed(-1, 1, "", "OTHER", "", "ADD", "NONE", null, null);
				case "missing-restatement-plan" -> jdbc.update(
					"INSERT INTO patch_plan_restatement (patch_plan_id, text, created_at) VALUES (-1, '', now())");
				case "delete-member" -> jdbc.update("DELETE FROM member WHERE member_id = ?", memberId);
				case "delete-game" -> jdbc.update("DELETE FROM game WHERE appid = ?", gameId);
				case "delete-genre" -> jdbc.update("DELETE FROM tag WHERE tag_id = ?", genreId);
				case "delete-plan" -> jdbc.update("DELETE FROM patch_plan WHERE patch_plan_id = ?", planId);
				case "delete-entity" -> jdbc.update("DELETE FROM patch_plan_entity WHERE patch_plan_entity_id = ?", entityId);
				case "entity-order-zero" -> entity(planId, 0, "", "UNKNOWN");
				case "slot-order-zero" -> slot(entityId, 0, "", "ADD", "NONE", null, null);
				case "confirmed-order-zero" -> confirmed(planId, 0, "", "OTHER", "", "ADD", "NONE", null, null);
				case "role-invalid" -> entity(planId, 2, "", "INVALID");
				case "change-type-invalid" -> slot(entityId, 2, "", "INVALID", "NONE", null, null);
				case "direction-invalid" -> slot(entityId, 2, "", "ADD", "INVALID", null, null);
				case "confirmed-role-invalid" -> confirmed(planId, 2, "", "INVALID", "", "ADD", "NONE", null, null);
				case "confirmed-type-invalid" -> confirmed(planId, 2, "", "OTHER", "", "INVALID", "NONE", null, null);
				case "confirmed-direction-invalid" -> confirmed(planId, 2, "", "OTHER", "", "ADD", "INVALID", null, null);
				case "warning-code-only" -> jdbc.update(
					"UPDATE patch_plan_entity SET warning_code = 'UNKNOWN_ENTITY' WHERE patch_plan_entity_id = ?", entityId);
				case "warning-message-only" -> jdbc.update(
					"UPDATE patch_plan_entity SET warning_message = '안내' WHERE patch_plan_entity_id = ?", entityId);
				case "entity-warning-invalid" -> jdbc.update(
					"UPDATE patch_plan_entity SET warning_code = 'NO_CHANGES', warning_message = '안내' WHERE patch_plan_entity_id = ?", entityId);
				case "global-warning-code-only" -> jdbc.update(
					"UPDATE patch_plan_restatement SET warning_code = 'NO_CHANGES' WHERE patch_plan_id = ?", planId);
				case "global-warning-message-only" -> jdbc.update(
					"UPDATE patch_plan_restatement SET warning_message = '안내' WHERE patch_plan_id = ?", planId);
				case "global-warning-invalid" -> jdbc.update(
					"UPDATE patch_plan_restatement SET warning_code = 'UNKNOWN_ENTITY', warning_message = '안내' WHERE patch_plan_id = ?", planId);
				case "null-original-text" -> jdbc.update("UPDATE patch_plan SET raw_text = NULL WHERE patch_plan_id = ?", planId);
				default -> throw new IllegalArgumentException(scenario);
			}
		});
		String expectedState = scenario.endsWith("-duplicate") ? "23505"
			: scenario.startsWith("missing-") || scenario.startsWith("delete-") ? "23503"
			: scenario.equals("null-original-text") ? "23502" : "23514";
		assertThat(failure.getMostSpecificCause()).isInstanceOf(SQLException.class);
		assertThat(((SQLException) failure.getMostSpecificCause()).getSQLState()).isEqualTo(expectedState);
	}

	@Test
	void failedSnapshotTransactionLeavesNoPartialHistory() {
		// 저장 서비스 구현 전, 이 스키마의 전체 입력을 한 트랜잭션으로 되돌릴 수 있는지 확인한다.
		jdbc.execute((ConnectionCallback<Void>) connection -> {
			var savepoint = connection.setSavepoint();
			long partialPlan = plan(CREATED_AT);
			entity(partialPlan, 1, "대상", "ENEMY");
			assertThrows(DataIntegrityViolationException.class,
				() -> confirmed(partialPlan, 1, "", "INVALID", "", "ADD", "NONE", null, null));
			connection.rollback(savepoint);
			connection.releaseSavepoint(savepoint);
			assertThat(jdbc.queryForObject("SELECT count(*) FROM patch_plan WHERE patch_plan_id = ?",
				Integer.class, partialPlan)).isZero();
			assertThat(jdbc.queryForObject("SELECT count(*) FROM patch_plan_entity WHERE patch_plan_id = ?",
				Integer.class, partialPlan)).isZero();
			return null;
		});
	}

	@Test
	void migrationCreatesHistoryTablesAndPreservesExistingParentDataInIsolatedSchema() {
		// public 데이터와 기존 Flyway 이력을 건드리지 않는 별도 스키마. DDL까지 테스트 종료 시 롤백한다.
		String schema = "plan_migration_" + UUID.randomUUID().toString().replace("-", "");
		jdbc.execute("CREATE SCHEMA " + schema);
		jdbc.execute("SET LOCAL search_path TO " + schema + ", public");
		// V13이 참조하는 기존 테이블만 복제한다. public을 직접 참조하는 이전 migration은 재실행하지 않는다.
		for (String table : List.of("member", "game", "tag", "game_tag")) {
			jdbc.execute("CREATE TABLE " + table + " (LIKE public." + table + " INCLUDING ALL)");
		}
		jdbc.execute("ALTER TABLE game_tag ADD FOREIGN KEY (appid) REFERENCES game (appid)");
		jdbc.execute("ALTER TABLE game_tag ADD FOREIGN KEY (tag_id) REFERENCES tag (tag_id)");
		jdbc.update("INSERT INTO member (login_type, status, created_at) VALUES ('LOCAL', 'ACTIVE', now())");
		jdbc.update("INSERT INTO game (appid, name, collected_at) VALUES (269, '기존 게임', now())");
		jdbc.update("INSERT INTO tag (tag_id, name_ko) VALUES (9, '기존 장르')");
		jdbc.update("INSERT INTO game_tag (appid, tag_id, weight) VALUES (269, 9, 1)");
		var membersBefore = jdbc.queryForList("SELECT * FROM member");
		var gamesBefore = jdbc.queryForList("SELECT * FROM game");
		var tagsBefore = jdbc.queryForList("SELECT * FROM tag ORDER BY tag_id");
		var linksBefore = jdbc.queryForList("SELECT * FROM game_tag");
		var script = new EncodedResource(new ClassPathResource(MIGRATION), StandardCharsets.UTF_8);
		jdbc.execute((ConnectionCallback<Void>) connection -> {
			ScriptUtils.executeSqlScript(connection, script);
			return null;
		});
		assertThat(jdbc.queryForList("""
			SELECT tablename FROM pg_tables WHERE schemaname = ? AND tablename LIKE 'patch_plan%'
			""", String.class, schema)).containsExactlyInAnyOrderElementsOf(HISTORY_TABLES);
		assertThat(jdbc.queryForList("SELECT * FROM member")).isEqualTo(membersBefore);
		assertThat(jdbc.queryForList("SELECT * FROM game")).isEqualTo(gamesBefore);
		assertThat(jdbc.queryForList("SELECT * FROM tag ORDER BY tag_id")).isEqualTo(tagsBefore);
		assertThat(jdbc.queryForList("SELECT * FROM game_tag")).isEqualTo(linksBefore);
		assertThat(jdbc.queryForObject("""
			INSERT INTO patch_plan (member_id, appid, raw_text, created_at)
			SELECT member_id, 269, '신규 저장', now() FROM member RETURNING patch_plan_id
			""", Long.class)).isPositive();
	}

	private long plan(OffsetDateTime createdAt) {
		return jdbc.queryForObject("""
			INSERT INTO patch_plan (member_id, appid, raw_text, created_at) VALUES (?, ?, ?, ?)
			RETURNING patch_plan_id
			""", Long.class, memberId, gameId, RAW_TEXT, createdAt);
	}

	private long entity(long plan, int order, String name, String role) {
		return jdbc.queryForObject("""
			INSERT INTO patch_plan_entity (patch_plan_id, entity_order, name, role) VALUES (?, ?, ?, ?)
			RETURNING patch_plan_entity_id
			""", Long.class, plan, order, name, role);
	}

	private void slot(long entity, int order, String attribute, String type, String direction, String magnitude, String scope) {
		jdbc.update("""
			INSERT INTO patch_plan_slot (patch_plan_entity_id, slot_order, attribute, change_type, direction, magnitude, scope)
			VALUES (?, ?, ?, ?, ?, ?, ?)
			""", entity, order, attribute, type, direction, magnitude, scope);
	}

	private void confirmed(long plan, int order, String name, String role, String attribute,
		String type, String direction, String magnitude, String scope) {
		jdbc.update("""
			INSERT INTO patch_plan_confirmed_slot (patch_plan_id, slot_order, target_name, target_role,
				attribute, change_type, direction, magnitude, scope, created_at)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			""", plan, order, name, role, attribute, type, direction, magnitude, scope, CREATED_AT);
	}
}
