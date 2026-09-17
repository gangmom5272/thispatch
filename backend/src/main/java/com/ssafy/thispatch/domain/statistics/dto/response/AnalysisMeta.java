package com.ssafy.thispatch.domain.statistics.dto.response;

import java.time.Instant;
import java.time.LocalDate;

import com.ssafy.thispatch.domain.statistics.service.ReviewPeriod;

public record AnalysisMeta(Period period, String timezone, String aggregationBasis, String dataStatus) {

	public static AnalysisMeta of(ReviewPeriod period) {
		return new AnalysisMeta(Period.of(period), "Asia/Seoul", "UPDATED_AT", "AVAILABLE");
	}

	public record Period(LocalDate startDate, LocalDate endDate, long dayCount) {
		public static Period of(ReviewPeriod period) {
			return new Period(period.startDate(), period.endDate(), period.dayCount());
		}
	}

	public record CollectionMeta(Period period, String timezone, String aggregationBasis,
		Instant lastCollectedAt, String dataStatus) {
		public static CollectionMeta of(ReviewPeriod period) {
			// 적재 담당의 리뷰 수집 시각 연결 전이다. 작성·수정 시각으로 대신하지 않는다.
			return new CollectionMeta(Period.of(period), "Asia/Seoul", "UPDATED_AT", null, "AVAILABLE");
		}
	}

	public record ReviewListMeta(Period period, String timezone, String aggregationBasis, Instant lastCollectedAt) {
		public static ReviewListMeta of(ReviewPeriod period) {
			return new ReviewListMeta(Period.of(period), "Asia/Seoul", "UPDATED_AT", null);
		}
	}
}
