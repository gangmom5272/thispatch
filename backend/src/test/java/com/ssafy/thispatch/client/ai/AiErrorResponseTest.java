package com.ssafy.thispatch.client.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.ssafy.thispatch.global.exception.BusinessException;
import com.ssafy.thispatch.global.exception.GlobalExceptionHandler;

class AiErrorResponseTest {

	@Test
	void aiRequiredOperationReturnsUnavailableWithoutLeakingInternalDetails() {
		var failure = new BusinessException(AiErrorCode.AI_UNAVAILABLE,
			new IllegalStateException("private upstream response and server address"));
		var response = new GlobalExceptionHandler().handleBusinessException(failure);
		var json = new ObjectMapper().valueToTree(response.getBody());

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
		assertThat(json.get("code").asText()).isEqualTo("AI_UNAVAILABLE");
		assertThat(json.get("message").asText()).isEqualTo(
			"AI 기능을 일시적으로 이용할 수 없습니다. 잠시 후 다시 시도해 주세요.");
		assertThat(json.get("responsedAt").asText()).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
		assertThat(json.size()).isEqualTo(3);
		assertThat(json.toString()).doesNotContain("private upstream");
	}
}
