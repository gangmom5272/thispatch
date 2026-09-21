package com.ssafy.thispatch.domain.patch.controller;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import com.ssafy.thispatch.domain.patch.dto.request.CaseSearchRequest;
import com.ssafy.thispatch.domain.patch.dto.request.PlanStructureRequest;
import com.ssafy.thispatch.domain.patch.dto.response.CaseSearchResponse;
import com.ssafy.thispatch.domain.patch.dto.response.PlanStructureResponse;
import com.ssafy.thispatch.domain.patch.service.CaseSearchService;
import com.ssafy.thispatch.domain.patch.service.PlanStructureService;
import com.ssafy.thispatch.global.security.MemberPrincipal;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class PatchPlanningController {
	private final PlanStructureService planStructureService;
	private final CaseSearchService caseSearchService;

	@PostMapping("/games/{gameId}/plan-structures")
	public PlanStructureResponse structure(@AuthenticationPrincipal MemberPrincipal principal,
		@PathVariable("gameId") long gameId,
		@Valid @RequestBody PlanStructureRequest request) {
		return planStructureService.structure(principal.memberId(), gameId, request);
	}

	@PostMapping("/games/{gameId}/case-searches")
	@ResponseStatus(HttpStatus.CREATED)
	public CaseSearchResponse search(@PathVariable("gameId") long gameId,
		@Valid @RequestBody CaseSearchRequest request) {
		return caseSearchService.search(gameId, request);
	}
}
