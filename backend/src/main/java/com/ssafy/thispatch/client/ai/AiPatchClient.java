package com.ssafy.thispatch.client.ai;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.ssafy.thispatch.client.ai.AiPatchContracts.*;
import com.ssafy.thispatch.global.exception.BusinessException;


@Component
public class AiPatchClient {

	private final RestClient client;
	private final RestClient healthClient;
	private final boolean configured;

	@Autowired
	public AiPatchClient(AiProperties properties) {
		this(createClient(properties, properties.readTimeout()),
			createClient(properties, Duration.ofSeconds(5)), !properties.baseUrl().isBlank());
	}

	AiPatchClient(RestClient client, RestClient healthClient, boolean configured) {
		this.client = client;
		this.healthClient = healthClient;
		this.configured = configured;
	}

	public PlanResponse structure(PlanRequest request) {
		PlanResponse response = post("/plan/structure", request, PlanResponse.class);
		requireValid(response.changes() != null);
		for (Change change : response.changes()) {
			// AI 응답의 누락·잘못된 코드값을 화면 서비스의 NPE/enum 오류로 넘기지 않는다.
			requireValid(change != null && change.changeType() != null && change.direction() != null
				&& change.targetType() != null && change.conditions() != null
				&& change.conditions().stream().allMatch(Objects::nonNull)
				&& change.restatement() != null && change.sourceSentence() != null);
			requireValid(Set.of("add", "remove", "modify", "fix", "deprecate").contains(change.changeType())
				&& Set.of("increase", "decrease", "none", "not_applicable", "unknown").contains(change.direction())
				&& Set.of("player", "enemy", "weapon", "item", "skill", "map", "system", "other", "unknown")
					.contains(change.targetType()));
		}
		return response;
	}

	public RestateResponse restate(RestateRequest request) {
		RestateResponse response = post("/plan/restate", request, RestateResponse.class);
		requireValid(response.restatements() != null && response.restatements().stream().allMatch(Objects::nonNull) && response.summary() != null);
		return response;
	}

	public EmbeddingResponse embed(EmbeddingRequest request) {
		EmbeddingResponse response = post("/embed/query", request, EmbeddingResponse.class);
		// 저장된 patch_chunk와 같은 512차원이어야 pgvector 검색에 사용할 수 있다.
		requireValid(response.model() != null && !response.model().isBlank() && response.dim() == 512 && response.embeddings() != null
			&& response.embeddings().size() == request.sentences().size());
		for (List<Double> vector : response.embeddings()) {
			requireValid(vector != null && vector.size() == 512
				&& vector.stream().allMatch(value -> value != null && Double.isFinite(value))
				&& vector.stream().anyMatch(value -> value != 0.0));
		}
		return response;
	}

	public CardsResponse cards(CardsRequest request) {
		CardsResponse response = post("/cases/cards", request, CardsResponse.class);
		requireValid(response.cards() != null && response.patterns() != null && response.patterns().stream().allMatch(Objects::nonNull));
		List<String> expected = request.cases().stream().map(CaseInput::gid).sorted().toList();
		requireValid(response.cards().stream().allMatch(card -> card != null && card.gid() != null && card.common() != null && card.difference() != null));
		requireValid(response.cards().stream().map(Card::gid).sorted().toList().equals(expected));
		return response;
	}

	public CompareResponse compare(CompareRequest request) {
		CompareResponse response = post("/cases/compare", request, CompareResponse.class);
		requireValid(Objects.equals(response.gid(), request.caseInput().gid())
			&& validPoints(response.common()) && validPoints(response.differences()));
		return response;
	}

	private static boolean validPoints(List<Point> points) {
		return points != null && points.size() <= 3 && points.stream().allMatch(point ->
			point != null && point.title() != null && point.body() != null);
	}

	public Health health() {
		if (!configured) {
			throw failure(new IllegalStateException("AI_BASE_URL is not configured"));
		}
		try {
			Health health = healthClient.get().uri("/health").retrieve().body(Health.class);
			requireValid(health != null);
			return health;
		} catch (RestClientException exception) {
			throw failure(exception);
		}
	}

	private <T> T post(String path, Object request, Class<T> responseType) {
		requireValid(health().ready());
		try {
			T response = client.post().uri(path).contentType(MediaType.APPLICATION_JSON)
				.body(request).retrieve().body(responseType);
			requireValid(response != null);
			return response;
		} catch (RestClientException exception) {
			throw failure(exception);
		}
	}

	private static void requireValid(boolean valid) {
		if (!valid) {
			throw failure(new IllegalStateException("AI server is not ready or returned an invalid response"));
		}
	}

	private static BusinessException failure(Exception cause) {
		return new BusinessException(AiErrorCode.AI_UNAVAILABLE, cause);
	}

	private static RestClient createClient(AiProperties properties, Duration timeout) {
		// AI 서버는 HTTP/2 전환 요청에서 스트리밍 본문을 읽지 못하므로 HTTP/1.1로 전송한다.
		var http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(properties.connectTimeout())
			.followRedirects(HttpClient.Redirect.NEVER).build();
		var requests = new JdkClientHttpRequestFactory(http);
		requests.setReadTimeout(timeout);
		var builder = RestClient.builder().requestFactory(requests);
		if (!properties.baseUrl().isBlank()) {
			builder.baseUrl(properties.baseUrl());
		}
		return builder.build();
	}

	public record Health(boolean ready) {
	}
}
