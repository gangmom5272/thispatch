package com.ssafy.thispatch.domain.member.validation;

import java.nio.charset.StandardCharsets;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class Utf8ByteLengthValidator implements ConstraintValidator<Utf8ByteLength, String> {

	private int max;

	@Override
	public void initialize(Utf8ByteLength constraint) {
		max = constraint.max();
	}

	@Override
	public boolean isValid(String value, ConstraintValidatorContext context) {
		// 필수 입력 여부는 @NotBlank가 담당한다.
		return value == null || value.getBytes(StandardCharsets.UTF_8).length <= max;
	}
}
