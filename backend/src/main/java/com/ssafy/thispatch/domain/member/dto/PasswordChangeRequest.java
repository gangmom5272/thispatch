package com.ssafy.thispatch.domain.member.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.ssafy.thispatch.domain.member.validation.Utf8ByteLength;

import jakarta.validation.constraints.NotBlank;

public record PasswordChangeRequest(
	@NotBlank(message = "현재 비밀번호를 입력해주세요.")
	@Utf8ByteLength(max = 72, message = "비밀번호는 UTF-8 기준 72바이트 이하여야 합니다.")
	String currentPassword,
	@NotBlank(message = "새 비밀번호를 입력해주세요.")
	@Utf8ByteLength(max = 72, message = "비밀번호는 UTF-8 기준 72바이트 이하여야 합니다.")
	String newPassword
) {

	@JsonCreator
	public static PasswordChangeRequest fromJson(@JsonProperty("currentPassword") JsonNode currentPassword,
		@JsonProperty("newPassword") JsonNode newPassword) {
		return new PasswordChangeRequest(stringValue(currentPassword), stringValue(newPassword));
	}

	private static String stringValue(JsonNode value) {
		if (value == null || value.isNull()) {
			return null;
		}
		if (!value.isTextual()) {
			throw new IllegalArgumentException("Password change fields must be strings");
		}
		return value.textValue();
	}

	@Override
	public String toString() {
		return "PasswordChangeRequest[credentials=REDACTED]";
	}
}
