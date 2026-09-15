package com.ssafy.thispatch.domain.member.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.ssafy.thispatch.domain.member.dto.SteamTokenRequest;
import com.ssafy.thispatch.domain.member.dto.SteamTokenResponse;
import com.ssafy.thispatch.domain.member.service.SteamLoginService;
import com.ssafy.thispatch.domain.member.service.SteamTokenService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class SteamAuthController {

	private final SteamLoginService steamLoginService;
	private final SteamTokenService steamTokenService;

	@GetMapping("/auth/steam/login")
	public ResponseEntity<Void> login() {
		return ResponseEntity.status(HttpStatus.FOUND)
			.location(steamLoginService.createLoginUrl()).build();
	}

	@PostMapping("/auth/steam/token")
	public SteamTokenResponse token(@Valid @RequestBody SteamTokenRequest request) {
		return steamTokenService.exchange(request.loginCode());
	}
}
