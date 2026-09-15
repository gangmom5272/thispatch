package com.ssafy.thispatch.domain.member.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.ssafy.thispatch.domain.member.dto.LogoutRequest;
import com.ssafy.thispatch.domain.member.dto.LogoutResponse;
import com.ssafy.thispatch.domain.member.service.LogoutService;
import com.ssafy.thispatch.global.security.MemberPrincipal;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class LogoutController {

	private final LogoutService logoutService;

	@PostMapping("/auth/logout")
	public LogoutResponse logout(@AuthenticationPrincipal MemberPrincipal principal,
		@Valid @RequestBody LogoutRequest request) {
		return logoutService.logout(principal, request.refreshToken());
	}
}
