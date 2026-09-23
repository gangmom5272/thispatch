package com.ssafy.thispatch.domain.game.dto.request;

import static com.ssafy.thispatch.global.exception.CommonErrorCode.INVALID_REQUEST;

import java.util.Arrays;
import java.util.List;

import com.ssafy.thispatch.global.exception.BusinessException;

public record GameListQuery(String search, GameListSort sort, int limit, List<Integer> genreIds,
	GameListFilters filters) {

	public GameListQuery {
		genreIds = List.copyOf(genreIds);
	}

	public static GameListQuery of(String search, GameListSort sort, int limit, String genreIds) {
		return of(search, sort, limit, genreIds, GameListFilters.NONE);
	}

	public static GameListQuery of(String search, GameListSort sort, int limit, String genreIds,
		GameListFilters filters) {
		List<Integer> ids = List.of();
		if (genreIds != null) {
			if (Arrays.stream(genreIds.split(",", -1)).anyMatch(id -> !id.matches("[0-9]+"))) {
				throw new BusinessException(INVALID_REQUEST);
			}
			try {
				ids = Arrays.stream(genreIds.split(",")).map(Integer::valueOf).distinct().sorted().toList();
			} catch (NumberFormatException exception) {
				throw new BusinessException(INVALID_REQUEST);
			}
			if (ids.stream().anyMatch(id -> id <= 0)) {
				throw new BusinessException(INVALID_REQUEST);
			}
		}
		// 제목과 검색어의 Unicode·소문자 규칙을 일치시키기 위해 정규화는 조회 전에 DB에서 수행한다.
		return new GameListQuery(search == null ? "" : search.strip(), sort, limit, ids, filters);
	}

	public String searchPattern() {
		return "%" + search.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
	}
}
