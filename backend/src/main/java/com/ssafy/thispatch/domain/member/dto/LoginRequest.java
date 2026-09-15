package com.ssafy.thispatch.domain.member.dto;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.ssafy.thispatch.domain.member.validation.Utf8ByteLength;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
	@NotBlank(message = "이메일을 입력해주세요.")
	@Email(message = "올바른 이메일 형식이 아닙니다.")
	@Size(max = 255, message = "이메일은 255자 이하여야 합니다.")
	String email,
	@NotBlank(message = "비밀번호를 입력해주세요.")
	@Utf8ByteLength(max = 72, message = "비밀번호는 UTF-8 기준 72바이트 이하여야 합니다.")
	String password
) {

	public LoginRequest {
		email = email == null ? null : email.strip().toLowerCase(Locale.ROOT);
	}

	@JsonCreator
	public static LoginRequest fromJson(@JsonProperty("email") JsonNode email,
		@JsonProperty("password") JsonNode password) {
		return new LoginRequest(stringValue(email), stringValue(password));
	}

	private static String stringValue(JsonNode value) {
		if (value == null || value.isNull()) {
			return null;
		}
		if (!value.isTextual()) {
			throw new IllegalArgumentException("Login credentials must be strings");
		}
		return value.textValue();
	}

	@Override
	public String toString() {
		return "LoginRequest[credentials=REDACTED]";
	}
}
