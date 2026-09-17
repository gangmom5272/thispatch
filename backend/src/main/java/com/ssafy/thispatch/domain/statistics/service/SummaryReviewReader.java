package com.ssafy.thispatch.domain.statistics.service;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.thispatch.domain.review.dto.response.ReviewItem.Tag;
import com.ssafy.thispatch.domain.review.repository.ReviewReadRepository;
import com.ssafy.thispatch.domain.review.repository.ReviewReadRepository.ReviewRow;
import com.ssafy.thispatch.domain.statistics.repository.SummaryReviewRepository;
import com.ssafy.thispatch.domain.statistics.repository.SummaryReviewRepository.SummaryReview;
import com.ssafy.thispatch.global.exception.BusinessException;
import com.ssafy.thispatch.global.exception.CommonErrorCode;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SummaryReviewReader {

	private final AnalysisContext context;
	private final SummaryReviewRepository repository;
	private final ReviewReadRepository reviews;

	// 느린 AI 응답을 기다리는 동안 DB 연결을 점유하지 않도록 조회 트랜잭션을 분리한다.
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public Input read(long gameId, ReviewPeriod period, Integer bandNo, String languageCode, int limit) {
		context.requireGame(gameId);
		if (languageCode != null && !repository.languageExists(languageCode)) {
			throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
		}
		List<SummaryReview> selected = repository.findSelected(gameId, period, bandNo, languageCode, limit);
		List<ReviewRow> rows = selected.stream().map(SummaryReview::review).toList();
		Map<Long, List<Tag>> tags = languageCode == null ? Map.of()
			: reviews.findTags(rows.stream().limit(4).map(ReviewRow::id).toList());
		return new Input(repository.gameName(gameId), selected.isEmpty() ? 0 : selected.get(0).targetCount(),
			rows, tags);
	}

	public record Input(String gameName, long targetCount, List<ReviewRow> reviews, Map<Long, List<Tag>> tags) {
	}
}
