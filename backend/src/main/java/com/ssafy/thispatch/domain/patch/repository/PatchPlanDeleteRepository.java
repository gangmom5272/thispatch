package com.ssafy.thispatch.domain.patch.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class PatchPlanDeleteRepository {

	private final NamedParameterJdbcTemplate jdbc;

	public boolean lockOwnedCompletedPlan(long memberId, long planId) {
		// 재검색 저장과 같은 원본 행을 잠가 복사 도중 하위 데이터가 삭제되지 않게 한다.
		return !jdbc.query("""
			SELECT patch_plan_id FROM patch_plan
			WHERE member_id = :memberId AND patch_plan_id = :planId AND created_at IS NOT NULL
			FOR UPDATE
			""", new MapSqlParameterSource("memberId", memberId).addValue("planId", planId),
			(rs, row) -> rs.getLong("patch_plan_id")).isEmpty();
	}

	public void deletePlanAndChildren(long planId) {
		var parameters = new MapSqlParameterSource("planId", planId);
		jdbc.update("""
			DELETE FROM patch_plan_slot WHERE patch_plan_entity_id IN (
				SELECT patch_plan_entity_id FROM patch_plan_entity WHERE patch_plan_id = :planId)
			""", parameters);
		jdbc.update("DELETE FROM patch_plan_entity WHERE patch_plan_id = :planId", parameters);
		jdbc.update("DELETE FROM patch_plan_restatement WHERE patch_plan_id = :planId", parameters);
		jdbc.update("DELETE FROM patch_plan_genre WHERE patch_plan_id = :planId", parameters);
		jdbc.update("DELETE FROM patch_plan_confirmed_slot WHERE patch_plan_id = :planId", parameters);
		jdbc.update("DELETE FROM patch_plan WHERE patch_plan_id = :planId", parameters);
	}
}
