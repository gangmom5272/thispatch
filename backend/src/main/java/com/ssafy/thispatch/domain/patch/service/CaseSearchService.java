package com.ssafy.thispatch.domain.patch.service;

import static com.ssafy.thispatch.domain.game.exception.GameDetailErrorCode.GAME_NOT_FOUND;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

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

@Service
@RequiredArgsConstructor
public class CaseSearchService {
	private static final int CARD_BATCH_SIZE = 60;
	private final PatchSearchRepository repository;
	private final AiPatchClient ai;
	private final CaseSearchStorageService storage;

	public CaseSearchResponse search(long memberId, long gameId, CaseSearchRequest request) {
		repository.findGame(gameId).orElseThrow(() -> new BusinessException(GAME_NOT_FOUND));
		storage.validate(memberId, gameId, request.planId());
		var genreIds = request.genreIds().stream().distinct().toList();
		var genres = repository.findGenres(genreIds);
		if (genres.size() != genreIds.size()) throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
		var planChanges = request.confirmedSlots().stream().map(CaseSearchService::toPlanChange).toList();
		var plan = new PlanSlots(planChanges, genres.stream().map(Genre::name).toList());
		var sentences = planChanges.stream().map(PlanChange::sourceSentence).toList();
		var embedding = ai.embed(new EmbeddingRequest("", sentences));
		var candidates = new ArrayList<>(repository.search(embedding.embeddings(), embedding.model(),
			request.confirmedSlots(), genreIds));
		candidates.sort(order(request.sort()));

		List<Group> groups = new ArrayList<>();
		List<String> notices = new ArrayList<>(List.of(
			"유사도는 변경 슬롯 임베딩의 코사인 유사도(0~100)이며 성공 확률이 아닙니다.",
			"후속 배수는 해당 패치~다음 패치 간격 / 평균 패치 주기입니다.",
			"급변은 패치 전후 긍정률 차이가 ±3%p 이상인 경우입니다. 인과관계를 의미하지 않습니다."));
		for (CaseOutcome outcome : CaseOutcome.values()) {
			var groupCandidates = candidates.stream().filter(candidate ->
				CaseOutcome.fromDelta(candidate.patch().deltaPp()) == outcome).toList();
			var inputs = groupCandidates.stream().map(CaseSearchService::toCaseInput).toList();
			var cards = fetchCards(plan, inputs);
			List<SimilarCase> cases = new ArrayList<>();
			for (int index = 0; index < groupCandidates.size(); index++) {
				var candidate = groupCandidates.get(index);
				// 검색 응답에 모든 비교를 담으므로 건별 LLM 추론은 하지 않고 기존 문장 틀을 사용한다.
				var comparison = ai.compare(new CompareRequest(plan, inputs.get(index), false));
				cases.add(toSimilarCase(candidate, cards.get(candidate.patch().gid()), comparison));
			}
			groups.add(new Group(outcome, outcome.displayName(), cases.size(), CasePatterns.summarize(inputs), cases));
			if (!cases.isEmpty() && cases.size() < CasePatterns.MIN_CASES) {
				notices.add(outcome.displayName() + " " + cases.size() + "건: 표본이 적어 공통 패턴을 요약하지 않습니다.");
			}
		}
		if (candidates.stream().anyMatch(Candidate::changesTruncated)) {
			notices.add("변경점이 200개를 넘는 패치의 비교는 검색에 매칭된 청크를 우선한 최대 200개를 사용합니다.");
		}
		var response = CaseSearchResponse.success(new SearchData("COMPLETED", gameId, request.confirmedSlots(), genreIds,
			request.sort(), candidates.size(), groups, notices));
		storage.save(memberId, gameId, request.planId(), genreIds, request.confirmedSlots());
		return response;
	}

	private Map<String, Card> fetchCards(PlanSlots plan, List<CaseInput> cases) {
		Map<String, Card> cards = new LinkedHashMap<>();
		for (int start = 0; start < cases.size(); start += CARD_BATCH_SIZE) {
			var batch = cases.subList(start, Math.min(cases.size(), start + CARD_BATCH_SIZE));
			// AI 호출 제한에 맞춰 카드만 분할한다. 결과군 패턴은 전체 사례로 한 번 계산한다.
			var response = ai.cards(new CardsRequest(plan, batch, Integer.MAX_VALUE));
			for (Card card : response.cards()) cards.put(card.gid(), card);
		}
		return cards;
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
