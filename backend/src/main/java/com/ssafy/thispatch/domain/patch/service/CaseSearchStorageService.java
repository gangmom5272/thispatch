package com.ssafy.thispatch.domain.patch.service;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.thispatch.common.TimeRule;
import com.ssafy.thispatch.domain.patch.dto.request.CaseSearchRequest.ConfirmedSlot;
import com.ssafy.thispatch.domain.patch.exception.PatchErrorCode;
import com.ssafy.thispatch.domain.patch.repository.CaseSearchStorageRepository;
import com.ssafy.thispatch.domain.patch.repository.CaseSearchStorageRepository.PlanState;
import com.ssafy.thispatch.global.exception.BusinessException;
import com.ssafy.thispatch.global.exception.CommonErrorCode;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CaseSearchStorageService {

	private final CaseSearchStorageRepository repository;

	public void validate(long memberId, long gameId, long planId) {
		var plan = repository.findOwnedPlan(memberId, planId)
			.orElseThrow(() -> new BusinessException(PatchErrorCode.PATCH_PLAN_NOT_FOUND));
		validateGame(plan, gameId);
	}

	// 모든 AI 호출이 성공한 뒤에만 잠근다. 대기하던 요청은 갱신된 완료 상태로 재검색을 저장한다.
	@Transactional
	public void save(long memberId, long gameId, long planId, List<Integer> genreIds, List<ConfirmedSlot> slots) {
		var plan = repository.lockOwnedPlan(memberId, planId)
			.orElseThrow(() -> new BusinessException(PatchErrorCode.PATCH_PLAN_NOT_FOUND));
		validateGame(plan, gameId);
		var storedAt = OffsetDateTime.now(TimeRule.ZONE);
		long savedPlanId = planId;
		if (plan.completed()) {
			savedPlanId = repository.copyPlan(planId, storedAt);
			repository.copyStructure(planId, savedPlanId);
		}
		for (int genreId : genreIds.stream().distinct().toList()) {
			repository.insertGenre(savedPlanId, genreId);
		}
		for (int index = 0; index < slots.size(); index++) {
			repository.insertConfirmedSlot(savedPlanId, index + 1, slots.get(index), storedAt);
		}
		if (!plan.completed()) repository.completePlan(savedPlanId, storedAt);
	}

	private void validateGame(PlanState plan, long gameId) {
		if (plan.gameId() != gameId) throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
	}
}
