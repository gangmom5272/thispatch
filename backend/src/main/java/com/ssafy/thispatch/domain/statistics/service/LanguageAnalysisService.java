package com.ssafy.thispatch.domain.statistics.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.thispatch.domain.statistics.dto.response.AnalysisMeta;
import com.ssafy.thispatch.domain.statistics.repository.LanguageStatisticsRepository;
import com.ssafy.thispatch.domain.statistics.service.LanguageStatisticsCalculator.LanguageItem;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class LanguageAnalysisService {

	private final LanguageStatisticsRepository repository;
	private final AnalysisContext context;

	@Transactional(readOnly = true)
	public LanguageAnalysis getAnalysis(long gameId) {
		context.requireGame(gameId);
		var period = context.recentPeriod();
		var statistics = LanguageStatisticsCalculator.calculate(repository.findWithinPeriod(gameId, period));
		return new LanguageAnalysis(AnalysisMeta.of(period), statistics.totalReviewCount(),
			statistics.isSufficientSample(), statistics.minimumSampleCount(), statistics.languages());
	}

	public record LanguageAnalysis(AnalysisMeta meta, long totalReviewCount, boolean isSufficientSample,
		int minimumSampleCount, List<LanguageItem> languages) {
	}
}
