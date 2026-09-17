package com.ssafy.thispatch.domain.statistics.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import com.ssafy.thispatch.domain.statistics.repository.LanguageStatisticsRepository.LanguageCount;

public final class LanguageStatisticsCalculator {

	private static final int MINIMUM_SAMPLE_COUNT = 30;

	private LanguageStatisticsCalculator() {
	}

	public static LanguageStatistics calculate(List<LanguageCount> counts) {
		long totalReviewCount = counts.stream().mapToLong(LanguageCount::reviewCount).sum();
		List<LanguageItem> languages = counts.stream().map(count -> new LanguageItem(
			count.languageCode(), count.displayName(), count.reviewCount(),
			percentage(count.reviewCount(), totalReviewCount), percentage(count.positiveCount(), count.reviewCount()),
			count.positiveCount(), count.reviewCount() - count.positiveCount(),
			count.reviewCount() >= MINIMUM_SAMPLE_COUNT)).toList();
		return new LanguageStatistics(totalReviewCount, totalReviewCount >= MINIMUM_SAMPLE_COUNT,
			MINIMUM_SAMPLE_COUNT, languages);
	}

	private static BigDecimal percentage(long numerator, long denominator) {
		return denominator == 0 ? null : BigDecimal.valueOf(numerator).multiply(BigDecimal.valueOf(100))
			.divide(BigDecimal.valueOf(denominator), 1, RoundingMode.HALF_UP);
	}

	public record LanguageStatistics(long totalReviewCount, boolean isSufficientSample,
		int minimumSampleCount, List<LanguageItem> languages) {
	}

	public record LanguageItem(String languageCode, String displayName, long reviewCount,
		BigDecimal reviewShare, BigDecimal positiveRate, long positiveCount, long negativeCount,
		boolean isSufficientSample) {
	}
}
