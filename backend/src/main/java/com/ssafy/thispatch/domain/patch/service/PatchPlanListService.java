package com.ssafy.thispatch.domain.patch.service;

import java.time.format.DateTimeFormatter;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.thispatch.common.TimeRule;
import com.ssafy.thispatch.domain.patch.dto.response.PatchPlanListData;
import com.ssafy.thispatch.domain.patch.dto.response.PatchPlanListData.Page;
import com.ssafy.thispatch.domain.patch.dto.response.PatchPlanListData.PlanItem;
import com.ssafy.thispatch.domain.patch.repository.PatchPlanListRepository;
import com.ssafy.thispatch.domain.patch.service.PatchPlanListCursorCodec.Boundary;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PatchPlanListService {

	private final PatchPlanListRepository repository;
	private final PatchPlanListCursorCodec cursors;

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public PatchPlanListData getPlans(long memberId, Long gameId, int limit, String cursor) {
		Boundary boundary = cursors.decode(cursor, memberId, gameId);
		long totalCount = repository.count(memberId, gameId);
		var rows = repository.findPage(memberId, gameId, limit, boundary);
		boolean hasNext = rows.size() > limit;
		var pageRows = rows.subList(0, Math.min(rows.size(), limit));
		var items = pageRows.stream().map(row -> new PlanItem(row.planId(), row.gameId(), row.gameTitle(),
			row.rawTextPreview(), row.slotCount(), row.unknownEntityCount(),
			row.createdAt().atZoneSameInstant(TimeRule.ZONE).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))).toList();
		String nextCursor = null;
		if (hasNext) {
			var last = pageRows.get(pageRows.size() - 1);
			nextCursor = cursors.encode(memberId, gameId, new Boundary(last.planId(), last.createdAt()));
		}
		return new PatchPlanListData(items, new Page(limit, nextCursor, hasNext, totalCount));
	}
}
