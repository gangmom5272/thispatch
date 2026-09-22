package com.ssafy.thispatch.domain.patch.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import com.ssafy.thispatch.client.ai.AiPatchClient;
import com.ssafy.thispatch.client.ai.AiPatchContracts.*;
import com.ssafy.thispatch.domain.patch.dto.PatchChangeCodes.*;
import com.ssafy.thispatch.domain.patch.dto.request.CaseSearchRequest;
import com.ssafy.thispatch.domain.patch.dto.request.CaseSearchRequest.*;
import com.ssafy.thispatch.domain.patch.repository.PatchSearchRepository;
import com.ssafy.thispatch.domain.patch.repository.PatchSearchRepository.*;

class CaseSearchServiceTest {
	private final PatchSearchRepository repository = mock(PatchSearchRepository.class);
	private final AiPatchClient ai = mock(AiPatchClient.class);
	private final CaseSearchStorageService storage = mock(CaseSearchStorageService.class);
	private final CaseSearchService service = new CaseSearchService(repository, ai, storage);
	private final ConfirmedSlot slot = new ConfirmedSlot(new Target("Axebot", TargetRole.ENEMY), "HP",
		ChangeType.MODIFY, Direction.INCREASE, "+20%", "hard mode");

	@BeforeEach
	void setup() {
		when(repository.findGame(1)).thenReturn(Optional.of(new GameContext(1, "Game", List.of())));
		when(repository.findGenres(anyList())).thenReturn(List.of(new Genre(7, "RPG")));
		when(ai.embed(any())).thenReturn(new EmbeddingResponse(List.of(List.of(1.0)), 512, "test-model"));
		when(ai.cards(any())).thenAnswer(invocation -> {
			CardsRequest request = invocation.getArgument(0);
			return new CardsResponse(request.cases().stream().map(item -> new Card(item.gid(), "공통 " + item.gid(), "차이")).toList(), List.of(), null);
		});
		when(ai.compare(any())).thenAnswer(invocation -> {
			CompareRequest request = invocation.getArgument(0);
			assertThat(request.useLlm()).isFalse();
			assertThat(request.plan().changes().get(0).values()).isEqualTo("+20%");
			return new CompareResponse(request.caseInput().gid(), List.of(new Point("방향", "공통 설명", "template")), List.of(), false, 0);
		});
	}

	@Test
	void fillsAllGroupsAtThreePointBoundariesAndKeepsNullableCycles() {
		when(repository.search(anyList(), anyString(), anyList(), anyList())).thenReturn(List.of(
			candidate("negative", "-3", .8, 10), candidate("neutral", "2.99", .7, 11), candidate("positive", "3", .9, 12)));
		var data = service.search(9, 1, new CaseSearchRequest(101L, List.of(slot), List.of(7, 7), null)).data();
		assertThat(data.genreIds()).containsExactly(7);
		assertThat(data.sort()).isEqualTo(Sort.SIMILARITY_DESC);
		assertThat(data.totalCount()).isEqualTo(3);
		assertThat(data.groups()).extracting(group -> group.outcome()).containsExactly(CaseOutcome.values());
		var item = data.groups().get(0).cases().get(0);
		assertThat(item.patchedOn().toString()).isEqualTo("2026-09-16");
		assertThat(item.similarity()).isEqualTo(80);
		assertThat(item.reviewCount()).isEqualTo(10);
		assertThat(item.avgPatchIntervalDays()).isEqualTo(12);
		assertThat(item.nextPatchIntervalDays()).isNull();
		assertThat(item.followUpSpeedRatio()).isNull();
		assertThat(item.comparison().commonalities().get(0).description()).isEqualTo("공통 설명");
		assertThat(data.groups()).allSatisfy(group -> assertThat(group.observedPatterns()).isEmpty());
		verify(repository).search(anyList(), eq("test-model"), eq(List.of(slot)), eq(List.of(7)));
		var order = inOrder(storage, ai);
		order.verify(storage).validate(9, 1, 101);
		order.verify(ai).embed(any());
		order.verify(ai).cards(any());
		order.verify(ai).compare(any());
		order.verify(ai).cards(any());
		order.verify(ai).compare(any());
		order.verify(ai).cards(any());
		order.verify(ai).compare(any());
		order.verify(storage).save(9, 1, 101, List.of(7), List.of(slot));
	}

