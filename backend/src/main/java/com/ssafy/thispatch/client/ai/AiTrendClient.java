package com.ssafy.thispatch.client.ai;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.ssafy.thispatch.global.exception.BusinessException;

@Component
public class AiTrendClient {

	private static final int MAXIMUM_PATCH_COUNT = 50;
	private final RestClient client;
	private final RestClient healthClient;
	private final boolean configured;

	@Autowired
	public AiTrendClient(AiProperties properties) {
		this(createClient(properties, properties.readTimeout()),
			createClient(properties, Duration.ofSeconds(5)), !properties.baseUrl().isBlank());
	}

	AiTrendClient(RestClient client, RestClient healthClient, boolean configured) {
		this.client = client;
		this.healthClient = healthClient;
		this.configured = configured;
	}

	public Result summarize(Request request) {
		if (request.patches().size() > MAXIMUM_PATCH_COUNT) {
			// 패치를 임의로 잘라 다른 범위의 요약을 만들지 않는다. 통계 조회는 그대로 유지한다.
			throw unavailable(new IllegalStateException("AI trend summary supports at most 50 patches"));
		}
		if (!configured) {
			throw unavailable(new IllegalStateException("AI_BASE_URL is not configured"));
		}
		try {
			Health health = healthClient.get().uri("/health").retrieve().body(Health.class);
			if (health == null || health.ready() == null) {
				throw new IllegalStateException("Invalid AI health response");
			}
			// 통계 요약은 ready=false여도 서버가 문장 틀 결과를 제공하므로 요청한다.
			Result result = client.post().uri("/trends/summarize").contentType(MediaType.APPLICATION_JSON)
				.body(request).retrieve().body(Result.class);
			if (result == null || result.appid() == null || result.appid() != request.appid()
				|| result.summary() == null || result.summary().isBlank()
				|| result.usedLlm() == null || result.clean() == null
				|| (result.usedLlm() && !result.clean()) || result.caveats() == null
				|| result.caveats().stream().anyMatch(note -> note == null || note.isBlank())) {
				throw new IllegalStateException("Invalid AI trend summary response");
			}
			return result;
		} catch (HttpClientErrorException exception) {
			if (exception.getStatusCode().value() == 400 || exception.getStatusCode().value() == 422) {
				// 서버가 만든 통계 입력의 오류다. 사용자 입력 오류나 AI 접속 장애로 숨기지 않는다.
				throw new IllegalStateException("AI rejected backend trend statistics", exception);
			}
			throw unavailable(exception);
		} catch (RestClientException | IllegalStateException exception) {
			throw unavailable(exception);
		}
	}

	private static BusinessException unavailable(Exception cause) {
		return new BusinessException(AiErrorCode.AI_UNAVAILABLE, cause);
	}

	private static RestClient createClient(AiProperties properties, Duration timeout) {
		HttpClient http = HttpClient.newBuilder().connectTimeout(properties.connectTimeout())
			.followRedirects(HttpClient.Redirect.NEVER).build();
		var requests = new JdkClientHttpRequestFactory(http);
		requests.setReadTimeout(timeout);
		var builder = RestClient.builder().requestFactory(requests);
		if (!properties.baseUrl().isBlank()) {
			builder.baseUrl(properties.baseUrl());
		}
		return builder.build();
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Health(Boolean ready) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	public record Request(long appid, List<Day> daily, List<Patch> patches, int windowDays, boolean useLlm) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	public record Day(@JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate date, long reviews, long positive,
		long firstReviews, long firstPositive, long editedReviews, long editedPositive) {
	}

	public record Patch(@JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate date, String title, String gid) {
	}

	// facts와 patch_effects는 AI 내부 계산 결과다. 화면의 기존 summary.text 계약에 필요한 값만 읽는다.
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	public record Result(Long appid, String summary, List<String> caveats, Boolean usedLlm, Boolean clean) {
	}
}
