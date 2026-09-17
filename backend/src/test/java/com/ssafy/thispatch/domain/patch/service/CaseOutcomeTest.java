package com.ssafy.thispatch.domain.patch.service;

import static org.assertj.core.api.Assertions.assertThat;
import java.math.BigDecimal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CaseOutcomeTest {
	@ParameterizedTest
	@CsvSource({"-100,NEGATIVE_SHIFT", "-3.001,NEGATIVE_SHIFT", "-3,NEGATIVE_SHIFT",
		"-2.999,NO_CHANGE", "0,NO_CHANGE", "2.999,NO_CHANGE", "3,POSITIVE_SHIFT", "3.001,POSITIVE_SHIFT", "100,POSITIVE_SHIFT"})
	void classifiesPercentagePointBoundariesWithoutRounding(String delta, CaseOutcome expected) {
		assertThat(CaseOutcome.fromDelta(new BigDecimal(delta))).isEqualTo(expected);
	}
}
