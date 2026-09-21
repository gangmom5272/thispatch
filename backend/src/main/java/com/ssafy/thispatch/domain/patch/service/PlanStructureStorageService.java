package com.ssafy.thispatch.domain.patch.service;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.thispatch.common.TimeRule;
import com.ssafy.thispatch.domain.patch.dto.PatchChangeCodes.TargetRole;
import com.ssafy.thispatch.domain.patch.dto.response.PlanStructureResponse.Entity;
import com.ssafy.thispatch.domain.patch.dto.response.PlanStructureResponse.Restatement;
import com.ssafy.thispatch.domain.patch.dto.response.PlanStructureResponse.Slot;
import com.ssafy.thispatch.domain.patch.repository.PlanStructureStorageRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PlanStructureStorageService {

	private final PlanStructureStorageRepository repository;

	// AI 호출과 응답 변환이 끝난 뒤 최초 구조화 정보만 하나의 트랜잭션으로 저장한다.
	@Transactional
	public long save(long memberId, long gameId, String rawText, List<Entity> entities,
		List<Slot> slots, Restatement restatement) {
		var storedAt = OffsetDateTime.now(TimeRule.ZONE);
		long planId = repository.insertPlan(memberId, gameId, rawText, storedAt);
		Map<EntityKey, Long> entityIds = new HashMap<>();
		for (Entity entity : entities) {
			var key = new EntityKey(entity.name(), entity.role());
			if (entityIds.containsKey(key)) continue;
			// 응답 경고에는 역할이 없으므로 같은 이름의 정상 대상에 경고를 연결하지 않는다.
			var warning = entity.role() == TargetRole.UNKNOWN || entity.name().isBlank()
				? restatement.warnings().stream()
					.filter(value -> "UNKNOWN_ENTITY".equals(value.code()) && entity.name().equals(value.entityName()))
					.findFirst().orElse(null)
				: null;
			long entityId = repository.insertEntity(planId, entityIds.size() + 1, entity, warning);
			entityIds.put(key, entityId);
		}
		for (int index = 0; index < slots.size(); index++) {
			Slot slot = slots.get(index);
			long entityId = entityIds.get(new EntityKey(slot.targetName(), slot.targetRole()));
			repository.insertSlot(entityId, index + 1, slot);
		}
		var warning = restatement.warnings().stream()
			.filter(value -> "NO_CHANGES".equals(value.code())).findFirst().orElse(null);
		repository.insertRestatement(planId, restatement.text(), warning, storedAt);
		return planId;
	}

	private record EntityKey(String name, TargetRole role) {
	}
}
