package com.ssafy.thispatch.domain.patch.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ssafy.thispatch.domain.patch.dto.response.PatchPlanListData;
import com.ssafy.thispatch.domain.patch.service.PatchPlanListService;
import com.ssafy.thispatch.domain.statistics.dto.response.AnalysisResponse;
import com.ssafy.thispatch.global.security.MemberPrincipal;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class PatchPlanListController {

	private final PatchPlanListService service;

	@GetMapping("/members/me/patch-plans")
	public AnalysisResponse<PatchPlanListData> getPlans(@AuthenticationPrincipal MemberPrincipal principal,
		@RequestParam(name = "gameId", required = false)
		@Positive(message = "gameId는 양수여야 합니다.") Long gameId,
		@RequestParam(name = "limit", defaultValue = "10")
		@Min(value = 1, message = "limit은 1 이상 100 이하여야 합니다.")
		@Max(value = 100, message = "limit은 1 이상 100 이하여야 합니다.") int limit,
		@RequestParam(name = "cursor", required = false) String cursor) {
		return AnalysisResponse.success(service.getPlans(principal.memberId(), gameId, limit, cursor));
	}
}
