package com.ssafy.thispatch.domain.patch.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import com.ssafy.thispatch.common.TimeRule;
import com.ssafy.thispatch.domain.patch.dto.request.CaseSearchRequest.ConfirmedSlot;
import com.ssafy.thispatch.domain.patch.dto.request.CaseSearchRequest.Sort;
import com.ssafy.thispatch.domain.patch.service.CaseOutcome;

public record CaseSearchResponse(String code, String message, String responsedAt,
	SearchData data, boolean success) {

	public static CaseSearchResponse success(SearchData data) {
		return new CaseSearchResponse("201", "성공했습니다.",
			LocalDateTime.now(TimeRule.ZONE).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")), data, true);
	}

	public record SearchData(String status, long gameId, List<ConfirmedSlot> confirmedSlots,
		List<Integer> genreIds, Sort sort, int totalCount, List<Group> groups, List<String> notices) {}
	public record Group(CaseOutcome outcome, String name, int caseCount,
		List<String> observedPatterns, List<SimilarCase> cases) {}
	public record SimilarCase(long gameId, String gameTitle, String capsuleImageUrl, List<Integer> genres,
		String patchId, String patchTitle, LocalDate patchedOn, double similarity, long reviewCount,
		BigDecimal positiveRateBefore, BigDecimal positiveRateAfter, BigDecimal deltaPp,
		Double avgPatchIntervalDays, Double nextPatchIntervalDays, Double followUpSpeedRatio,
		String commonalitySummary, String differenceSummary, Comparison comparison) {}
	public record Comparison(List<ComparisonItem> commonalities, List<ComparisonItem> differences) {}
	public record ComparisonItem(String title, String description) {}
}
