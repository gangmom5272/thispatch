package com.ssafy.thispatch.domain.statistics.controller;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ssafy.thispatch.domain.statistics.dto.response.AnalysisResponse;
import com.ssafy.thispatch.domain.statistics.dto.response.PlaytimeAnalysis;
import com.ssafy.thispatch.domain.statistics.service.LanguageAnalysisService;
import com.ssafy.thispatch.domain.statistics.service.LanguageAnalysisService.LanguageAnalysis;
import com.ssafy.thispatch.domain.statistics.service.PlaytimeAnalysisService;
import com.ssafy.thispatch.domain.statistics.service.ReactionTrendsService;
import com.ssafy.thispatch.domain.statistics.service.ReactionTrendsService.ReactionTrends;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class StatisticsController {

	private final LanguageAnalysisService languageService;
	private final ReactionTrendsService reactionService;
	private final PlaytimeAnalysisService playtimeService;

	@GetMapping("/games/{gameId}/language-analysis")
	public AnalysisResponse<LanguageAnalysis> getLanguages(@PathVariable("gameId") long gameId) {
		return AnalysisResponse.success(languageService.getAnalysis(gameId));
	}

	@GetMapping("/games/{gameId}/reaction-trends")
	public AnalysisResponse<ReactionTrends> getTrends(@PathVariable("gameId") long gameId,
		@RequestParam("startDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate) {
		return AnalysisResponse.success(reactionService.getTrends(gameId, startDate));
	}

	@GetMapping("/games/{gameId}/playtime-topics")
	public AnalysisResponse<PlaytimeAnalysis> getPlaytime(@PathVariable("gameId") long gameId,
		@RequestParam(name = "startDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
		@RequestParam(name = "bandNo", required = false) Integer bandNo) {
		return AnalysisResponse.success(playtimeService.getAnalysis(gameId, startDate, bandNo));
	}
}
