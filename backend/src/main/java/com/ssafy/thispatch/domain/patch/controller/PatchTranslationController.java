package com.ssafy.thispatch.domain.patch.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.ssafy.thispatch.domain.patch.dto.response.PatchTranslation;
import com.ssafy.thispatch.domain.patch.service.PatchTranslationService;
import com.ssafy.thispatch.domain.statistics.dto.response.AnalysisResponse;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class PatchTranslationController {

	private final PatchTranslationService service;

	@GetMapping("/patches/{patchId}/translation")
	public AnalysisResponse<PatchTranslation> translate(@PathVariable("patchId") String patchId) {
		return AnalysisResponse.success(service.translate(patchId));
	}
}
