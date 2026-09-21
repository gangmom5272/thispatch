package com.ssafy.thispatch.domain.member.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.ssafy.thispatch.domain.member.dto.PasswordChangeRequest;
import com.ssafy.thispatch.domain.member.dto.PasswordChangeResponse;
import com.ssafy.thispatch.domain.member.service.PasswordChangeService;
import com.ssafy.thispatch.global.security.MemberPrincipal;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class PasswordChangeController {

	private final PasswordChangeService passwordChangeService;

	@PatchMapping("/members/me/password")
	public PasswordChangeResponse changePassword(@AuthenticationPrincipal MemberPrincipal principal,
		@Valid @RequestBody PasswordChangeRequest request) throws BindException {
		return passwordChangeService.changePassword(principal, request);
	}
}
