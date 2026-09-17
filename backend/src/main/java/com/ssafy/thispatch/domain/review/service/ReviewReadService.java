package com.ssafy.thispatch.domain.review.service;

import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.thispatch.domain.review.dto.response.ReviewItem;
import com.ssafy.thispatch.domain.review.repository.ReviewReadRepository;
import com.ssafy.thispatch.domain.review.repository.ReviewReadRepository.ReviewRow;
import com.ssafy.thispatch.domain.statistics.dto.response.AnalysisMeta.CollectionMeta;
import com.ssafy.thispatch.domain.statistics.dto.response.AnalysisMeta.ReviewListMeta;
import com.ssafy.thispatch.domain.statistics.service.AnalysisContext;
import com.ssafy.thispatch.global.exception.BusinessException;
import com.ssafy.thispatch.global.exception.CommonErrorCode;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ReviewReadService {

	private final ReviewReadRepository repository;
	private final AnalysisContext context;

	public ReviewPage getReviews(long gameId, Set<Integer> topicIds, String cursor, int limit) {
		if (limit < 1 || limit > 100 || topicIds.stream().anyMatch(id -> id == null || id < 1 || id > Short.MAX_VALUE)) {
			throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
		}
		var period = context.recentPeriod();
		var position = ReviewCursor.decode(cursor, gameId, period.endDate(), topicIds);
		context.requireGame(gameId);
		if (!repository.topicsExist(topicIds)) {
			throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
		}
		var rows = repository.findHelpfulReviews(gameId, period, topicIds, position, limit + 1);
		boolean hasNext = rows.size() > limit;
		var visible = hasNext ? rows.subList(0, limit) : rows;
		String nextCursor = hasNext
			? ReviewCursor.encode(gameId, period.endDate(), topicIds, visible.get(visible.size() - 1)) : null;
		return new ReviewPage(ReviewListMeta.of(period), items(visible),
			new Page(limit, nextCursor, hasNext, repository.countWithinPeriod(gameId, period, topicIds)));
	}

	public RepresentativeReviews getRepresentatives(long gameId) {
		context.requireGame(gameId);
		var period = context.recentPeriod();
		return new RepresentativeReviews(CollectionMeta.of(period), items(repository.findRepresentatives(gameId, period)));
	}

	private List<ReviewItem> items(List<ReviewRow> rows) {
		var tags = repository.findTags(rows.stream().map(ReviewRow::id).toList());
		return rows.stream().map(row -> ReviewItem.of(row, tags.getOrDefault(row.id(), List.of()))).toList();
	}

	public record Page(int limit, String nextCursor, boolean hasNext, long totalCount) {
	}

	public record ReviewPage(ReviewListMeta meta, List<ReviewItem> items, Page page) {
	}

	public record RepresentativeReviews(CollectionMeta meta, List<ReviewItem> items) {
	}
}
