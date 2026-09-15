package com.ssafy.thispatch.domain.member.repository;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ssafy.thispatch.domain.member.entity.Member;

public interface MemberRepository extends JpaRepository<Member, Long> {

	Optional<Member> findByEmail(String email);

	Optional<Member> findBySteamId(BigInteger steamId);

	boolean existsByEmail(String email);

	// 조회 후 저장 사이의 경합에서도 닉네임을 덮어쓰거나 비활성 회원을 갱신하지 않는다.
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
		update Member m set m.nickname = :nickname, m.updatedAt = :updatedAt
		where m.memberId = :memberId and m.status = 'ACTIVE' and m.nickname is null
		""")
	int setNicknameIfUnset(@Param("memberId") long memberId, @Param("nickname") String nickname,
		@Param("updatedAt") Instant updatedAt);

	// 호출 서비스의 트랜잭션 안에서 실행한다. 신규 회원 INSERT를 먼저 반영하고 조회 캐시를 비운다.
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
		update Member m set m.refreshTokenHash = :tokenHash, m.refreshTokenExpiresAt = :expiresAt
		where m.memberId = :memberId
		""")
	int updateRefreshToken(@Param("memberId") long memberId, @Param("tokenHash") String tokenHash,
		@Param("expiresAt") Instant expiresAt);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
		update Member m set m.refreshTokenHash = null, m.refreshTokenExpiresAt = null
		where m.memberId = :memberId and m.refreshTokenHash = :tokenHash
		""")
	int clearRefreshToken(@Param("memberId") long memberId, @Param("tokenHash") String tokenHash);
}
