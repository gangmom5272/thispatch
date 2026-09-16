package com.ssafy.thispatch.domain.member.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ssafy.thispatch.domain.member.dto.WithdrawalResponse;
import com.ssafy.thispatch.domain.member.service.WithdrawalService;
import com.ssafy.thispatch.global.security.MemberPrincipal;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class WithdrawalController {

	private final WithdrawalService withdrawalService;

	@DeleteMapping("/members/me")
	public WithdrawalResponse withdraw(@AuthenticationPrincipal MemberPrincipal principal) {
		return withdrawalService.withdraw(principal);
	}
}
