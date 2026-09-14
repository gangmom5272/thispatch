package com.ssafy.thispatch.domain.member.repository;

import java.math.BigInteger;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ssafy.thispatch.domain.member.entity.Member;

public interface MemberRepository extends JpaRepository<Member, Long> {

	Optional<Member> findByEmail(String email);

	Optional<Member> findBySteamId(BigInteger steamId);

	boolean existsByEmail(String email);
}
