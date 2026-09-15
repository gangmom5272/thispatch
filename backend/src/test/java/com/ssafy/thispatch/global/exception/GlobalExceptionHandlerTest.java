package com.ssafy.thispatch.global.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

@WebMvcTest(GlobalExceptionHandlerTest.TestController.class)
@Import({GlobalExceptionHandler.class, GlobalExceptionHandlerTest.TestController.class})
// 인증 필터 연동은 별도 Security 작업의 범위이며 여기서는 MVC 오류 계약을 검증한다.
@AutoConfigureMockMvc(addFilters = false)
class GlobalExceptionHandlerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void businessErrorPreservesStatusAndCodeAndUsesKoreanTime() throws Exception {
		Instant before = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		var result = mockMvc.perform(get("/_test/errors/business"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("TEST_CONFLICT"))
			.andExpect(jsonPath("$.message").value("이미 처리된 요청입니다."))
			.andExpect(jsonPath("$.data").doesNotExist())
			.andExpect(jsonPath("$.success").doesNotExist())
			.andExpect(jsonPath("$.errors").doesNotExist())
			.andReturn();
		var body = objectMapper.readTree(result.getResponse().getContentAsByteArray());
		assertThat(body.size()).isEqualTo(3);
		Instant responseTime = LocalDateTime.parse(body.get("responsedAt").asText(),
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).atZone(ZoneId.of("Asia/Seoul")).toInstant();
		assertThat(responseTime).isBetween(before, Instant.now());
	}

