package com.ssafy.thispatch.domain.member.service;

import java.net.URI;

import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import com.ssafy.thispatch.global.config.AppProperties;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SteamLoginService {

	private static final String STEAM_LOGIN_URL = "https://steamcommunity.com/openid/login";
	private static final String OPENID_NAMESPACE = "http://specs.openid.net/auth/2.0";
	private static final String IDENTIFIER_SELECT = OPENID_NAMESPACE + "/identifier_select";

	private final AppProperties appProperties;

	public URI createLoginUrl() {
		String realm = appProperties.backendPublicUrl().toASCIIString();
		// 외부 /api 접두사를 보존하고 끝 슬래시만 제거하여 콜백 경로를 붙인다.
		String callbackBase = realm.replaceFirst("/+$", "");
		return UriComponentsBuilder.fromUriString(STEAM_LOGIN_URL)
			.queryParam("openid.ns", OPENID_NAMESPACE)
			.queryParam("openid.mode", "checkid_setup")
			.queryParam("openid.claimed_id", IDENTIFIER_SELECT)
			.queryParam("openid.identity", IDENTIFIER_SELECT)
			.queryParam("openid.return_to", "{returnTo}")
			.queryParam("openid.realm", "{realm}")
			.encode().buildAndExpand(callbackBase + "/auth/steam/callback", realm).toUri();
	}
}