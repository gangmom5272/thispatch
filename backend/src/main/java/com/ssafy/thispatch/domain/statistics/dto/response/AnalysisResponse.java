package com.ssafy.thispatch.domain.statistics.dto.response;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import com.ssafy.thispatch.common.TimeRule;

public record AnalysisResponse<T>(String code, String message, String responsedAt, T data, boolean success) {

	public static <T> AnalysisResponse<T> success(T data) {
		return new AnalysisResponse<>("200", "성공했습니다.",
			LocalDateTime.now(TimeRule.ZONE).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")), data, true);
	}
}
