package com.ssafy.thispatch.domain.statistics.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.thispatch.domain.statistics.dto.response.AnalysisMeta.CollectionMeta;
import com.ssafy.thispatch.domain.statistics.dto.response.AnalysisMeta.Period;
import com.ssafy.thispatch.domain.statistics.repository.DailyStatisticsRepository;
import com.ssafy.thispatch.domain.statistics.repository.DailyStatisticsRepository.DailyCounts;
import com.ssafy.thispatch.domain.statistics.repository.ReactionPatchRepository;
import com.ssafy.thispatch.domain.statistics.repository.ReactionPatchRepository.Patch;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ReactionTrendsService {

	private final DailyStatisticsRepository repository;
	private final ReactionPatchRepository patchRepository;
	private final AnalysisContext context;

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public ReactionTrends getTrends(long gameId, LocalDate startDate) {
		var period = context.periodStarting(startDate);
		context.requireGame(gameId);
		var patchesByDate = patchRepository.findWithinPeriod(gameId, period).stream()
			.collect(Collectors.groupingBy(Patch::patchedOn));
		var rows = repository.findWithinPeriod(gameId, period);
		var daily = rows.stream().map(row -> toDay(row, patchesByDate.getOrDefault(row.date(), List.of()))).toList();
		long reviews = 0, negatives = 0, written = 0, writtenPositive = 0, edited = 0, editedPositive = 0;
		for (var day : daily) {
			reviews += day.reviewCount();
			negatives += day.negativeCount();
			written += day.firstWrittenCount();
			writtenPositive += day.firstWrittenPositiveCount();
			edited += day.updatedCount();
			editedPositive += day.updatedPositiveCount();
		}
		var summary = new Summary(reviews, reviews - negatives, negatives, percentage(reviews - negatives, reviews),
			written, writtenPositive, written - writtenPositive, percentage(writtenPositive, written),
			edited, editedPositive, edited - editedPositive, percentage(editedPositive, edited));
		var firstDate = repository.firstStatDate(gameId);
		var available = firstDate == null ? null : Period.of(new ReviewPeriod(firstDate, period.endDate()));
		return new ReactionTrends(CollectionMeta.of(period), available, summary, daily);
	}

	private static Day toDay(DailyCounts row, List<Patch> patches) {
		long negative = row.reviewCount() - row.positiveCount();
		return new Day(row.date(), true, row.reviewCount(), row.reviewCount() - negative, negative,
			percentage(row.reviewCount() - negative, row.reviewCount()), row.firstWrittenCount(), row.updatedCount(),
			row.firstWrittenPositiveCount(), row.firstWrittenCount() - row.firstWrittenPositiveCount(),
			row.updatedPositiveCount(), row.updatedCount() - row.updatedPositiveCount(), patches);
	}

	private static BigDecimal percentage(long positive, long count) {
		return count == 0 ? null : BigDecimal.valueOf(positive).multiply(BigDecimal.valueOf(100))
			.divide(BigDecimal.valueOf(count), 1, RoundingMode.HALF_UP);
	}

	public record ReactionTrends(CollectionMeta meta, Period availablePeriod, Summary summary, List<Day> daily) {
	}

	public record Summary(long reviewCount, long positiveCount, long negativeCount, BigDecimal positiveRate,
		long firstWrittenCount, long firstWrittenPositiveCount, long firstWrittenNegativeCount,
		BigDecimal firstWrittenPositiveRate, long updatedCount, long updatedPositiveCount,
		long updatedNegativeCount, BigDecimal updatedPositiveRate) {
	}

	public record Day(LocalDate date, boolean dataAvailable, long reviewCount, long positiveCount,
		long negativeCount, BigDecimal positiveRate, long firstWrittenCount, long updatedCount,
		long firstWrittenPositiveCount, long firstWrittenNegativeCount, long updatedPositiveCount,
		long updatedNegativeCount, List<Patch> patches) {
	}
}
