package com.ssafy.thispatch.domain.member.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.ssafy.thispatch.domain.member.dto.NicknameChangeResponse;
import com.ssafy.thispatch.domain.member.dto.NicknameRequest;
import com.ssafy.thispatch.domain.member.service.NicknameChangeService;
import com.ssafy.thispatch.global.security.MemberPrincipal;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class NicknameChangeController {

	private final NicknameChangeService nicknameChangeService;

	@PatchMapping("/members/me/nickname")
	public NicknameChangeResponse changeNickname(@AuthenticationPrincipal MemberPrincipal principal,
		@Valid @RequestBody NicknameRequest request) {
		return nicknameChangeService.changeNickname(principal, request.nickname());
	}
}
