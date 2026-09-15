package com.ssafy.thispatch.domain.member.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import jakarta.validation.constraints.NotBlank;

public record SteamTokenRequest(@NotBlank(message = "로그인 코드를 입력해주세요.") String loginCode) {

	@JsonCreator
	public static SteamTokenRequest fromJson(@JsonProperty("loginCode") JsonNode loginCode) {
		if (loginCode == null || loginCode.isNull()) {
			return new SteamTokenRequest(null);
		}
		// 숫자·불리언을 문자열로 자동 변환하지 않고 필수 string 계약을 유지한다.
		if (!loginCode.isTextual()) {
			throw new IllegalArgumentException("loginCode must be a string");
		}
		return new SteamTokenRequest(loginCode.textValue());
	}
}
