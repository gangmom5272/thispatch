package com.ssafy.thispatch.domain.patch.service;

import static com.ssafy.thispatch.domain.game.exception.GameDetailErrorCode.GAME_NOT_FOUND;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StopWatch;

import com.ssafy.thispatch.client.ai.AiPatchClient;
import com.ssafy.thispatch.client.ai.AiPatchContracts.*;
import com.ssafy.thispatch.common.TimeRule;
import com.ssafy.thispatch.domain.patch.dto.request.CaseSearchRequest;
import com.ssafy.thispatch.domain.patch.dto.request.CaseSearchRequest.ConfirmedSlot;
import com.ssafy.thispatch.domain.patch.dto.response.CaseSearchResponse;
import com.ssafy.thispatch.domain.patch.dto.response.CaseSearchResponse.*;
import com.ssafy.thispatch.domain.patch.repository.PatchSearchRepository;
import com.ssafy.thispatch.domain.patch.repository.PatchSearchRepository.Candidate;
import com.ssafy.thispatch.domain.patch.repository.PatchSearchRepository.Genre;
import com.ssafy.thispatch.global.exception.BusinessException;
import com.ssafy.thispatch.global.exception.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class CaseSearchService {
	private static final int CARD_BATCH_SIZE = 60;
	private final PatchSearchRepository repository;
	private final AiPatchClient ai;
	private final CaseSearchExecutor tasks;

	public CaseSearchResponse search(long gameId, CaseSearchRequest request) {
		var timing = new StopWatch();
		String result = "failed";
		int candidateCount = 0;
		int cardBatchCount = 0;
		timing.start("validate");
		try {
			repository.findGame(gameId).orElseThrow(() -> new BusinessException(GAME_NOT_FOUND));
			var genreIds = request.genreIds().stream().distinct().toList();
			var genres = repository.findGenres(genreIds);
			if (genres.size() != genreIds.size()) throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
			var planChanges = request.confirmedSlots().stream().map(CaseSearchService::toPlanChange).toList();
			var plan = new PlanSlots(planChanges, genres.stream().map(Genre::name).toList());
			var sentences = planChanges.stream().map(PlanChange::sourceSentence).toList();
			timing.stop();
			timing.start("embed");
			var embedding = ai.embed(new EmbeddingRequest("", sentences));
			timing.stop();
			timing.start("search");
			var candidates = new ArrayList<>(repository.search(embedding.embeddings(), embedding.model(),
				request.confirmedSlots(), genreIds));
			candidates.sort(order(request.sort()));
			candidateCount = candidates.size();
			var inputs = candidates.stream().map(CaseSearchService::toCaseInput).toList();
			var cardRequests = cardRequests(plan, candidates, inputs);
			cardBatchCount = cardRequests.size();
			timing.stop();
			timing.start("cards");
			Map<String, Card> cards = new LinkedHashMap<>();
			for (var response : tasks.map(cardRequests, ai::cards)) {
				for (Card card : response.cards()) cards.put(card.gid(), card);
			}
			timing.stop();
			timing.start("compare");
			// 모든 비교를 응답에 담되, 건별 LLM 추론 없이 기존 문장 틀을 병렬로 요청한다.
			var comparisons = tasks.map(inputs, input -> ai.compare(new CompareRequest(plan, input, false)));
			timing.stop();
			timing.start("assemble");
			var response = assemble(gameId, request, genreIds, candidates, inputs, cards, comparisons);
			result = "completed";
			return response;
		} finally {
			if (timing.isRunning()) timing.stop();
			Map<String, Long> stages = new LinkedHashMap<>();
			for (var stage : timing.getTaskInfo()) stages.put(stage.getTaskName(), stage.getTimeMillis());
			// 원문·근거 문장 대신 호출 규모와 실제 대기 시간을 남긴다. 실패한 단계도 포함한다.
			log.info("Case search finished: gameId={}, result={}, candidateCount={}, cardBatchCount={}, elapsedMs={}, stagesMs={}",
				gameId, result, candidateCount, cardBatchCount, timing.getTotalTimeMillis(), stages);
		}
	}

	private List<CardsRequest> cardRequests(PlanSlots plan, List<Candidate> candidates, List<CaseInput> inputs) {
		List<CardsRequest> requests = new ArrayList<>();
		for (CaseOutcome outcome : CaseOutcome.values()) {
			List<CaseInput> group = new ArrayList<>();
			for (int index = 0; index < candidates.size(); index++) {
				if (CaseOutcome.fromDelta(candidates.get(index).patch().deltaPp()) == outcome) group.add(inputs.get(index));
			}
			for (int start = 0; start < group.size(); start += CARD_BATCH_SIZE) {
				var batch = List.copyOf(group.subList(start, Math.min(group.size(), start + CARD_BATCH_SIZE)));
				requests.add(new CardsRequest(plan, batch, Integer.MAX_VALUE));
			}
		}
		return requests;
	}

	private CaseSearchResponse assemble(long gameId, CaseSearchRequest request, List<Integer> genreIds,
		List<Candidate> candidates, List<CaseInput> inputs, Map<String, Card> cards, List<CompareResponse> comparisons) {
		List<Group> groups = new ArrayList<>();
		List<String> notices = new ArrayList<>(List.of(
			"유사도는 변경 슬롯 임베딩의 코사인 유사도(0~100)이며 성공 확률이 아닙니다.",
			"후속 배수는 해당 패치~다음 패치 간격 / 평균 패치 주기입니다.",
			"급변은 패치 전후 긍정률 차이가 ±3%p 이상인 경우입니다. 인과관계를 의미하지 않습니다."));
		for (CaseOutcome outcome : CaseOutcome.values()) {
			List<CaseInput> groupInputs = new ArrayList<>();
			List<SimilarCase> cases = new ArrayList<>();
			for (int index = 0; index < candidates.size(); index++) {
				var candidate = candidates.get(index);
				if (CaseOutcome.fromDelta(candidate.patch().deltaPp()) != outcome) continue;
				groupInputs.add(inputs.get(index));
				cases.add(toSimilarCase(candidate, cards.get(candidate.patch().gid()), comparisons.get(index)));
			}
			groups.add(new Group(outcome, outcome.displayName(), cases.size(), CasePatterns.summarize(groupInputs), cases));
			if (!cases.isEmpty() && cases.size() < CasePatterns.MIN_CASES) {
				notices.add(outcome.displayName() + " " + cases.size() + "건: 표본이 적어 공통 패턴을 요약하지 않습니다.");
			}
		}
		if (candidates.stream().anyMatch(Candidate::changesTruncated)) {
			notices.add("변경점이 200개를 넘는 패치의 비교는 검색에 매칭된 청크를 우선한 최대 200개를 사용합니다.");
		}
		return CaseSearchResponse.success(new SearchData("COMPLETED", gameId, request.confirmedSlots(), genreIds,
			request.sort(), candidates.size(), groups, notices));
	}

	static PlanChange toPlanChange(ConfirmedSlot slot) {
		String verb = switch (slot.changeType()) {
			case ADD -> "Add";
			case REMOVE -> "Remove";
			case FIX -> "Fix";
			case DEPRECATE -> "Deprecate";
			case MODIFY -> switch (slot.direction()) {
				case INCREASE -> "Increase";
				case DECREASE -> "Decrease";
				default -> "Change";
			};
		};
		String target = slot.target().name().isBlank() ? slot.target().role().name() : slot.target().name();
		String sentence = verb + " " + target + " (" + slot.target().role().name().toLowerCase(Locale.ROOT) + ") " + slot.attribute();
		if (slot.magnitude() != null && !slot.magnitude().isBlank()) sentence += " " + slot.magnitude();
		var conditions = slot.scope() == null || slot.scope().isBlank() ? List.<String>of() : List.of(slot.scope());
		if (!conditions.isEmpty()) sentence += ". Scope: " + slot.scope();
		return new PlanChange(slot.changeType().name().toLowerCase(Locale.ROOT), slot.direction().name().toLowerCase(Locale.ROOT),
			slot.target().role().name().toLowerCase(Locale.ROOT), slot.target().name(), slot.attribute(),
			slot.magnitude(), conditions, sentence);
	}

	private static CaseInput toCaseInput(Candidate candidate) {
		var patch = candidate.patch();
		return new CaseInput(patch.gid(), patch.gameTitle(), patch.title(), candidate.genres().stream().map(Genre::name).toList(),
			candidate.changes(), new CaseStats(patch.beforeRate().doubleValue(), patch.afterRate().doubleValue(),
				patch.deltaPp().doubleValue(), patch.reviewCount()),
			new CaseCycle(patch.averageDays(), patch.nextDays(), followUpRatio(patch.averageDays(), patch.nextDays())));
	}

	private static SimilarCase toSimilarCase(Candidate candidate, Card card, CompareResponse comparison) {
		var patch = candidate.patch();
		String capsule = patch.capsulePath() == null || patch.capsulePath().isBlank() ? null
			: "https://shared.fastly.steamstatic.com/store_item_assets/steam/apps/" + patch.gameId() + "/" + patch.capsulePath();
		return new SimilarCase(patch.gameId(), patch.gameTitle(), capsule, candidate.genres().stream().map(Genre::id).toList(),
			patch.gid(), patch.title(), patch.publishedAt().atZone(TimeRule.ZONE).toLocalDate(),
			Math.round(Math.max(0, Math.min(1, candidate.cosine())) * 1000.0) / 10.0,
			patch.reviewCount(), patch.beforeRate(), patch.afterRate(), patch.deltaPp(),
			patch.averageDays(), patch.nextDays(), followUpRatio(patch.averageDays(), patch.nextDays()),
			card.common(), card.difference(), new Comparison(points(comparison.common()), points(comparison.differences())));
	}

	private static List<ComparisonItem> points(List<Point> points) {
		return points.stream().map(point -> new ComparisonItem(point.title(), point.body())).toList();
	}

	static Double followUpRatio(Double averageDays, Double nextDays) {
		return averageDays == null || averageDays <= 0 || nextDays == null ? null : nextDays / averageDays;
	}

	private static Comparator<Candidate> order(CaseSearchRequest.Sort sort) {
		Comparator<Candidate> primary = switch (sort) {
			case SIMILARITY_DESC -> Comparator.comparingDouble(Candidate::cosine).reversed();
			case REVIEW_COUNT_DESC -> Comparator.<Candidate>comparingLong(candidate -> candidate.patch().reviewCount()).reversed();
			case ABS_DELTA_PP_DESC -> Comparator.<Candidate, java.math.BigDecimal>comparing(candidate -> candidate.patch().deltaPp().abs()).reversed();
			case PATCHED_ON_DESC -> Comparator.<Candidate, java.time.Instant>comparing(candidate -> candidate.patch().publishedAt()).reversed();
		};
		return primary.thenComparing(Comparator.comparingDouble(Candidate::cosine).reversed())
			.thenComparing(candidate -> candidate.patch().gid());
	}
}
