package com.ssafy.thispatch.domain.statistics.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thispatch.domain.statistics.repository.LanguageStatisticsRepository.LanguageCount;

class LanguageStatisticsCalculatorTest {

	@Test
	void insufficientLanguagesStillContributeToTotalAndShares() {
		var result = LanguageStatisticsCalculator.calculate(List.of(
			new LanguageCount("english", "영어", 80, 56),
			new LanguageCount("korean", "한국어", 30, 21),
			new LanguageCount("japanese", "일본어", 10, 0)));
		assertThat(result.totalReviewCount()).isEqualTo(120);
		assertThat(result.isSufficientSample()).isTrue();
		assertThat(result.languages().get(0).reviewShare()).isEqualByComparingTo("66.7");
		assertThat(result.languages().get(0).positiveRate()).isEqualByComparingTo("70.0");
		assertThat(result.languages().get(1).isSufficientSample()).isTrue();
		assertThat(result.languages().get(2).isSufficientSample()).isFalse();
		assertThat(result.languages().get(2).negativeCount()).isEqualTo(10);
	}

	@Test
	void emptyPeriodHasNoSufficientSample() {
		var result = LanguageStatisticsCalculator.calculate(List.of());
		assertThat(result.totalReviewCount()).isZero();
		assertThat(result.isSufficientSample()).isFalse();
		assertThat(result.languages()).isEmpty();
	}

	@Test
	void sampleFlagKeepsTheAgreedJsonName() throws Exception {
		var result = LanguageStatisticsCalculator.calculate(List.of(new LanguageCount("korean", "한국어", 30, 21)));
		var json = new ObjectMapper().valueToTree(result);
		assertThat(json.path("isSufficientSample").asBoolean()).isTrue();
		assertThat(json.has("sampleSufficient")).isFalse();
		assertThat(json.path("languages").get(0).path("isSufficientSample").asBoolean()).isTrue();
	}
}
