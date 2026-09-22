package com.ssafy.thispatch.domain.patch.repository;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.ssafy.thispatch.domain.patch.dto.request.CaseSearchRequest.ConfirmedSlot;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class CaseSearchStorageRepository {

	private final NamedParameterJdbcTemplate jdbc;

	public Optional<PlanState> findOwnedPlan(long memberId, long planId) {
		return findOwnedPlan(memberId, planId, false);
	}

	public Optional<PlanState> lockOwnedPlan(long memberId, long planId) {
		return findOwnedPlan(memberId, planId, true);
	}

	private Optional<PlanState> findOwnedPlan(long memberId, long planId, boolean lock) {
		return jdbc.query("""
			SELECT appid, created_at IS NOT NULL AS completed FROM patch_plan
			WHERE patch_plan_id = :planId AND member_id = :memberId
			""" + (lock ? " FOR UPDATE" : ""),
			new MapSqlParameterSource("planId", planId).addValue("memberId", memberId),
			(rs, row) -> new PlanState(rs.getLong("appid"), rs.getBoolean("completed"))).stream().findFirst();
	}

	public long copyPlan(long planId, OffsetDateTime storedAt) {
		return jdbc.queryForObject("""
			INSERT INTO patch_plan (member_id, appid, raw_text, structured_at, created_at)
			SELECT member_id, appid, raw_text, structured_at, :storedAt FROM patch_plan WHERE patch_plan_id = :planId
			RETURNING patch_plan_id
			""", new MapSqlParameterSource("planId", planId).addValue("storedAt", storedAt), Long.class);
	}

	public void copyStructure(long sourcePlanId, long targetPlanId) {
		var parameters = new MapSqlParameterSource("sourcePlanId", sourcePlanId).addValue("targetPlanId", targetPlanId);
		jdbc.update("""
			INSERT INTO patch_plan_entity (patch_plan_id, entity_order, name, role, warning_code, warning_message)
			SELECT :targetPlanId, entity_order, name, role, warning_code, warning_message
			FROM patch_plan_entity WHERE patch_plan_id = :sourcePlanId
			""", parameters);
		// 기획안별 entity_order UNIQUE를 이용해 새 엔티티 PK에 연결하고 전체 슬롯 순서를 보존한다.
		jdbc.update("""
			INSERT INTO patch_plan_slot (patch_plan_entity_id, slot_order, attribute, change_type, direction, magnitude, scope)
			SELECT target.patch_plan_entity_id, s.slot_order, s.attribute, s.change_type, s.direction, s.magnitude, s.scope
			FROM patch_plan_slot s
			JOIN patch_plan_entity source ON source.patch_plan_entity_id = s.patch_plan_entity_id
			JOIN patch_plan_entity target ON target.patch_plan_id = :targetPlanId AND target.entity_order = source.entity_order
			WHERE source.patch_plan_id = :sourcePlanId
			""", parameters);
		jdbc.update("""
			INSERT INTO patch_plan_restatement (patch_plan_id, text, warning_code, warning_message, created_at)
			SELECT :targetPlanId, text, warning_code, warning_message, created_at
			FROM patch_plan_restatement WHERE patch_plan_id = :sourcePlanId
			""", parameters);
	}

	public void insertGenre(long planId, int genreId) {
		jdbc.update("INSERT INTO patch_plan_genre (patch_plan_id, genre_id) VALUES (:planId, :genreId)",
			new MapSqlParameterSource("planId", planId).addValue("genreId", genreId));
	}

	public void insertConfirmedSlot(long planId, int order, ConfirmedSlot slot, OffsetDateTime storedAt) {
		jdbc.update("""
			INSERT INTO patch_plan_confirmed_slot (patch_plan_id, slot_order, target_name, target_role, attribute,
				change_type, direction, magnitude, scope, created_at)
			VALUES (:planId, :order, :name, :role, :attribute, :changeType, :direction, :magnitude, :scope, :storedAt)
			""", new MapSqlParameterSource("planId", planId).addValue("order", order)
				.addValue("name", slot.target().name()).addValue("role", slot.target().role().name())
				.addValue("attribute", slot.attribute()).addValue("changeType", slot.changeType().name())
				.addValue("direction", slot.direction().name()).addValue("magnitude", slot.magnitude())
				.addValue("scope", slot.scope()).addValue("storedAt", storedAt));
	}

	public void completePlan(long planId, OffsetDateTime storedAt) {
		jdbc.update("UPDATE patch_plan SET created_at = :storedAt WHERE patch_plan_id = :planId",
			new MapSqlParameterSource("planId", planId).addValue("storedAt", storedAt));
	}

	public record PlanState(long gameId, boolean completed) {
	}
}
