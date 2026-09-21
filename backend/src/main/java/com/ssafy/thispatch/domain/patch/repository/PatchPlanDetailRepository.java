package com.ssafy.thispatch.domain.patch.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.ssafy.thispatch.domain.patch.dto.PatchChangeCodes.ChangeType;
import com.ssafy.thispatch.domain.patch.dto.PatchChangeCodes.Direction;
import com.ssafy.thispatch.domain.patch.dto.PatchChangeCodes.TargetRole;
import com.ssafy.thispatch.domain.patch.dto.request.CaseSearchRequest.ConfirmedSlot;
import com.ssafy.thispatch.domain.patch.dto.request.CaseSearchRequest.Target;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class PatchPlanDetailRepository {

	private final NamedParameterJdbcTemplate jdbc;

	public Optional<PlanRow> findOwnedPlan(long memberId, long planId) {
		return jdbc.query("""
			SELECT p.patch_plan_id, p.appid, g.name, p.raw_text, p.created_at,
				COALESCE(r.text, '') AS restatement
			FROM patch_plan p
			JOIN game g ON g.appid = p.appid
			LEFT JOIN patch_plan_restatement r ON r.patch_plan_id = p.patch_plan_id
			WHERE p.member_id = :memberId AND p.patch_plan_id = :planId
			""", Map.of("memberId", memberId, "planId", planId),
			(row, index) -> new PlanRow(row.getLong("patch_plan_id"), row.getLong("appid"), row.getString("name"),
				row.getString("raw_text"), row.getString("restatement"),
				row.getObject("created_at", OffsetDateTime.class))).stream().findFirst();
	}

	public List<Integer> findGenreIds(long planId) {
		return jdbc.queryForList("""
			SELECT genre_id FROM patch_plan_genre WHERE patch_plan_id = :planId ORDER BY genre_id
			""", Map.of("planId", planId), Integer.class);
	}

	public List<ConfirmedSlot> findConfirmedSlots(long planId) {
		return jdbc.query("""
			SELECT target_name, target_role, attribute, change_type, direction, magnitude, scope
			FROM patch_plan_confirmed_slot WHERE patch_plan_id = :planId ORDER BY slot_order
			""", Map.of("planId", planId), (row, index) -> new ConfirmedSlot(
				new Target(row.getString("target_name"), TargetRole.valueOf(row.getString("target_role"))),
				row.getString("attribute"), ChangeType.valueOf(row.getString("change_type")),
				Direction.valueOf(row.getString("direction")), row.getString("magnitude"), row.getString("scope")));
	}

	public record PlanRow(long planId, long gameId, String gameTitle, String rawText,
		String restatement, OffsetDateTime createdAt) {
	}
}
