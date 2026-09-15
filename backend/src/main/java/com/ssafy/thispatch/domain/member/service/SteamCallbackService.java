package com.ssafy.thispatch.domain.member.service;

import java.net.URI;

import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

import com.ssafy.thispatch.client.steam.SteamOpenIdClient;
import com.ssafy.thispatch.domain.member.entity.LoginType;
import com.ssafy.thispatch.global.config.AppProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class SteamCallbackService {

	private final SteamOpenIdClient openIdClient;
	private final SteamMemberService steamMemberService;
	private final SteamLoginCodeService loginCodeService;
	private final AppProperties appProperties;

	public URI callback(MultiValueMap<String, String> parameters) {
		var steamId = openIdClient.verify(parameters);
		if (steamId.isEmpty()) {
			return failureUrl();
		}
		var member = steamMemberService.findOrCreate(steamId.get());
		if (member.getLoginType() != LoginType.STEAM || !"ACTIVE".equals(member.getStatus())) {
			log.warn("Steam callback rejected: member is not eligible for Steam login");
			return failureUrl();
		}
		// findOrCreate의 DB 커밋 후 발급. Redis 실패 시 회원을 삭제하거나 복구하지 않는다.
		String loginCode = loginCodeService.issue(member.getMemberId());
		return UriComponentsBuilder.fromUriString(frontendBase() + "/auth/steam/callback")
			.queryParam("loginCode", "{loginCode}").encode().buildAndExpand(loginCode).toUri();
	}

	private URI failureUrl() {
		return URI.create(frontendBase() + "/login?error=STEAM_AUTH_FAILED");
	}

	private String frontendBase() {
		return appProperties.frontendBaseUrl().toASCIIString().replaceFirst("/+$", "");
	}
}
