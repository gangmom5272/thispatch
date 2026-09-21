package com.ssafy.thispatch.domain.patch.dto.response;

import java.util.List;

public record PatchPlanListData(List<PlanItem> items, Page page) {

	public record PlanItem(long planId, long gameId, String gameTitle, String rawTextPreview,
		int slotCount, int unknownEntityCount, String createdAt) {
	}

	public record Page(int limit, String nextCursor, boolean hasNext, long totalCount) {
	}
}
