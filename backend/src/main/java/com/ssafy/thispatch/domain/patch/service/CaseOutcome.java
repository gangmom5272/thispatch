package com.ssafy.thispatch.domain.patch.service;

import java.math.BigDecimal;

public enum CaseOutcome {
	NEGATIVE_SHIFT("부정 급변"), NO_CHANGE("변화 없음"), POSITIVE_SHIFT("긍정 급변");

	private static final BigDecimal SHIFT_THRESHOLD_PP = new BigDecimal("3");
	private final String displayName;

	CaseOutcome(String displayName) { this.displayName = displayName; }
	public String displayName() { return displayName; }

	public static CaseOutcome fromDelta(BigDecimal deltaPp) {
		// 표시 반올림보다 먼저 원래 %p 값으로 경계를 판정한다.
		if (deltaPp.compareTo(SHIFT_THRESHOLD_PP) >= 0) return POSITIVE_SHIFT;
		if (deltaPp.compareTo(SHIFT_THRESHOLD_PP.negate()) <= 0) return NEGATIVE_SHIFT;
		return NO_CHANGE;
	}
}
