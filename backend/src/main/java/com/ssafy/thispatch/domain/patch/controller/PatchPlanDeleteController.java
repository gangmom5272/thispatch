package com.ssafy.thispatch.domain.patch.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.ssafy.thispatch.domain.patch.dto.response.PatchPlanDeleteResponse;
import com.ssafy.thispatch.domain.patch.service.PatchPlanDeleteService;
import com.ssafy.thispatch.global.security.MemberPrincipal;

import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class PatchPlanDeleteController {

	private final PatchPlanDeleteService service;

	@DeleteMapping("/members/me/patch-plans/{planId}")
	public PatchPlanDeleteResponse deletePlan(@AuthenticationPrincipal MemberPrincipal principal,
		@PathVariable("planId") @Min(value = 1, message = "planId는 1 이상이어야 합니다.") long planId) {
		service.deletePlan(principal.memberId(), planId);
		return PatchPlanDeleteResponse.successResponse();
	}
}
