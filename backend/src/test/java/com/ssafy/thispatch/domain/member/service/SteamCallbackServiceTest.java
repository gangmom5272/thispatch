package com.ssafy.thispatch.domain.member.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigInteger;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.LinkedMultiValueMap;

import com.ssafy.thispatch.client.steam.SteamOpenIdClient;
import com.ssafy.thispatch.domain.member.entity.LoginType;
import com.ssafy.thispatch.domain.member.entity.Member;
import com.ssafy.thispatch.global.config.AppProperties;

class SteamCallbackServiceTest {

	private final SteamOpenIdClient client = mock(SteamOpenIdClient.class);
	private final SteamMemberService members = mock(SteamMemberService.class);
	private final SteamLoginCodeService codes = mock(SteamLoginCodeService.class);
	private final BigInteger steamId = new BigInteger("76561198000000001");
	private final LinkedMultiValueMap<String, String> parameters = new LinkedMultiValueMap<>();
	private final SteamCallbackService service = new SteamCallbackService(client, members, codes,
		new AppProperties(URI.create("https://frontend.example/app/"), URI.create("https://backend.example/api"),
			new AppProperties.Cors(List.of(URI.create("https://frontend.example")))));

	@BeforeEach
	void setUp() {
		when(client.verify(parameters)).thenReturn(Optional.of(steamId));
	}

	@Test
	void newAndExistingMembersUseSameCodeRedirectWithoutTokens() {
		for (String nickname : new String[] {null, "existing"}) {
			when(members.findOrCreate(steamId)).thenReturn(member(LoginType.STEAM, "ACTIVE", nickname));
			when(codes.issue(42)).thenReturn("a+/=&?");
			assertThat(service.callback(parameters).toASCIIString())
				.isEqualTo("https://frontend.example/app/auth/steam/callback?loginCode=a%2B%2F%3D%26%3F");
		}
		var order = inOrder(members, codes);
		order.verify(members).findOrCreate(steamId);
		order.verify(codes).issue(42);
		order.verify(members).findOrCreate(steamId);
		order.verify(codes).issue(42);
	}

	@Test
	void invalidAuthenticationNeverTouchesMembersOrCodes() {
		when(client.verify(parameters)).thenReturn(Optional.empty());
		assertThat(service.callback(parameters).toString())
			.isEqualTo("https://frontend.example/app/login?error=STEAM_AUTH_FAILED");
		verifyNoInteractions(members, codes);
	}

	@Test
	void rejectsInactiveAndNonSteamMembersWithoutIssuingCode() {
		for (String status : List.of("WITHDRAWN", "INACTIVE", "UNKNOWN")) {
			when(members.findOrCreate(steamId)).thenReturn(member(LoginType.STEAM, status, null));
			assertThat(service.callback(parameters).toString()).endsWith("/login?error=STEAM_AUTH_FAILED");
		}
		when(members.findOrCreate(steamId)).thenReturn(member(LoginType.LOCAL, "ACTIVE", null));
		assertThat(service.callback(parameters).toString()).endsWith("/login?error=STEAM_AUTH_FAILED");
		verifyNoInteractions(codes);
	}

	@Test
	void propagatesDatabaseAndRedisFailuresToCommonHandler() {
		var dbFailure = new IllegalStateException("database unavailable");
		when(members.findOrCreate(steamId)).thenThrow(dbFailure);
		assertThatThrownBy(() -> service.callback(parameters)).isSameAs(dbFailure);
		verifyNoInteractions(codes);

		doReturn(member(LoginType.STEAM, "ACTIVE", null)).when(members).findOrCreate(steamId);
		var redisFailure = new IllegalStateException("redis unavailable");
		when(codes.issue(42)).thenThrow(redisFailure);
		assertThatThrownBy(() -> service.callback(parameters)).isSameAs(redisFailure);
	}

	private Member member(LoginType type, String status, String nickname) {
		var member = Member.builder().loginType(type).steamId(steamId).status(status).nickname(nickname)
			.createdAt(Instant.now()).build();
		ReflectionTestUtils.setField(member, "memberId", 42L);
		return member;
	}
}
