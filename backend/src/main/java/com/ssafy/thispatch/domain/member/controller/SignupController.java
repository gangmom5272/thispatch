package com.ssafy.thispatch.domain.member.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.ssafy.thispatch.domain.member.dto.SignupRequest;
import com.ssafy.thispatch.domain.member.dto.SignupResponse;
import com.ssafy.thispatch.domain.member.service.SignupService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class SignupController {

	private final SignupService signupService;

	@PostMapping("/auth/signup")
	public SignupResponse signup(@Valid @RequestBody SignupRequest request) {
		return signupService.signup(request.email(), request.password(), request.nickname());
	}
}
