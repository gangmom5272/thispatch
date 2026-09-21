package com.ssafy.thispatch.domain.patch.repository;

import java.time.OffsetDateTime;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.ssafy.thispatch.domain.patch.dto.response.PlanStructureResponse.Entity;
import com.ssafy.thispatch.domain.patch.dto.response.PlanStructureResponse.Slot;
import com.ssafy.thispatch.domain.patch.dto.response.PlanStructureResponse.Warning;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class PlanStructureStorageRepository {

	private final NamedParameterJdbcTemplate jdbc;

	public long insertPlan(long memberId, long gameId, String rawText, OffsetDateTime storedAt) {
		return jdbc.queryForObject("""
			INSERT INTO patch_plan (member_id, appid, raw_text, structured_at, created_at)
			VALUES (:memberId, :gameId, :rawText, :storedAt, NULL) RETURNING patch_plan_id
			""", new MapSqlParameterSource("memberId", memberId).addValue("gameId", gameId)
				.addValue("rawText", rawText).addValue("storedAt", storedAt), Long.class);
	}

	public long insertEntity(long planId, int order, Entity entity, Warning warning) {
		return jdbc.queryForObject("""
			INSERT INTO patch_plan_entity (patch_plan_id, entity_order, name, role, warning_code, warning_message)
			VALUES (:planId, :order, :name, :role, :warningCode, :warningMessage) RETURNING patch_plan_entity_id
			""", warningParameters(warning).addValue("planId", planId).addValue("order", order)
				.addValue("name", entity.name()).addValue("role", entity.role().name()), Long.class);
	}

	public void insertSlot(long entityId, int order, Slot slot) {
		jdbc.update("""
			INSERT INTO patch_plan_slot (patch_plan_entity_id, slot_order, attribute, change_type, direction, magnitude, scope)
			VALUES (:entityId, :order, :attribute, :changeType, :direction, :magnitude, :scope)
			""", new MapSqlParameterSource("entityId", entityId).addValue("order", order)
				.addValue("attribute", slot.attribute()).addValue("changeType", slot.changeType().name())
				.addValue("direction", slot.direction().name()).addValue("magnitude", slot.magnitude())
				.addValue("scope", slot.scope()));
	}

	public void insertRestatement(long planId, String text, Warning warning, OffsetDateTime storedAt) {
		jdbc.update("""
			INSERT INTO patch_plan_restatement (patch_plan_id, text, warning_code, warning_message, created_at)
			VALUES (:planId, :text, :warningCode, :warningMessage, :storedAt)
			""", warningParameters(warning).addValue("planId", planId).addValue("text", text)
				.addValue("storedAt", storedAt));
	}

	private MapSqlParameterSource warningParameters(Warning warning) {
		return new MapSqlParameterSource("warningCode", warning == null ? null : warning.code())
			.addValue("warningMessage", warning == null ? null : warning.message());
	}
}
