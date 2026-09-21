package com.ssafy.thispatch.domain.statistics.service;

import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.stereotype.Service;

import com.ssafy.thispatch.client.ai.AiErrorCode;
import com.ssafy.thispatch.client.ai.AiTrendClient;
import com.ssafy.thispatch.client.ai.AiSummaryCache;

import com.ssafy.thispatch.domain.statistics.dto.response.AnalysisMeta.CollectionMeta;
import com.ssafy.thispatch.domain.statistics.dto.response.AnalysisMeta.Period;
import com.ssafy.thispatch.global.exception.BusinessException;
import com.ssafy.thispatch.global.exception.CommonErrorCode;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ReactionSummaryService {

	private static final int MINIMUM_SAMPLE_COUNT = 30;
	private final AnalysisContext context;
	private final ReactionSummaryReader reader;
	private final AiTrendClient ai;
	private final AiSummaryCache summaryCache;

	public ReactionSummary getSummary(long gameId, LocalDate startDate, LocalDate endDate) {
		ReviewPeriod period = context.periodBetween(startDate, endDate);
		if (period.dayCount() > 400) {
			throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
		}
		AiTrendClient.Request request = reader.read(gameId, period);
		long reviewCount = request.daily().stream().mapToLong(AiTrendClient.Day::reviews).sum();
		Summary summary;
		if (reviewCount < MINIMUM_SAMPLE_COUNT) {
			summary = new Summary("SKIPPED", null, Period.of(period), "INSUFFICIENT_SAMPLE", null);
		} else {
			try {
				AiTrendClient.Result result = summaryCache.getOrCompute("trends", List.of(period, request),
					AiTrendClient.Result.class, () -> ai.summarize(request), response ->
						AiTrendClient.isUsableSummary(request, response) && response.usedLlm() && response.clean());
				String text = result.summary();
				if (!result.caveats().isEmpty()) {
					// 별도 각주 필드가 없는 화면 계약에서도 해석의 한계를 빠뜨리지 않는다.
					text += "\n\n" + String.join("\n", result.caveats());
				}
				summary = new Summary("COMPLETED", text, Period.of(period), null, null);
			} catch (BusinessException exception) {
				if (exception.getErrorCode() != AiErrorCode.AI_UNAVAILABLE) {
					throw exception;
				}
				summary = new Summary("UNAVAILABLE", null, Period.of(period), "AI_UNAVAILABLE",
					"AI 요약을 일시적으로 이용할 수 없습니다.");
			}
		}
		return new ReactionSummary(CollectionMeta.of(period), summary);
	}

	public record ReactionSummary(CollectionMeta meta, Summary summary) {
	}

	public record Summary(String status, String text, Period targetPeriod, String reasonCode,
		@JsonInclude(JsonInclude.Include.NON_NULL) String message) {
	}
}
