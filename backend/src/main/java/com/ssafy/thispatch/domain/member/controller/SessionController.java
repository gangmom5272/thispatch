package com.ssafy.thispatch.domain.member.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ssafy.thispatch.domain.member.dto.SessionResponse;
import com.ssafy.thispatch.domain.member.service.SessionService;
import com.ssafy.thispatch.global.security.MemberPrincipal;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class SessionController {

	private final SessionService sessionService;

	@GetMapping("/session")
	public SessionResponse session(@AuthenticationPrincipal MemberPrincipal principal) {
		return sessionService.getSession(principal);
	}
}
