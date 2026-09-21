package com.ssafy.thispatch.domain.patch.service;

import static com.ssafy.thispatch.domain.patch.exception.PatchErrorCode.PATCH_PLAN_NOT_FOUND;

import java.time.format.DateTimeFormatter;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.thispatch.common.TimeRule;
import com.ssafy.thispatch.domain.patch.dto.response.PatchPlanDetailData;
import com.ssafy.thispatch.domain.patch.dto.response.PatchPlanDetailData.Restatement;
import com.ssafy.thispatch.domain.patch.repository.PatchPlanDetailRepository;
import com.ssafy.thispatch.global.exception.BusinessException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PatchPlanDetailService {

	private final PatchPlanDetailRepository repository;

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public PatchPlanDetailData getPlan(long memberId, long planId) {
		var plan = repository.findOwnedPlan(memberId, planId)
			.orElseThrow(() -> new BusinessException(PATCH_PLAN_NOT_FOUND));
		return new PatchPlanDetailData(plan.planId(), plan.gameId(), plan.gameTitle(), plan.rawText(),
			new Restatement(plan.restatement()), repository.findGenreIds(planId), repository.findConfirmedSlots(planId),
			plan.createdAt().atZoneSameInstant(TimeRule.ZONE).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
	}
}
