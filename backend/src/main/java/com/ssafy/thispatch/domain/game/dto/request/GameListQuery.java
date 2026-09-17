package com.ssafy.thispatch.domain.game.dto.request;

import static com.ssafy.thispatch.global.exception.CommonErrorCode.INVALID_REQUEST;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import com.ssafy.thispatch.global.exception.BusinessException;

public record GameListQuery(String search, GameListSort sort, int limit, List<Integer> genreIds) {

	public GameListQuery {
		genreIds = List.copyOf(genreIds);
	}

	public static GameListQuery of(String search, GameListSort sort, int limit, String genreIds) {
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
		return new GameListQuery(search == null ? "" : search.strip().toLowerCase(Locale.ROOT), sort, limit, ids);
	}

	public String searchPattern() {
		return "%" + search.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
	}
}