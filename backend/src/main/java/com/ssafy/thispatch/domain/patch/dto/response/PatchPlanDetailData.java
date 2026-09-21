package com.ssafy.thispatch.domain.patch.dto.response;

import java.util.List;

import com.ssafy.thispatch.domain.patch.dto.request.CaseSearchRequest.ConfirmedSlot;

public record PatchPlanDetailData(long planId, long gameId, String gameTitle, String rawText,
	Restatement restatement, List<Integer> genreIds, List<ConfirmedSlot> confirmedSlots, String createdAt) {

	public record Restatement(String text) {
	}
}
