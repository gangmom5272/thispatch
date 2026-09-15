package com.ssafy.thispatch.domain.game.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.ssafy.thispatch.domain.game.dto.MyGameUnregisterResponse;
import com.ssafy.thispatch.domain.game.service.MyGameUnregisterService;
import com.ssafy.thispatch.global.security.MemberPrincipal;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class MyGameUnregisterController {

	private final MyGameUnregisterService myGameUnregisterService;

	@DeleteMapping("/games/{gameId}/my-game")
	public MyGameUnregisterResponse unregister(@AuthenticationPrincipal MemberPrincipal principal,
		@PathVariable("gameId") long gameId) {
		return myGameUnregisterService.unregister(principal, gameId);
	}
}
