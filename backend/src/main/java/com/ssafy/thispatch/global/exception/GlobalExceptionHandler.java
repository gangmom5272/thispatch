package com.ssafy.thispatch.global.exception;

import java.util.ArrayList;
import java.util.List;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.ssafy.thispatch.global.exception.ErrorResponse.FieldErrorDetail;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	@ExceptionHandler(BusinessException.class)
	public ResponseEntity<Object> handleBusinessException(BusinessException exception) {
		ErrorCode errorCode = exception.getErrorCode();
		if (errorCode.getStatus().is5xxServerError()) {
			logger.error("Business operation failed: " + errorCode.getCode(), exception);
		}
		var response = ResponseEntity.status(errorCode.getStatus());
		if (errorCode.getStatus() == HttpStatus.UNAUTHORIZED) {
			response.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
		}
		return response.body(ErrorResponse.of(errorCode));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<Object> handleUnexpectedException(Exception exception) {
		logger.error("Unexpected server error", exception);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
			.body(ErrorResponse.of(CommonErrorCode.INTERNAL_SERVER_ERROR));
	}

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
		HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		List<FieldErrorDetail> errors = exception.getBindingResult().getFieldErrors().stream()
			.map(this::fieldError).toList();
		return handleExceptionInternal(exception, ErrorResponse.validation(errors), headers, status, request);
	}

	@Override
	protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException exception,
		HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		// 반환값 검증 실패는 서버 구현 오류이므로 입력값 오류로 노출하지 않는다.
		if (exception.isForReturnValue()) {
			return handleExceptionInternal(exception, null, headers, status, request);
		}
		List<FieldErrorDetail> errors = new ArrayList<>();
		for (var result : exception.getParameterValidationResults()) {
			if (result instanceof ParameterErrors parameterErrors) {
				parameterErrors.getFieldErrors().stream().map(this::fieldError).forEach(errors::add);
			} else {
				String field = parameterName(result.getMethodParameter());
				result.getResolvableErrors().forEach(error ->
					errors.add(new FieldErrorDetail(field, validationMessage(error.getDefaultMessage()))));
			}
		}
		return handleExceptionInternal(exception, ErrorResponse.validation(errors), headers, status, request);
	}

	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
		HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		if (status.is5xxServerError()) {
			logger.error("Spring MVC server error", exception);
		}
		// MVC가 정한 상태 코드와 Allow 등 프로토콜 헤더는 유지한다.
		ErrorResponse response = body instanceof ErrorResponse errorResponse ? errorResponse : frameworkError(status);
		return super.handleExceptionInternal(exception, response, headers, status, request);
	}

	private ErrorResponse frameworkError(HttpStatusCode status) {
		if (status.value() == HttpStatus.BAD_REQUEST.value()) {
			return ErrorResponse.of(CommonErrorCode.INVALID_REQUEST);
		}
		if (status.value() == HttpStatus.INTERNAL_SERVER_ERROR.value()) {
			return ErrorResponse.of(CommonErrorCode.INTERNAL_SERVER_ERROR);
		}
		HttpStatus httpStatus = HttpStatus.resolve(status.value());
		String code = httpStatus == null ? "HTTP_ERROR" : httpStatus.name();
		String message = status.is5xxServerError()
			? CommonErrorCode.INTERNAL_SERVER_ERROR.getMessage() : "요청을 처리할 수 없습니다.";
		return ErrorResponse.of(code, message);
	}

	private FieldErrorDetail fieldError(FieldError error) {
		// 타입 변환 실패의 기본 메시지에는 입력값이나 Java 타입이 들어갈 수 있다.
		String message = error.isBindingFailure() ? "올바른 형식의 값을 입력해주세요."
			: validationMessage(error.getDefaultMessage());
		return new FieldErrorDetail(error.getField(), message);
	}

	private String validationMessage(String message) {
		return message == null ? CommonErrorCode.VALIDATION_FAILED.getMessage() : message;
	}

	private String parameterName(MethodParameter parameter) {
		RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
		if (requestParam != null) {
			String name = requestParam.name().isEmpty() ? requestParam.value() : requestParam.name();
			if (!name.isEmpty()) {
				return name;
			}
		}
		PathVariable pathVariable = parameter.getParameterAnnotation(PathVariable.class);
		if (pathVariable != null) {
			String name = pathVariable.name().isEmpty() ? pathVariable.value() : pathVariable.name();
			if (!name.isEmpty()) {
				return name;
			}
		}
		return parameter.getParameterName() == null ? "arg" + parameter.getParameterIndex() : parameter.getParameterName();
	}
}
