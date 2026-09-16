package com.ssafy.thispatch.domain.game.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.ssafy.thispatch.domain.game.dto.response.GameDetailResponse;
import com.ssafy.thispatch.domain.game.service.GameDetailService;
import com.ssafy.thispatch.global.security.MemberPrincipal;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class GameDetailController {

	private final GameDetailService gameDetailService;

	@GetMapping("/games/{gameId}")
	public GameDetailResponse getGame(@AuthenticationPrincipal MemberPrincipal principal,
		@PathVariable("gameId") long gameId) {
		return gameDetailService.getGame(principal.memberId(), gameId);
	}
}
