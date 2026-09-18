package com.ssafy.thispatch.domain.review.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.ssafy.thispatch.domain.review.dto.response.ReviewTranslation;
import com.ssafy.thispatch.domain.review.service.ReviewTranslationService;
import com.ssafy.thispatch.domain.statistics.dto.response.AnalysisResponse;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class ReviewTranslationController {

	private final ReviewTranslationService service;

	@GetMapping("/reviews/{reviewId}/translation")
	public AnalysisResponse<ReviewTranslation> translate(@PathVariable("reviewId") long reviewId) {
		return AnalysisResponse.success(service.translate(reviewId));
	}
}
