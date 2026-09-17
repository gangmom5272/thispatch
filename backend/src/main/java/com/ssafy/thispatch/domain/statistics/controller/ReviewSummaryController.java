package com.ssafy.thispatch.domain.statistics.controller;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ssafy.thispatch.domain.statistics.dto.response.AnalysisResponse;
import com.ssafy.thispatch.domain.statistics.service.ReviewSummaryService;
import com.ssafy.thispatch.domain.statistics.service.ReviewSummaryService.LanguageDetail;
import com.ssafy.thispatch.domain.statistics.service.ReviewSummaryService.PlaytimeSummary;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class ReviewSummaryController {

	private final ReviewSummaryService service;

	@GetMapping("/games/{gameId}/summaries/playtime-topics")
	public AnalysisResponse<PlaytimeSummary> playtime(@PathVariable long gameId,
		@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
		@RequestParam(required = false) Integer bandNo) {
		return AnalysisResponse.success(service.playtime(gameId, startDate, bandNo));
	}

	@GetMapping("/games/{gameId}/language-analysis/{languageCode}")
	public AnalysisResponse<LanguageDetail> language(@PathVariable long gameId, @PathVariable String languageCode) {
		return AnalysisResponse.success(service.language(gameId, languageCode));
	}
}
