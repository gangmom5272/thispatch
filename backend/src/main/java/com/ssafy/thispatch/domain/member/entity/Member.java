package com.ssafy.thispatch.domain.member.entity;

import java.math.BigInteger;
import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "member")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "member_id")
	private Long memberId;

	@Enumerated(EnumType.STRING)
	@Column(name = "login_type", nullable = false, length = 10)
	private LoginType loginType;

	@Column(name = "email", length = 255)
	private String email;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "password", length = 64)
	private String password;

	@Column(name = "steam_id", precision = 20, scale = 0)
	private BigInteger steamId;

	@Column(name = "nickname", length = 50)
	private String nickname;

	// 가입 상태 ACTIVE만 확정되어 있다. 탈퇴 등 미정 상태도 DB 값 그대로 읽는다.
	@Column(name = "status", nullable = false, length = 10)
	private String status;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at")
	private Instant updatedAt;

	// 저장소의 조건부 UPDATE로만 변경한다. 오래된 Member 저장으로 폐기된 토큰이 복구되지 않게 한다.
	@Column(name = "refresh_token_hash", length = 64, insertable = false, updatable = false)
	private String refreshTokenHash;

	@Column(name = "refresh_token_expires_at", insertable = false, updatable = false)
	private Instant refreshTokenExpiresAt;

	@Builder
	public Member(LoginType loginType, String email, String password, BigInteger steamId, String nickname,
		String status, Instant createdAt, Instant updatedAt) {
		this.loginType = loginType;
		this.email = email;
		this.password = password;
		this.steamId = steamId;
		this.nickname = nickname;
		this.status = status;
		this.createdAt = createdAt;
		this.updatedAt = updatedAt;
	}

	public String getPassword() {
		// PostgreSQL CHAR(64)의 저장 패딩을 제거해 BCrypt 해시를 그대로 검증한다.
		return password == null ? null : password.stripTrailing();
	}
}
