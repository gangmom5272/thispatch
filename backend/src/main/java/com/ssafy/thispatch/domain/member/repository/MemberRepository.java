package com.ssafy.thispatch.domain.member.repository;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ssafy.thispatch.domain.member.entity.Member;

import jakarta.persistence.LockModeType;

public interface MemberRepository extends JpaRepository<Member, Long> {

	Optional<Member> findByEmail(String email);

	Optional<Member> findBySteamId(BigInteger steamId);

	boolean existsByEmail(String email);

	boolean existsByMemberIdAndStatus(long memberId, String status);

	// 비밀번호 검증부터 변경까지 직렬화해 동시 요청이 이전 비밀번호로 덮어쓰지 못하게 한다.
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select m from Member m where m.memberId = :memberId")
	Optional<Member> findByIdForPasswordChange(@Param("memberId") long memberId);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
		update Member m set m.password = :passwordHash, m.updatedAt = :updatedAt,
		m.refreshTokenHash = null, m.refreshTokenExpiresAt = null
		where m.memberId = :memberId and m.status = 'ACTIVE'
		""")
	int changePasswordAndClearRefreshToken(@Param("memberId") long memberId,
		@Param("passwordHash") String passwordHash, @Param("updatedAt") Instant updatedAt);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
		update Member m set m.status = 'WITHDRAWN', m.updatedAt = :updatedAt,
		m.refreshTokenHash = null, m.refreshTokenExpiresAt = null
		where m.memberId = :memberId and m.status = 'ACTIVE'
		""")
	int withdrawIfActive(@Param("memberId") long memberId, @Param("updatedAt") Instant updatedAt);

	// 이메일 충돌만 무시한다. 회원 생성과 토큰 저장은 호출 서비스의 같은 트랜잭션에 참여한다.
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = """
		insert into member (login_type, email, password, nickname, status, created_at)
		values ('LOCAL', :email, :passwordHash, :nickname, 'ACTIVE', current_timestamp)
		on conflict on constraint uk_member_email do nothing
		""", nativeQuery = true)
	int insertLocalMemberIfAbsent(@Param("email") String email, @Param("passwordHash") String passwordHash,
		@Param("nickname") String nickname);

	// 조회 후 저장 사이의 경합에서도 닉네임을 덮어쓰거나 비활성 회원을 갱신하지 않는다.
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
		update Member m set m.nickname = :nickname, m.updatedAt = :updatedAt
		where m.memberId = :memberId and m.status = 'ACTIVE' and m.nickname is null
		""")
	int setNicknameIfUnset(@Param("memberId") long memberId, @Param("nickname") String nickname,
		@Param("updatedAt") Instant updatedAt);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
		update Member m set m.nickname = :nickname, m.updatedAt = :updatedAt
		where m.memberId = :memberId and m.status = 'ACTIVE'
		""")
	int changeNicknameIfActive(@Param("memberId") long memberId, @Param("nickname") String nickname,
		@Param("updatedAt") Instant updatedAt);

	// PostgreSQL의 UNIQUE 충돌을 문장 수준에서 처리해 트랜잭션이 rollback-only가 되지 않게 한다.
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = """
		insert into member (login_type, steam_id, status, created_at)
		values ('STEAM', :steamId, 'ACTIVE', current_timestamp)
		on conflict (steam_id) do nothing
		""", nativeQuery = true)
	int insertSteamMemberIfAbsent(@Param("steamId") BigInteger steamId);

	// 호출 서비스의 트랜잭션 안에서 실행한다. 신규 회원 INSERT를 먼저 반영하고 조회 캐시를 비운다.
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
		update Member m set m.refreshTokenHash = :tokenHash, m.refreshTokenExpiresAt = :expiresAt
		where m.memberId = :memberId and m.status = 'ACTIVE'
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
