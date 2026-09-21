package com.ssafy.thispatch.domain.patch.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PatchPlanStagedStorageMigrationIntegrationTest {

	private static final OffsetDateTime STRUCTURED_AT = OffsetDateTime.parse("2026-09-21T14:00:00+09:00");
	private static final OffsetDateTime SEARCHED_AT = STRUCTURED_AT.plusMinutes(10);
	private static final List<String> TABLES = List.of("patch_plan", "patch_plan_entity", "patch_plan_slot",
		"patch_plan_restatement", "patch_plan_genre", "patch_plan_confirmed_slot");
	@Autowired private JdbcTemplate jdbc;

	@BeforeEach
	void isolateMigrationInTestDatabase() {
		assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo("thispatch_test");
		String schema = "plan_staged_" + UUID.randomUUID().toString().replace("-", "");
		jdbc.execute("CREATE SCHEMA " + schema);
		jdbc.execute("SET LOCAL search_path TO " + schema + ", public");
		// V13의 FK가 참조하는 PK만 준비한다. public 데이터·시퀀스·Flyway 이력은 건드리지 않는다.
		jdbc.execute("CREATE TABLE member (member_id BIGINT PRIMARY KEY)");
		jdbc.execute("CREATE TABLE game (appid BIGINT PRIMARY KEY)");
		jdbc.execute("CREATE TABLE tag (tag_id INTEGER PRIMARY KEY)");
		jdbc.update("INSERT INTO member VALUES (1)");
		jdbc.update("INSERT INTO game VALUES (283)");
		jdbc.update("INSERT INTO tag VALUES (9)");
		migrate("V13__add_patch_plan_history.sql");
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void upgradesEmptyOrPopulatedSchemaWithoutChangingExistingHistory(boolean populated) {
		if (populated) seedLegacyHistory();
		Map<String, List<Map<String, Object>>> before = new LinkedHashMap<>();
		for (String table : TABLES) before.put(table, jdbc.queryForList("SELECT * FROM " + table));
		migrate("V14__support_patch_plan_staged_storage.sql");
		for (String table : TABLES) {
			var after = jdbc.queryForList("SELECT * FROM " + table);
			if (table.equals("patch_plan")) {
				for (var row : after) {
					assertThat(row).containsEntry("structured_at", null);
					row.remove("structured_at");
				}
			}
			assertThat(after).as("preserved %s", table).isEqualTo(before.get(table));
		}
		long draft = draft();
		assertThat(jdbc.queryForMap("SELECT structured_at, created_at FROM patch_plan WHERE patch_plan_id = ?", draft))
			.containsEntry("created_at", null);
		assertThat(jdbc.queryForObject("SELECT structured_at FROM patch_plan WHERE patch_plan_id = ?",
			OffsetDateTime.class, draft).toInstant()).isEqualTo(STRUCTURED_AT.toInstant());
		assertThat(jdbc.queryForList("SELECT member_id FROM member", Long.class)).containsExactly(1L);
		assertThat(jdbc.queryForList("SELECT appid FROM game", Long.class)).containsExactly(283L);
		assertThat(jdbc.queryForList("SELECT tag_id FROM tag", Integer.class)).containsExactly(9);
	}

	@Test
	void rejectsHistoryWithNeitherStructureNorSearchTimestamp() {
		migrate("V14__support_patch_plan_staged_storage.sql");
		var failure = assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
			INSERT INTO patch_plan (member_id, appid, raw_text) VALUES (1, 283, '시각 없는 내역')
			"""));
		assertThat(failure.getMostSpecificCause()).isInstanceOf(SQLException.class);
		assertThat(((SQLException) failure.getMostSpecificCause()).getSQLState()).isEqualTo("23514");
	}

	@Test
	void failedConfirmationCanRollBackWithoutLosingOriginalStructure() {
		migrate("V14__support_patch_plan_staged_storage.sql");
		long plan = draft();
		jdbc.update("INSERT INTO patch_plan_restatement (patch_plan_id, text, created_at) VALUES (?, '최초 해석', ?)",
			plan, STRUCTURED_AT);
		// 저장 서비스 연동 전, 새 스키마에서도 확정 단계 전체를 원자적으로 되돌릴 수 있는지 검증한다.
		jdbc.execute((ConnectionCallback<Void>) connection -> {
			var savepoint = connection.setSavepoint();
			jdbc.update("UPDATE patch_plan SET created_at = ? WHERE patch_plan_id = ?", SEARCHED_AT, plan);
			jdbc.update("INSERT INTO patch_plan_genre VALUES (?, 9)", plan);
			confirmed(plan);
			assertThrows(DataIntegrityViolationException.class, () -> confirmed(plan));
			connection.rollback(savepoint);
			connection.releaseSavepoint(savepoint);
			return null;
		});
		assertThat(jdbc.queryForMap("SELECT raw_text, created_at FROM patch_plan WHERE patch_plan_id = ?", plan))
			.containsEntry("raw_text", "최초 원문").containsEntry("created_at", null);
		assertThat(jdbc.queryForObject("SELECT text FROM patch_plan_restatement WHERE patch_plan_id = ?", String.class, plan))
			.isEqualTo("최초 해석");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM patch_plan_genre WHERE patch_plan_id = ?", Integer.class, plan)).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM patch_plan_confirmed_slot WHERE patch_plan_id = ?", Integer.class, plan)).isZero();
	}

	@Test
	void originalStructureTimeCanBeSharedByIndependentCompletedSearches() {
		migrate("V14__support_patch_plan_staged_storage.sql");
		long first = draft();
		jdbc.update("UPDATE patch_plan SET created_at = ? WHERE patch_plan_id = ?", SEARCHED_AT, first);
		long second = jdbc.queryForObject("""
			INSERT INTO patch_plan (member_id, appid, raw_text, structured_at, created_at)
			SELECT member_id, appid, raw_text, structured_at, ? FROM patch_plan WHERE patch_plan_id = ?
			RETURNING patch_plan_id
			""", Long.class, SEARCHED_AT.plusMinutes(5), first);
		assertThat(second).isNotEqualTo(first);
		assertThat(jdbc.queryForList("SELECT patch_plan_id FROM patch_plan ORDER BY created_at DESC", Long.class))
			.containsExactly(second, first);
		assertThat(jdbc.queryForObject("SELECT created_at FROM patch_plan WHERE patch_plan_id = ?", OffsetDateTime.class, first)
			.toInstant()).isEqualTo(SEARCHED_AT.toInstant());
		assertThat(jdbc.queryForObject("SELECT structured_at FROM patch_plan WHERE patch_plan_id = ?", OffsetDateTime.class, second)
			.toInstant()).isEqualTo(STRUCTURED_AT.toInstant());
	}

	private void migrate(String name) {
		var script = new EncodedResource(new ClassPathResource("db/migration/" + name), StandardCharsets.UTF_8);
		jdbc.execute((ConnectionCallback<Void>) connection -> {
			ScriptUtils.executeSqlScript(connection, script);
			return null;
		});
	}

	private long draft() {
		return jdbc.queryForObject("""
			INSERT INTO patch_plan (member_id, appid, raw_text, structured_at)
			VALUES (1, 283, '최초 원문', ?) RETURNING patch_plan_id
			""", Long.class, STRUCTURED_AT);
	}

	private void seedLegacyHistory() {
		long plan = jdbc.queryForObject("""
			INSERT INTO patch_plan (member_id, appid, raw_text, created_at)
			VALUES (1, 283, '기존 원문', ?) RETURNING patch_plan_id
			""", Long.class, SEARCHED_AT);
		long entity = jdbc.queryForObject("""
			INSERT INTO patch_plan_entity (patch_plan_id, entity_order, name, role, warning_code, warning_message)
			VALUES (?, 1, '대상', 'UNKNOWN', 'UNKNOWN_ENTITY', '기존 경고') RETURNING patch_plan_entity_id
			""", Long.class, plan);
		jdbc.update("""
			INSERT INTO patch_plan_slot (patch_plan_entity_id, slot_order, attribute, change_type, direction)
			VALUES (?, 1, '체력', 'MODIFY', 'INCREASE')
			""", entity);
		jdbc.update("INSERT INTO patch_plan_restatement (patch_plan_id, text, created_at) VALUES (?, '기존 해석', ?)", plan, SEARCHED_AT);
		jdbc.update("INSERT INTO patch_plan_genre VALUES (?, 9)", plan);
		confirmed(plan);
	}

	private void confirmed(long plan) {
		jdbc.update("""
			INSERT INTO patch_plan_confirmed_slot
			(patch_plan_id, slot_order, target_name, target_role, attribute, change_type, direction, created_at)
			VALUES (?, 1, '대상', 'ENEMY', '체력', 'MODIFY', 'INCREASE', ?)
			""", plan, SEARCHED_AT);
	}
}
