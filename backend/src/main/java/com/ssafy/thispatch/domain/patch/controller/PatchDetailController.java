package com.ssafy.thispatch.domain.patch.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.ssafy.thispatch.domain.patch.dto.response.PatchDetailResponse;
import com.ssafy.thispatch.domain.patch.service.PatchDetailService;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class PatchDetailController {

	private final PatchDetailService service;

	@GetMapping("/games/{gameId}/patches/{patchId}")
	public PatchDetailResponse getPatch(@PathVariable("gameId") long gameId,
		@PathVariable("patchId") String patchId) {
		return service.getPatch(gameId, patchId);
	}
}
