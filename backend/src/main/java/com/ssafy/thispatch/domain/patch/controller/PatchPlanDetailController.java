package com.ssafy.thispatch.domain.patch.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.ssafy.thispatch.domain.patch.dto.response.PatchPlanDetailData;
import com.ssafy.thispatch.domain.patch.service.PatchPlanDetailService;
import com.ssafy.thispatch.domain.statistics.dto.response.AnalysisResponse;
import com.ssafy.thispatch.global.security.MemberPrincipal;

import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class PatchPlanDetailController {

	private final PatchPlanDetailService service;

	@GetMapping("/members/me/patch-plans/{planId}")
	public AnalysisResponse<PatchPlanDetailData> getPlan(@AuthenticationPrincipal MemberPrincipal principal,
		@PathVariable("planId") @Min(value = 1, message = "planId는 1 이상이어야 합니다.") long planId) {
		return AnalysisResponse.success(service.getPlan(principal.memberId(), planId));
	}
}
