package com.ssafy.thispatch.client.ai;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.ssafy.thispatch.global.exception.BusinessException;

@Component
public class AiReviewClient {

	private final RestClient client;
	private final RestClient healthClient;
	private final boolean configured;

	@Autowired
	public AiReviewClient(AiProperties properties) {
		this(createClient(properties, properties.readTimeout()),
			createClient(properties, Duration.ofSeconds(5)), !properties.baseUrl().isBlank());
	}

	AiReviewClient(RestClient client, RestClient healthClient, boolean configured) {
		this.client = client;
		this.healthClient = healthClient;
		this.configured = configured;
	}

	public Health health() {
		requireConfigured();
		try {
			Health response = healthClient.get().uri("/health").retrieve().body(Health.class);
			if (response == null) {
				throw new IllegalStateException("AI health response is empty");
			}
			return response;
		} catch (RestClientException | IllegalStateException exception) {
			throw failure(exception);
		}
	}

	public Summary summarize(SummaryRequest request) {
		if (request.reviews() == null || request.reviews().size() < 3 || request.reviews().size() > 40) {
			throw new IllegalArgumentException("AI summary requires 3 to 40 reviews");
		}
		if (!health().ready()) {
			throw failure(new IllegalStateException("AI models are not ready"));
		}
		try {
			Summary response = client.post().uri("/reviews/summarize")
				.contentType(MediaType.APPLICATION_JSON).body(request).retrieve().body(Summary.class);
			validateResponse(request, response);
			return response;
		} catch (RestClientException | IllegalStateException exception) {
			// AI의 오류 본문이나 접속 주소는 화면 응답에 포함하지 않는다.
			throw failure(exception);
		}
	}

	private void requireConfigured() {
		if (!configured) {
			throw failure(new IllegalStateException("AI_BASE_URL is not configured"));
		}
	}

	private static void validateResponse(SummaryRequest request, Summary response) {
		Set<Long> reviewIds = request.reviews().stream().map(Review::reviewId).collect(Collectors.toSet());
		if (response == null || !response.clean() || response.summary() == null || response.summary().isBlank()
			|| response.appid() != request.appid() || !Objects.equals(response.scopeType(), request.scopeType())
			|| !Objects.equals(response.scopeKey(), request.scopeKey())
			|| response.reviewCount() != request.reviews().size() || response.phrases() == null
			|| response.phrases().stream().anyMatch(Objects::isNull) || response.evidenceIds() == null
			|| !reviewIds.containsAll(response.evidenceIds())) {
			throw new IllegalStateException("Invalid AI summary response");
		}
	}

	private static BusinessException failure(Exception cause) {
		return new BusinessException(AiErrorCode.AI_UNAVAILABLE, cause);
	}

	private static RestClient createClient(AiProperties properties, Duration readTimeout) {
		// AI 서버는 HTTP/2 전환 요청에서 스트리밍 본문을 읽지 못하므로 HTTP/1.1로 전송한다.
		HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(properties.connectTimeout())
			.followRedirects(HttpClient.Redirect.NEVER).build();
		var requests = new JdkClientHttpRequestFactory(http);
		requests.setReadTimeout(readTimeout);
		var builder = RestClient.builder().requestFactory(requests);
		if (!properties.baseUrl().isBlank()) {
			builder.baseUrl(properties.baseUrl());
		}
		return builder.build();
	}

	public record Health(boolean ready) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	public record Review(long reviewId, String reviewText, boolean votedUp, int votesUp,
		String languageCode, Integer playtimeAtReview) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	public record SummaryRequest(long appid, String game, String scopeType, String scopeKey, List<Review> reviews) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	public record Summary(long appid, String scopeType, String scopeKey, String title, String summary,
		List<String> phrases, List<Long> evidenceIds, int reviewCount, String model, int attempts,
		boolean clean, long elapsedMs) {
	}
}
