package com.ssafy.thispatch.domain.statistics.service;

import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.stereotype.Service;

import com.ssafy.thispatch.client.ai.AiErrorCode;
import com.ssafy.thispatch.client.ai.AiReviewClient;
import com.ssafy.thispatch.client.ai.AiSummaryCache;
import com.ssafy.thispatch.client.ai.AiReviewClient.Review;
import com.ssafy.thispatch.client.ai.AiReviewClient.SummaryRequest;
import com.ssafy.thispatch.domain.review.dto.response.ReviewItem;
import com.ssafy.thispatch.domain.review.repository.ReviewReadRepository.ReviewRow;
import com.ssafy.thispatch.domain.statistics.dto.response.AnalysisMeta;
import com.ssafy.thispatch.domain.statistics.dto.response.AnalysisMeta.Period;
import com.ssafy.thispatch.domain.statistics.service.SummaryReviewReader.Input;
import com.ssafy.thispatch.global.exception.BusinessException;
import com.ssafy.thispatch.global.exception.CommonErrorCode;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReviewSummaryService {

	private static final int MINIMUM_SAMPLE_COUNT = 30;
	private final AnalysisContext context;
	private final SummaryReviewReader reader;
	private final AiReviewClient ai;
	private final AiSummaryCache summaryCache;

	public PlaytimeSummary playtime(long gameId, LocalDate startDate, Integer bandNo) {
		if (bandNo != null && (bandNo < 1 || bandNo > 4)) {
			throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
		}
		ReviewPeriod period = startDate == null ? context.recentPeriod() : context.periodStarting(startDate);
		Input input = reader.read(gameId, period, bandNo, null, 40);
		String selectedBand = bandNo == null ? "ALL" : "B" + bandNo;
		SummaryBody summary = summarize(gameId, period, input, bandNo == null ? "ALL" : "BAND",
			bandNo == null ? "" : bandNo.toString(), 40,
			new Selection("HELPFUL_DESC_PER_PLAYTIME_BUCKET", 40,
				"선택 구간(" + (bandNo == null ? "전체" : selectedBand) + ") 기준 도움됨 상위 리뷰를 사용해 요약했습니다."));
		return new PlaytimeSummary(AnalysisMeta.of(period), selectedBand, summary);
	}

	public LanguageDetail language(long gameId, String languageCode) {
		validateLanguageCode(languageCode);
		ReviewPeriod period = context.recentPeriod();
		Input input = reader.read(gameId, period, null, languageCode, 20);
		return new LanguageDetail(AnalysisMeta.of(period), languageCode,
			summarizeLanguage(gameId, languageCode, period, input), representatives(input));
	}

	public LanguageReviews languageReviews(long gameId, String languageCode) {
		validateLanguageCode(languageCode);
		ReviewPeriod period = context.recentPeriod();
		Input input = reader.read(gameId, period, null, languageCode, 4);
		return new LanguageReviews(AnalysisMeta.of(period), languageCode, representatives(input));
	}

	public LanguageSummaryResponse languageSummary(long gameId, String languageCode) {
		validateLanguageCode(languageCode);
		ReviewPeriod period = context.recentPeriod();
		Input input = reader.read(gameId, period, null, languageCode, 20);
		return new LanguageSummaryResponse(AnalysisMeta.of(period), languageCode,
			summarizeLanguage(gameId, languageCode, period, input));
	}

	private static void validateLanguageCode(String languageCode) {
		if (languageCode == null || !languageCode.matches("[a-z]{2,20}") || "koreana".equals(languageCode)) {
			throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
		}
	}

	private LanguageSummary summarizeLanguage(long gameId, String languageCode, ReviewPeriod period, Input input) {
		SummaryBody summary = summarize(gameId, period, input, "LANGUAGE", languageCode, 20,
			new Selection("HELPFUL_DESC", 20, input.targetCount() + "건 중 도움됨 상위 "
				+ input.reviews().size() + "건"));
		return new LanguageSummary(summary.status(), summary.text(), summary.targetPeriod(),
			summary.targetReviewCount(), summary.usedReviewCount(), summary.selection(),
			summary.reasonCode(), summary.message());
	}

	private static List<ReviewItem> representatives(Input input) {
		return input.reviews().stream().limit(4)
			.map(row -> ReviewItem.of(row, input.tags().getOrDefault(row.id(), List.of()))).toList();
	}

	private SummaryBody summarize(long gameId, ReviewPeriod period, Input input, String scopeType,
		String scopeKey, int limit, Selection selection) {
		if (input.targetCount() < MINIMUM_SAMPLE_COUNT) {
			return new SummaryBody("SKIPPED", null, List.of(), Period.of(period), input.targetCount(),
				null, null, "INSUFFICIENT_SAMPLE", null);
		}
		List<Review> selected = input.reviews().stream().limit(limit).map(ReviewSummaryService::aiReview).toList();
		try {
			var request = new SummaryRequest(gameId, input.gameName(), scopeType, scopeKey, selected);
			var result = summaryCache.getOrCompute("reviews", List.of(period, request), AiReviewClient.Summary.class,
				() -> ai.summarize(request), response -> AiReviewClient.isUsableSummary(request, response));
			return new SummaryBody("COMPLETED", result.summary(), result.phrases(), Period.of(period),
				input.targetCount(), selected.size(), selection, null, null);
		} catch (BusinessException exception) {
			// AI 실패만 요약 영역에 한정한다. DB 장애나 다른 코드 오류를 정상 응답으로 숨기지 않는다.
			if (exception.getErrorCode() != AiErrorCode.AI_UNAVAILABLE) {
				throw exception;
			}
			log.warn("AI summary unavailable: gameId={}, scopeType={}", gameId, scopeType);
			return new SummaryBody("UNAVAILABLE", null, List.of(), Period.of(period), input.targetCount(),
				null, null, "AI_UNAVAILABLE", "AI 요약을 일시적으로 이용할 수 없습니다.");
		}
	}

	private static Review aiReview(ReviewRow row) {
		// AI 서버도 본문 앞 600자만 사용한다. API의 본문 길이 제한(4,000자)을 넘기지 않는다.
		String body = row.body();
		if (body == null || body.isBlank()) {
			throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
		}
		int end = body.offsetByCodePoints(0, Math.min(600, body.codePointCount(0, body.length())));
		return new Review(row.id(), body.substring(0, end), row.positive(), row.helpfulCount(),
			"koreana".equals(row.languageCode()) ? "korean" : row.languageCode(), row.playtimeMinutes());
	}

	public record Selection(String code, int limit, String description) {
	}

	public record SummaryBody(String status, String text, List<String> recurringExpressions,
		Period targetPeriod, long targetReviewCount, Integer usedReviewCount, Selection selection, String reasonCode,
		@JsonInclude(JsonInclude.Include.NON_NULL) String message) {
	}

	public record LanguageSummary(String status, String text, Period targetPeriod, long targetReviewCount,
		Integer usedReviewCount, Selection selection,
		@JsonInclude(JsonInclude.Include.NON_NULL) String reasonCode,
		@JsonInclude(JsonInclude.Include.NON_NULL) String message) {
	}

	public record PlaytimeSummary(AnalysisMeta meta, String selectedBand, SummaryBody summary) {
	}

	public record LanguageDetail(AnalysisMeta meta, String languageCode, LanguageSummary summary,
		List<ReviewItem> representativeReviews) {
	}

	public record LanguageReviews(AnalysisMeta meta, String languageCode, List<ReviewItem> representativeReviews) {
	}

	public record LanguageSummaryResponse(AnalysisMeta meta, String languageCode, LanguageSummary summary) {
	}
}
