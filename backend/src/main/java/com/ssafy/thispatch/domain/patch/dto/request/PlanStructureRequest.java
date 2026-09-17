package com.ssafy.thispatch.domain.patch.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PlanStructureRequest(
	@NotBlank(message = "기획안 본문을 입력해주세요.")
	@Size(min = 5, max = 6000, message = "기획안은 5~6000자로 입력해주세요.") String text) {
}
