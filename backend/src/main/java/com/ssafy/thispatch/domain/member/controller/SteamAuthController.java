package com.ssafy.thispatch.domain.member.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;

import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.ssafy.thispatch.domain.member.dto.SteamTokenRequest;
import com.ssafy.thispatch.domain.member.dto.SteamTokenResponse;

import com.ssafy.thispatch.domain.member.service.SteamCallbackService;
import com.ssafy.thispatch.domain.member.service.SteamLoginService;
import com.ssafy.thispatch.domain.member.service.SteamTokenService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class SteamAuthController {

	private final SteamLoginService steamLoginService;
	private final SteamCallbackService steamCallbackService;
	private final SteamTokenService steamTokenService;

	@GetMapping("/auth/steam/login")
	public ResponseEntity<Void> login() {
		return ResponseEntity.status(HttpStatus.FOUND)
			.location(steamLoginService.createLoginUrl()).build();
	}

	@GetMapping("/auth/steam/callback")
	public ResponseEntity<Void> callback(@RequestParam MultiValueMap<String, String> parameters) {
		return ResponseEntity.status(HttpStatus.FOUND)
			.location(steamCallbackService.callback(parameters)).build();
	}

	@PostMapping("/auth/steam/token")
	public SteamTokenResponse token(@Valid @RequestBody SteamTokenRequest request) {
		return steamTokenService.exchange(request.loginCode());
	}
}
