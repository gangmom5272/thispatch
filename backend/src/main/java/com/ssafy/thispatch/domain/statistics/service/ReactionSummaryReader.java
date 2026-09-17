package com.ssafy.thispatch.domain.statistics.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.thispatch.client.ai.AiTrendClient;
import com.ssafy.thispatch.domain.statistics.repository.DailyStatisticsRepository;
import com.ssafy.thispatch.domain.statistics.repository.ReactionPatchRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ReactionSummaryReader {

	private final AnalysisContext context;
	private final DailyStatisticsRepository statistics;
	private final ReactionPatchRepository patches;

	// 같은 시점의 통계·패치를 조회한 뒤, AI 응답을 기다리기 전에 DB 연결을 반환한다.
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public AiTrendClient.Request read(long gameId, ReviewPeriod period) {
		context.requireGame(gameId);
		List<AiTrendClient.Day> daily = statistics.findWithinPeriod(gameId, period).stream()
			.map(row -> new AiTrendClient.Day(row.date(), row.reviewCount(), row.positiveCount(),
				row.firstWrittenCount(), row.firstWrittenPositiveCount(), row.updatedCount(), row.updatedPositiveCount()))
			.toList();
		List<AiTrendClient.Patch> selectedPatches = patches.findWithinPeriod(gameId, period).stream()
			.map(patch -> new AiTrendClient.Patch(patch.patchedOn(), patch.title(), patch.id())).toList();
		return new AiTrendClient.Request(gameId, daily, selectedPatches, 7, true);
	}
}