	@Test
	void bodyValidationReturnsFieldMessagesWithoutRejectedValues() throws Exception {
		var result = mockMvc.perform(post("/_test/errors/body").contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"email":"private-invalid-email","password":""}
				"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.message").value("입력값을 확인해주세요."))
			.andExpect(jsonPath("$.errors.length()").value(2))
			.andExpect(jsonPath("$.errors[*].field", hasItems("email", "password")))
			.andExpect(jsonPath("$.errors[*].message", hasItems("올바른 이메일 형식이 아닙니다.", "비밀번호를 입력해주세요.")))
			.andReturn();
		var body = objectMapper.readTree(result.getResponse().getContentAsByteArray());
		assertThat(body.size()).isEqualTo(4);
		body.get("errors").forEach(error -> assertThat(error.size()).isEqualTo(2));
		assertThat(body.toString()).doesNotContain("private-invalid-email", "rejectedValue", "data", "success");
	}

	@Test
	void malformedJsonReturnsSafeBadRequest() throws Exception {
		var result = mockMvc.perform(post("/_test/errors/body").contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"private-value\""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.errors").doesNotExist())
			.andReturn();
		assertThat(result.getResponse().getContentAsString()).doesNotContain("private-value", "JsonEOFException");
	}

	@Test
	void missingBodyReturnsBadRequest() throws Exception {
		mockMvc.perform(post("/_test/errors/body").contentType(MediaType.APPLICATION_JSON))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
	}

	@Test
	void missingQueryParameterReturnsBadRequest() throws Exception {
		mockMvc.perform(get("/_test/errors/query"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
	}

	@Test
	void queryTypeMismatchReturnsBadRequestWithoutRejectedValue() throws Exception {
		var result = mockMvc.perform(get("/_test/errors/query").param("limit", "private-value"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.errors").doesNotExist())
			.andReturn();
		assertThat(result.getResponse().getContentAsString()).doesNotContain("private-value", "Integer");
	}

	@Test
	void queryConstraintUsesExternalParameterName() throws Exception {
		mockMvc.perform(get("/_test/errors/query").param("limit", "0"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[0].field").value("limit"))
			.andExpect(jsonPath("$.errors[0].message").value("1 이상이어야 합니다."));
	}

	@Test
	void pathConstraintUsesExternalParameterName() throws Exception {
		mockMvc.perform(get("/_test/errors/path/-1"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[0].field").value("gameId"));
	}

	@Test
	void modelBindingFailureDoesNotExposeConversionDetails() throws Exception {
		var result = mockMvc.perform(get("/_test/errors/model").param("limit", "private-value"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[0].field").value("limit"))
			.andExpect(jsonPath("$.errors[0].message").value("올바른 형식의 값을 입력해주세요."))
			.andReturn();
		assertThat(result.getResponse().getContentAsString()).doesNotContain("private-value", "NumberFormatException");
	}

	@Test
	void returnValueValidationIsServerErrorWithoutFieldDetails() throws Exception {
		mockMvc.perform(get("/_test/errors/invalid-response"))
			.andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
			.andExpect(jsonPath("$.errors").doesNotExist());
	}

	@Test
	@ExtendWith(OutputCaptureExtension.class)
	void unexpectedExceptionIsLoggedButNotExposed(CapturedOutput output) throws Exception {
		var result = mockMvc.perform(get("/_test/errors/unexpected"))
			.andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
			.andExpect(jsonPath("$.message").value("서버 내부 오류가 발생했습니다."))
			.andExpect(jsonPath("$.errors").doesNotExist())
			.andReturn();
		assertThat(result.getResponse().getContentAsString()).doesNotContain("internal-test-detail", "IllegalStateException");
		assertThat(output.getOut() + output.getErr()).contains("IllegalStateException: internal-test-detail");
	}

	@Test
	void missingRouteRemainsNotFound() throws Exception {
		mockMvc.perform(get("/_test/errors/missing"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@Test
	void unsupportedMethodPreservesAllowHeader() throws Exception {
		mockMvc.perform(post("/_test/errors/query"))
			.andExpect(status().isMethodNotAllowed())
			.andExpect(header().string("Allow", containsString("GET")))
			.andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
	}

	@Test
	void unsupportedContentTypeRemainsUnsupportedMediaType() throws Exception {
		mockMvc.perform(post("/_test/errors/body").contentType(MediaType.TEXT_PLAIN).content("text"))
			.andExpect(status().isUnsupportedMediaType())
			.andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
	}

	@Test
	void responseStatusExceptionPreservesStatusWithoutInternalReason() throws Exception {
		var result = mockMvc.perform(get("/_test/errors/status"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOT_FOUND"))
			.andReturn();
		assertThat(result.getResponse().getContentAsString()).doesNotContain("internal-test-detail");
	}

	@Test
	void successfulResponseIsUnchanged() throws Exception {
		mockMvc.perform(get("/_test/errors/success"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.code").value("200"))
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.responsedAt").doesNotExist());
	}

	@RestController
	static class TestController {

		@GetMapping("/_test/errors/business")
		void business() {
			throw new BusinessException(TestErrorCode.TEST_CONFLICT);
		}

		@PostMapping("/_test/errors/body")
		void body(@Valid @RequestBody SignupRequest request) {
		}

		@GetMapping("/_test/errors/query")
		void query(@RequestParam("limit") @Min(value = 1, message = "1 이상이어야 합니다.") int size) {
		}

		@GetMapping("/_test/errors/path/{gameId}")
		void path(@PathVariable("gameId") @Positive long id) {
		}

		@GetMapping("/_test/errors/model")
		void model(@Valid @ModelAttribute SearchRequest request) {
		}

		@GetMapping("/_test/errors/invalid-response")
		@Min(1)
		Integer invalidResponse() {
			return 0;
		}

		@GetMapping("/_test/errors/unexpected")
		void unexpected() {
			throw new IllegalStateException("internal-test-detail");
		}

		@GetMapping("/_test/errors/status")
		void responseStatus() {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "internal-test-detail");
		}

		@GetMapping("/_test/errors/success")
		Map<String, Object> success() {
			return Map.of("code", "200", "success", true);
		}
	}

	record SignupRequest(
		@Email(message = "올바른 이메일 형식이 아닙니다.") String email,
		@NotBlank(message = "비밀번호를 입력해주세요.") String password
	) {
	}

	record SearchRequest(@Min(1) int limit) {
	}

	private enum TestErrorCode implements ErrorCode {
		TEST_CONFLICT;

		@Override
		public HttpStatus getStatus() {
			return HttpStatus.CONFLICT;
		}

		@Override
		public String getCode() {
			return name();
		}

		@Override
		public String getMessage() {
			return "이미 처리된 요청입니다.";
		}
	}
}
