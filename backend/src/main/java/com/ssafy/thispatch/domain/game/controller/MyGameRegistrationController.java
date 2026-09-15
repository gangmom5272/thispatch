package com.ssafy.thispatch.domain.game.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ssafy.thispatch.domain.game.dto.MyGameRegistrationResponse;
import com.ssafy.thispatch.domain.game.service.MyGameRegistrationService;
import com.ssafy.thispatch.global.security.MemberPrincipal;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class MyGameRegistrationController {

	private final MyGameRegistrationService registrationService;

	@PostMapping("/games/{gameId}/my-game")
	public MyGameRegistrationResponse register(@AuthenticationPrincipal MemberPrincipal principal,
		@PathVariable("gameId") long gameId) {
		return registrationService.register(principal, gameId);
	}
}
