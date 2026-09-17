package com.ssafy.thispatch.domain.statistics.controller;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ssafy.thispatch.domain.statistics.dto.response.AnalysisResponse;
import com.ssafy.thispatch.domain.statistics.service.ReactionSummaryService;
import com.ssafy.thispatch.domain.statistics.service.ReactionSummaryService.ReactionSummary;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class ReactionSummaryController {

	private final ReactionSummaryService service;

	@GetMapping("/games/{gameId}/summaries/reaction-trends")
	public AnalysisResponse<ReactionSummary> getSummary(@PathVariable("gameId") long gameId,
		@RequestParam("startDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
		@RequestParam("endDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
		return AnalysisResponse.success(service.getSummary(gameId, startDate, endDate));
	}
}
