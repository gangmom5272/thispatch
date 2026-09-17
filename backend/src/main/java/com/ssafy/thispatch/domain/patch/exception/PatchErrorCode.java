package com.ssafy.thispatch.domain.patch.exception;

import org.springframework.http.HttpStatus;
import com.ssafy.thispatch.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PatchErrorCode implements ErrorCode {

	PATCH_NOT_FOUND(HttpStatus.NOT_FOUND, "패치를 찾을 수 없습니다.");

	private final HttpStatus status;
	private final String message;

	@Override
	public String getCode() {
		return name();
	}
}
