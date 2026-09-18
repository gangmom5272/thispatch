package com.ssafy.thispatch.domain.review.service;

import org.springframework.stereotype.Service;

import com.ssafy.thispatch.client.deepl.DeepLClient;
import com.ssafy.thispatch.domain.review.dto.response.ReviewTranslation;
import com.ssafy.thispatch.domain.review.exception.ReviewErrorCode;
import com.ssafy.thispatch.domain.review.repository.ReviewReadRepository;
import com.ssafy.thispatch.global.exception.BusinessException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ReviewTranslationService {

	private final ReviewReadRepository repository;
	private final DeepLClient client;

	// 단일 조회가 끝나면 DB 연결을 반환하고, 외부 번역을 트랜잭션 밖에서 기다린다.
	public ReviewTranslation translate(long reviewId) {
		var source = repository.findTranslationSource(reviewId)
			.orElseThrow(() -> new BusinessException(ReviewErrorCode.REVIEW_NOT_FOUND));
		boolean useOriginal = source.text().isBlank() || "korean".equals(source.languageCode())
			|| "koreana".equals(source.languageCode());
		String translated = useOriginal ? source.text() : client.translateToKorean(source.text());
		return new ReviewTranslation(reviewId, translated);
	}
}
