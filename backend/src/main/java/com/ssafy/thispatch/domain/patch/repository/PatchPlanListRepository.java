package com.ssafy.thispatch.domain.patch.repository;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.ssafy.thispatch.domain.patch.service.PatchPlanListCursorCodec.Boundary;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class PatchPlanListRepository {

	private final NamedParameterJdbcTemplate jdbc;

	public long count(long memberId, Long gameId) {
		return jdbc.queryForObject("SELECT count(*) FROM patch_plan WHERE " + filter(gameId),
			parameters(memberId, gameId), Long.class);
	}

	public List<PlanRow> findPage(long memberId, Long gameId, int limit, Boundary boundary) {
		var parameters = parameters(memberId, gameId).addValue("fetchLimit", limit + 1);
		String after = "";
		if (boundary != null) {
			after = " AND (created_at, patch_plan_id) < (:createdAt, :planId)";
			parameters.addValue("createdAt", boundary.createdAt()).addValue("planId", boundary.planId());
		}
		// 페이지를 먼저 확정하여 슬롯 수와 관계없이 내역 단위로 페이지네이션한다.
		String sql = """
			WITH page AS (
				SELECT patch_plan_id, appid, left(raw_text, 200) AS preview, created_at
				FROM patch_plan WHERE %s%s
				ORDER BY created_at DESC, patch_plan_id DESC LIMIT :fetchLimit
			)
			SELECT p.*, g.name, slots.slot_count, slots.unknown_entity_count
			FROM page p JOIN game g ON g.appid = p.appid
			CROSS JOIN LATERAL (
				SELECT count(*) AS slot_count,
					count(DISTINCT target_name) FILTER (WHERE target_role = 'UNKNOWN') AS unknown_entity_count
				FROM patch_plan_confirmed_slot WHERE patch_plan_id = p.patch_plan_id
			) slots
			ORDER BY p.created_at DESC, p.patch_plan_id DESC
			""".formatted(filter(gameId), after);
		return jdbc.query(sql, parameters, (row, index) -> new PlanRow(row.getLong("patch_plan_id"),
			row.getLong("appid"), row.getString("name"), row.getString("preview"), row.getInt("slot_count"),
			row.getInt("unknown_entity_count"), row.getObject("created_at", OffsetDateTime.class)));
	}

	private String filter(Long gameId) {
		return "member_id = :memberId AND created_at IS NOT NULL"
			+ (gameId == null ? "" : " AND appid = :gameId");
	}

	private MapSqlParameterSource parameters(long memberId, Long gameId) {
		return new MapSqlParameterSource("memberId", memberId).addValue("gameId", gameId);
	}

	public record PlanRow(long planId, long gameId, String gameTitle, String rawTextPreview,
		int slotCount, int unknownEntityCount, OffsetDateTime createdAt) {
	}
}
