package com.ssafy.thispatch.domain.game.repository;

import org.springframework.stereotype.Repository;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class MyGameRegistrationRepository {

	private final EntityManager entityManager;

	// 등록 트랜잭션이 끝날 때까지 게임 삭제·식별자 변경을 막는다. 등록끼리는 공유할 수 있는 잠금이다.
	public boolean lockExistingGame(long gameId) {
		return !entityManager.createNativeQuery("select appid from game where appid = :gameId for key share")
			.setParameter("gameId", gameId)
			.getResultList().isEmpty();
	}

	// 중복 기본키만 무시한다. 기존 등록 시각을 보존하고 그 밖의 DB 오류는 호출자에게 전달한다.
	public int insertIfAbsent(long memberId, long gameId) {
		return entityManager.createNativeQuery("""
			insert into my_game (member_id, appid, created_at)
			values (:memberId, :gameId, current_timestamp)
			on conflict on constraint pk_my_game do nothing
			""")
			.setParameter("memberId", memberId)
			.setParameter("gameId", gameId)
			.executeUpdate();
	}
}
