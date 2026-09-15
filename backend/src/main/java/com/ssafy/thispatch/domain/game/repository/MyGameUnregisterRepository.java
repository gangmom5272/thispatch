package com.ssafy.thispatch.domain.game.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class MyGameUnregisterRepository {

	private final JdbcTemplate jdbcTemplate;

	public boolean gameExists(long gameId) {
		return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
			"select exists (select 1 from game where appid = ?)", Boolean.class, gameId));
	}

	// 선행 조회 결과 대신 조건부 DELETE의 행 수로 동시 해제 여부를 판정한다.
	public int deleteRegistration(long memberId, long gameId) {
		return jdbcTemplate.update("delete from my_game where member_id = ? and appid = ?", memberId, gameId);
	}
}
