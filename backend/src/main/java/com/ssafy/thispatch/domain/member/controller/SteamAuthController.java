package com.ssafy.thispatch.domain.member.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ssafy.thispatch.domain.member.service.SteamLoginService;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class SteamAuthController {

	private final SteamLoginService steamLoginService;

	@GetMapping("/auth/steam/login")
	public ResponseEntity<Void> login() {
		return ResponseEntity.status(HttpStatus.FOUND)
			.location(steamLoginService.createLoginUrl()).build();
	}
}