package com.ssafy.thispatch.domain.member.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.ssafy.thispatch.domain.member.dto.TokenRefreshRequest;
import com.ssafy.thispatch.domain.member.dto.TokenRefreshResponse;
import com.ssafy.thispatch.domain.member.service.TokenRefreshService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class TokenRefreshController {

	private final TokenRefreshService tokenRefreshService;

	@PostMapping("/auth/refresh")
	public TokenRefreshResponse refresh(@Valid @RequestBody TokenRefreshRequest request) {
		return tokenRefreshService.refresh(request.refreshToken());
	}
}
