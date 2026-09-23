package com.ssafy.thispatch.domain.patch.service;

import static com.ssafy.thispatch.domain.patch.exception.PatchErrorCode.PATCH_PLAN_NOT_FOUND;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.thispatch.domain.patch.repository.PatchPlanDeleteRepository;
import com.ssafy.thispatch.global.exception.BusinessException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PatchPlanDeleteService {

	private final PatchPlanDeleteRepository repository;

	@Transactional
	public void deletePlan(long memberId, long planId) {
		if (!repository.lockOwnedCompletedPlan(memberId, planId)) {
			throw new BusinessException(PATCH_PLAN_NOT_FOUND);
		}
		repository.deletePlanAndChildren(planId);
	}
}
