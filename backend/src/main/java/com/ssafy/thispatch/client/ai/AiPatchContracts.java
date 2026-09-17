package com.ssafy.thispatch.client.ai;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** AI 내부 계약. 화면의 필드명·코드값과 다르므로 화면 응답으로 직접 반환하지 않는다. */
public final class AiPatchContracts {

	private AiPatchContracts() {
	}

	public record PlanRequest(String title, String text) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	public record Change(int changeSeq, String changeType, String direction, String targetType,
		String target, String attribute, String values, List<String> conditions, String restatement,
		String sourceSentence) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	public record PlanResponse(List<Change> changes, String model, String promptVersion, long elapsedMs) {
	}

	public record EmbeddingRequest(String title, List<String> sentences) {
	}

	public record EmbeddingResponse(List<List<Double>> embeddings, int dim, String model) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	public record PlanChange(String changeType, String direction, String targetType, String target,
		String attribute, String values, List<String> conditions, String sourceSentence) {
	}

	public record PlanSlots(List<PlanChange> changes, List<String> genres) {
	}

	public record RestateRequest(List<PlanChange> changes) {
	}

	public record RestateResponse(List<String> restatements, String summary) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	public record CaseChange(String changeType, String direction, String targetType, String evidenceQuote) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	public record CaseStats(Double beforePositivePct, Double afterPositivePct, Double deltaPct, Long reviewCount) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	public record CaseCycle(Double usualDays, Double nextPatchDays, Double ratio) {
	}

	public record CaseInput(String gid, String game, String version, List<String> genres,
		List<CaseChange> changes, CaseStats stats, CaseCycle cycle) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	public record CardsRequest(PlanSlots plan, List<CaseInput> cases, int minGroupN) {
	}

	public record Card(String gid, String common, String difference) {
	}

	public record CardsResponse(List<Card> cards, List<String> patterns, String note) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	public record CompareRequest(PlanSlots plan, @JsonProperty("case") CaseInput caseInput, boolean useLlm) {
	}

	public record Point(String title, String body, String source) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	public record CompareResponse(String gid, List<Point> common, List<Point> differences,
		boolean llmUsed, long elapsedMs) {
	}
}
