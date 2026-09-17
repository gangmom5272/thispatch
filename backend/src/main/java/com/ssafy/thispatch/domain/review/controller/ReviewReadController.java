package com.ssafy.thispatch.domain.review.controller;

import java.util.Set;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ssafy.thispatch.domain.review.service.ReviewReadService;
import com.ssafy.thispatch.domain.review.service.ReviewReadService.RepresentativeReviews;
import com.ssafy.thispatch.domain.review.service.ReviewReadService.ReviewPage;
import com.ssafy.thispatch.domain.statistics.dto.response.AnalysisResponse;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class ReviewReadController {

	private final ReviewReadService service;

	@GetMapping("/games/{gameId}/reviews")
	public AnalysisResponse<ReviewPage> getReviews(@PathVariable("gameId") long gameId,
		@RequestParam(name = "topicIds", required = false) Set<Integer> topicIds,
		@RequestParam(name = "cursor", required = false) String cursor,
		@RequestParam(name = "limit", defaultValue = "10") int limit) {
		return AnalysisResponse.success(service.getReviews(gameId, topicIds == null ? Set.of() : topicIds, cursor, limit));
	}

	@GetMapping("/games/{gameId}/reviews/representative")
	public AnalysisResponse<RepresentativeReviews> getRepresentatives(@PathVariable("gameId") long gameId) {
		return AnalysisResponse.success(service.getRepresentatives(gameId));
	}
}
