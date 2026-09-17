package com.ssafy.thispatch.domain.statistics.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record PlaytimeAnalysis(AnalysisMeta meta, String selectedBand, int minimumSampleCount,
	boolean isSufficientSample, Scale scale, Band overall, List<Band> bands, List<Topic> topics, Fallback fallback) {

	public record Scale(String source, long sampleCount, Integer p25Minutes, Integer medianMinutes, Integer p75Minutes) {
	}

	public record Band(String band, int minMinutes, Integer maxMinutesExclusive, long reviewCount,
		long positiveCount, long negativeCount, BigDecimal positiveRate, boolean isSufficientSample) {
	}

	public record Topic(int topicId, String name, long mentionCount, BigDecimal mentionRate,
		BigDecimal overallMentionRate, BigDecimal differencePp, HighestBand highestBand) {
	}

	public record HighestBand(String band, BigDecimal mentionRate) {
	}

	public record Fallback(String reasonCode, String message, long totalCount, List<BandItems> itemsByBand) {
	}

	public record BandItems(String band, List<FallbackReview> items) {
	}

	public record FallbackReview(long id, String sentiment, LocalDate reviewDate,
		Integer playtimeMinutes, String languageCode, String body) {
	}
}
