package com.ssafy.thispatch.domain.patch.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import com.ssafy.thispatch.client.ai.AiPatchClient;
import com.ssafy.thispatch.client.ai.AiPatchContracts.*;
import com.ssafy.thispatch.domain.patch.dto.request.PlanStructureRequest;
import com.ssafy.thispatch.domain.patch.repository.PatchSearchRepository;
import com.ssafy.thispatch.domain.patch.repository.PatchSearchRepository.*;
import com.ssafy.thispatch.global.exception.BusinessException;

class PlanStructureServiceTest {
	private final PatchSearchRepository repository = mock(PatchSearchRepository.class);
	private final AiPatchClient ai = mock(AiPatchClient.class);
	private final PlanStructureStorageService storage = mock(PlanStructureStorageService.class);
	private final PlanStructureService service = new PlanStructureService(repository, ai, storage);

	@Test
	void mapsAiChangesWithoutDroppingTypeValuesConditionsOrServerGenres() {
		when(repository.findGame(1)).thenReturn(Optional.of(new GameContext(1, "Game", List.of(new Genre(3, "RPG")))));
		when(ai.structure(any())).thenReturn(new PlanResponse(List.of(
			new Change(1, "modify", "increase", "enemy", "Axebot", "health", "+20%", List.of("hard mode", "level 4"), "체력 상향", "source"),
			new Change(2, "add", "none", "enemy", "Axebot", null, null, List.of(), "적 추가", "source")), "qwen", "v1", 1));
		when(storage.save(eq(7L), eq(1L), anyString(), anyList(), anyList(), any())).thenReturn(101L);
		var data = service.structure(7, 1, new PlanStructureRequest("기획안 원문입니다")).data();
		assertThat(data.planId()).isEqualTo(101);
		assertThat(data.genreIds()).containsExactly(3);
		assertThat(data.entities()).hasSize(1);
		assertThat(data.entities().get(0).source()).isEqualTo("AI");
		assertThat(data.slots().get(0).changeType().name()).isEqualTo("MODIFY");
		assertThat(data.slots().get(1).changeType().name()).isEqualTo("ADD");
		assertThat(data.slots().get(0).attribute()).isEqualTo("health");
		assertThat(data.slots().get(0).magnitude()).isEqualTo("+20%");
		assertThat(data.slots().get(0).scope()).isEqualTo("hard mode, level 4");
		assertThat(data.restatement().text()).isEqualTo("체력 상향\n적 추가");
		assertThat(data.restatement().highlights().direction()).isEqualTo("MIXED");
		verify(ai).structure(new PlanRequest("", "기획안 원문입니다"));
		var order = inOrder(ai, storage);
		order.verify(ai).structure(any());
		order.verify(storage).save(7, 1, data.rawText(), data.entities(), data.slots(), data.restatement());
	}

	@Test
	void keepsUnknownTargetsAndEmptyResultsExplicit() {
		when(repository.findGame(1)).thenReturn(Optional.of(new GameContext(1, "Game", List.of())));
		when(ai.structure(any())).thenReturn(new PlanResponse(List.of(
			new Change(1, "fix", "not_applicable", "unknown", null, null, null, List.of(), "수정", "source")), "qwen", "v1", 1));
		var data = service.structure(7, 1, new PlanStructureRequest("버그를 고칩니다")).data();
		assertThat(data.slots()).hasSize(1);
		assertThat(data.slots().get(0).targetName()).isEmpty();
		assertThat(data.restatement().warnings().get(0).code()).isEqualTo("UNKNOWN_ENTITY");
		when(ai.structure(any())).thenReturn(new PlanResponse(List.of(), "qwen", "v1", 1));
		var empty = service.structure(7, 1, new PlanStructureRequest("추출할 변경점 없음")).data();
		assertThat(empty.slots()).isEmpty();
		assertThat(empty.restatement().warnings().get(0).code()).isEqualTo("NO_CHANGES");
	}

	@Test
	void missingGameDoesNotCallAi() {
		assertThatThrownBy(() -> service.structure(7, 1, new PlanStructureRequest("anything")))
			.isInstanceOf(BusinessException.class).hasMessage("게임을 찾을 수 없습니다.");
		verifyNoInteractions(ai, storage);
	}
}
