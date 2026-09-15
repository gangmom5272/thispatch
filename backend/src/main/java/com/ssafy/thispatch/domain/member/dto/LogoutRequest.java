package com.ssafy.thispatch.domain.member.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import jakarta.validation.constraints.NotBlank;

public record LogoutRequest(@NotBlank(message = "Refresh Token을 입력해주세요.") String refreshToken) {

	@JsonCreator
	public static LogoutRequest fromJson(@JsonProperty("refreshToken") JsonNode refreshToken) {
		if (refreshToken == null || refreshToken.isNull()) {
			return new LogoutRequest(null);
		}
		if (!refreshToken.isTextual()) {
			throw new IllegalArgumentException("refreshToken must be a string");
		}
		return new LogoutRequest(refreshToken.textValue());
	}
}
