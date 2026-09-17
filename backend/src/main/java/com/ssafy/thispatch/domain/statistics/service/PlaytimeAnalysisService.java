package com.ssafy.thispatch.domain.statistics.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.thispatch.common.TimeRule;
import com.ssafy.thispatch.domain.statistics.dto.response.AnalysisMeta;
import com.ssafy.thispatch.domain.statistics.dto.response.PlaytimeAnalysis;
import com.ssafy.thispatch.domain.statistics.dto.response.PlaytimeAnalysis.*;
import com.ssafy.thispatch.domain.statistics.repository.PlaytimeAnalysisRepository;
import com.ssafy.thispatch.domain.statistics.repository.PlaytimeAnalysisRepository.BandCount;
import com.ssafy.thispatch.domain.statistics.repository.PlaytimeAnalysisRepository.TopicCount;
import com.ssafy.thispatch.global.exception.BusinessException;
import com.ssafy.thispatch.global.exception.CommonErrorCode;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PlaytimeAnalysisService {

	private static final int MINIMUM_SAMPLE_COUNT = 30;
	private final PlaytimeAnalysisRepository repository;
	private final AnalysisContext context;

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public PlaytimeAnalysis getAnalysis(long gameId, LocalDate startDate, Integer bandNo) {
		if (bandNo != null && (bandNo < 1 || bandNo > 4)) {
			throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
		}
		var period = startDate == null ? context.recentPeriod() : context.periodStarting(startDate);
		context.requireGame(gameId);
		var counts = repository.findBandCounts(gameId, period);
		validateBoundaries(counts);
		var bands = counts.stream().map(count -> band("B" + count.bandNo(), count.minMinutes(),
			count.maxMinutesExclusive(), count.reviewCount(), count.positiveCount())).toList();
		long total = counts.stream().mapToLong(BandCount::reviewCount).sum();
		long positive = counts.stream().mapToLong(BandCount::positiveCount).sum();
		var overall = band("ALL", 0, null, total, positive);
		var scale = new Scale("ALL_GAME_REVIEWS", counts.stream().mapToLong(BandCount::allTimeCount).sum(),
			counts.isEmpty() ? null : counts.get(0).maxMinutesExclusive(),
			counts.isEmpty() ? null : counts.get(1).maxMinutesExclusive(),
			counts.isEmpty() ? null : counts.get(2).maxMinutesExclusive());
		long selectedCount = bandNo == null ? total : counts.isEmpty() ? 0 : counts.get(bandNo - 1).reviewCount();
		boolean sufficient = selectedCount >= MINIMUM_SAMPLE_COUNT;
		return new PlaytimeAnalysis(AnalysisMeta.of(period), bandNo == null ? "ALL" : "B" + bandNo,
			MINIMUM_SAMPLE_COUNT, sufficient, scale, overall, sufficient ? bands : List.of(),
			sufficient ? topics(repository.findTopicCounts(gameId, period), counts, bandNo, total) : List.of(),
			sufficient ? null : fallback(gameId, period, bandNo, selectedCount));
	}

	private static void validateBoundaries(List<BandCount> counts) {
		if (counts.isEmpty()) {
			return;
		}
		if (counts.size() != 4) {
			throw new IllegalStateException("Expected four precomputed playtime bands");
		}
		int expectedFrom = 0;
		for (int index = 0; index < 4; index++) {
			var count = counts.get(index);
			if (count.bandNo() != index + 1 || count.minMinutes() != expectedFrom
				|| (index == 3 ? count.maxMinutesExclusive() != null
					: count.maxMinutesExclusive() == null || count.maxMinutesExclusive() < expectedFrom)) {
				throw new IllegalStateException("Invalid precomputed playtime boundaries");
			}
			if (count.maxMinutesExclusive() != null) {
				expectedFrom = count.maxMinutesExclusive();
			}
		}
	}

	private Fallback fallback(long gameId, ReviewPeriod period, Integer selectedBand, long count) {
		var rows = repository.findFallbackReviews(gameId, period, selectedBand);
		var byBand = rows.stream().collect(Collectors.groupingBy(PlaytimeAnalysisRepository.BandReview::bandNo));
		List<BandItems> items = new ArrayList<>();
		for (int bandNo = 1; bandNo <= 4; bandNo++) {
			var reviews = byBand.getOrDefault(bandNo, List.of()).stream().map(item -> {
				var review = item.review();
				return new FallbackReview(review.id(), review.positive() ? "POSITIVE" : "NEGATIVE",
					review.updatedAt().atZone(TimeRule.ZONE).toLocalDate(), review.playtimeMinutes(),
					"koreana".equals(review.languageCode()) ? "korean" : review.languageCode(), review.body());
			}).toList();
			items.add(new BandItems("B" + bandNo, reviews));
		}
		return new Fallback("INSUFFICIENT_SAMPLE", "표본이 부족해 구간·토픽 집계 대신 원문을 표시합니다.", count, items);
	}

	private static List<Topic> topics(List<TopicCount> mentions, List<BandCount> bands, Integer selectedBand, long total) {
		Map<Integer, List<TopicCount>> byTopic = mentions.stream()
			.collect(Collectors.groupingBy(TopicCount::topicId, TreeMap::new, Collectors.toList()));
		List<Topic> topics = new ArrayList<>();
		for (var topicCounts : byTopic.values()) {
			long overallCount = topicCounts.stream().mapToLong(TopicCount::mentionCount).sum();
			long selectedCount = selectedBand == null ? overallCount : topicCounts.stream()
				.filter(item -> item.bandNo() == selectedBand).mapToLong(TopicCount::mentionCount).sum();
			long denominator = selectedBand == null ? total : bands.get(selectedBand - 1).reviewCount();
			var rate = percentage(selectedCount, denominator);
			var overallRate = percentage(overallCount, total);
			HighestBand highest = null;
			// 동률이면 B1부터 유지한다. 빈 구간의 비율은 비교 대상이 아니다.
			for (var band : bands) {
				long count = topicCounts.stream().filter(item -> item.bandNo() == band.bandNo())
					.mapToLong(TopicCount::mentionCount).sum();
				var bandRate = percentage(count, band.reviewCount());
				if (bandRate != null && (highest == null || bandRate.compareTo(highest.mentionRate()) > 0)) {
					highest = new HighestBand("B" + band.bandNo(), bandRate);
				}
			}
			var first = topicCounts.get(0);
			topics.add(new Topic(first.topicId(), first.name(), selectedCount, rate, overallRate,
				selectedBand == null || rate == null || overallRate == null ? null : rate.subtract(overallRate), highest));
		}
		return topics;
	}

	private static Band band(String name, int from, Integer to, long count, long positive) {
		return new Band(name, from, to, count, positive, count - positive, percentage(positive, count),
			count >= MINIMUM_SAMPLE_COUNT);
	}

	private static BigDecimal percentage(long count, long total) {
		return total == 0 ? null : BigDecimal.valueOf(count).multiply(BigDecimal.valueOf(100))
			.divide(BigDecimal.valueOf(total), 1, RoundingMode.HALF_UP);
	}
}
