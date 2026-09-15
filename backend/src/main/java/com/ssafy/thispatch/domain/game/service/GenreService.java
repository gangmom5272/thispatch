package com.ssafy.thispatch.domain.game.service;

import static com.ssafy.thispatch.domain.game.exception.GenreErrorCode.GENRE_LIST_UNAVAILABLE;

import java.util.List;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

import com.ssafy.thispatch.domain.game.dto.response.GenreListResponse;
import com.ssafy.thispatch.domain.game.entity.Tag;
import com.ssafy.thispatch.domain.game.repository.TagRepository;
import com.ssafy.thispatch.global.exception.BusinessException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class GenreService {

	private final TagRepository tagRepository;

	public GenreListResponse getGenres() {
		List<Tag> tags;
		try {
			// Repository의 읽기 트랜잭션 시작·종료 실패까지 503으로 변환한다.
			tags = tagRepository.findAllByOrderByTagIdAsc();
		} catch (DataAccessException | TransactionException exception) {
			throw new BusinessException(GENRE_LIST_UNAVAILABLE, exception);
		}
		return GenreListResponse.success(tags);
	}
}
