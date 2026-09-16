package com.ssafy.thispatch.domain.member.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.ssafy.thispatch.domain.member.dto.SteamNicknameRequest;
import com.ssafy.thispatch.domain.member.dto.SteamNicknameResponse;
import com.ssafy.thispatch.domain.member.service.SteamNicknameService;
import com.ssafy.thispatch.global.security.MemberPrincipal;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class SteamNicknameController {

	private final SteamNicknameService steamNicknameService;

	@PostMapping("/auth/steam/signup")
	public SteamNicknameResponse signup(@AuthenticationPrincipal MemberPrincipal principal,
		@Valid @RequestBody SteamNicknameRequest request) {
		return steamNicknameService.setNickname(principal, request.nickname());
	}
}
