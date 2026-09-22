package com.ssafy.thispatch.domain.member.dto;

import java.io.IOException;

import org.hibernate.validator.constraints.CodePointLength;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record NicknameRequest(
	@NotNull(message = "닉네임을 입력해주세요.")
	@Pattern(regexp = "(?s).*[^\\p{javaWhitespace}\\p{Z}].*", message = "닉네임을 입력해주세요.")
	@CodePointLength(max = 20, message = "닉네임은 20자 이하로 입력해주세요.")
	@Pattern(regexp = "[^\\x00]*", message = "닉네임에 NUL 문자를 사용할 수 없습니다.")
	@JsonDeserialize(using = NicknameDeserializer.class)
	String nickname
) {

	// Jackson의 숫자·boolean → 문자열 자동 변환을 이 요청 필드에서만 차단한다.
	public static class NicknameDeserializer extends StdDeserializer<String> {

		public NicknameDeserializer() {
			super(String.class);
		}

		@Override
		public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
			if (!parser.hasToken(JsonToken.VALUE_STRING)) {
				return (String) context.handleUnexpectedToken(String.class, parser);
			}
			return parser.getText();
		}
	}
}