	@ParameterizedTest
	@EnumSource(Sort.class)
	void sortsCasesWithinTheirGroup(Sort sort) {
		var first = candidate("a", "4", .9, 10);
		var second = candidate("b", "6", .8, 30);
		when(repository.search(anyList(), anyString(), anyList(), anyList())).thenReturn(List.of(second, first));
		var cases = service.search(9, 1, new CaseSearchRequest(101L, List.of(slot), List.of(7), sort)).data().groups().get(2).cases();
		String expectedFirst = sort == Sort.REVIEW_COUNT_DESC || sort == Sort.ABS_DELTA_PP_DESC ? "b" : "a";
		assertThat(cases.get(0).patchId()).isEqualTo(expectedFirst);
	}

	@Test
	void emptyResultsDoNotCallCardsOrComparison() {
		when(repository.search(anyList(), anyString(), anyList(), anyList())).thenReturn(List.of());
		var data = service.search(9, 1, new CaseSearchRequest(101L, List.of(slot), List.of(7), null)).data();
		assertThat(data.totalCount()).isZero();
		assertThat(data.groups()).hasSize(3).allSatisfy(group -> assertThat(group.cases()).isEmpty());
		verify(ai, never()).cards(any());
		verify(ai, never()).compare(any());
		verify(storage).save(9, 1, 101, List.of(7), List.of(slot));
	}

	@ParameterizedTest
	@org.junit.jupiter.params.provider.ValueSource(strings = {"embedding", "search", "cards", "compare"})
	void failedSearchDoesNotStoreConfirmedInput(String phase) {
		when(repository.search(anyList(), anyString(), anyList(), anyList()))
			.thenReturn(List.of(candidate("p", "-5", .8, 10)));
		var failure = new IllegalStateException("upstream-failure");
		switch (phase) {
			case "embedding" -> when(ai.embed(any())).thenThrow(failure);
			case "search" -> when(repository.search(anyList(), anyString(), anyList(), anyList())).thenThrow(failure);
			case "cards" -> doThrow(failure).when(ai).cards(any());
			case "compare" -> doThrow(failure).when(ai).compare(any());
		}
		assertThatThrownBy(() -> service.search(9, 1, new CaseSearchRequest(101L, List.of(slot), List.of(7), null)))
			.isSameAs(failure);
		verify(storage, never()).save(anyLong(), anyLong(), anyLong(), anyList(), anyList());
	}

	@Test
	void batchesCardsAndCalculatesPatternsAcrossWholeGroup() {
		List<Candidate> candidates = new ArrayList<>();
		for (int index = 0; index < 61; index++) candidates.add(candidate("p" + index, "-5", .8, index + 1));
		when(repository.search(anyList(), anyString(), anyList(), anyList())).thenReturn(candidates);
		var group = service.search(9, 1, new CaseSearchRequest(101L, List.of(slot), List.of(7), null)).data().groups().get(0);
		assertThat(group.caseCount()).isEqualTo(61);
		assertThat(group.observedPatterns()).contains("적 대상 변경이 가장 많음 (61건)");
		verify(ai, times(2)).cards(any());
		verify(ai, times(61)).compare(any());
	}

	@Test
	void preservesMagnitudeAndScopeInEmbeddingAndHandlesUndefinedCycles() {
		var change = CaseSearchService.toPlanChange(slot);
		assertThat(change.sourceSentence()).contains("Increase", "Axebot", "HP", "+20%", "hard mode");
		assertThat(change.conditions()).containsExactly("hard mode");
		assertThat(CaseSearchService.followUpRatio(12.0, 6.0)).isEqualTo(.5);
		assertThat(CaseSearchService.followUpRatio(null, 6.0)).isNull();
		assertThat(CaseSearchService.followUpRatio(0.0, 6.0)).isNull();
	}

	private Candidate candidate(String id, String delta, double similarity, long reviews) {
		var before = new BigDecimal("70");
		var change = new BigDecimal(delta);
		return new Candidate(new PatchData(id, 2, "Game", null, "Patch", Instant.parse("2026-09-15T15:30:00Z"),
			reviews, before, before.add(change), change, 12.0, null), similarity, List.of(new Genre(7, "RPG")),
			List.of(new CaseChange("modify", "increase", "enemy", "HP +20%")), false);
	}
}
