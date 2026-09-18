package com.ssafy.thispatch.domain.game.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ssafy.thispatch.domain.game.dto.request.GameListQuery;
import com.ssafy.thispatch.domain.game.dto.request.GameListFilters;
import com.ssafy.thispatch.domain.game.dto.request.GameListScope;
import com.ssafy.thispatch.domain.game.dto.request.GameListSort;
import com.ssafy.thispatch.domain.game.dto.response.MyGameListResponse;
import com.ssafy.thispatch.domain.game.service.GameListService;
import com.ssafy.thispatch.global.security.MemberPrincipal;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class MyGameListController {

	private final GameListService gameListService;

	@GetMapping("/members/me/games")
	public MyGameListResponse getGames(@AuthenticationPrincipal MemberPrincipal principal,
		@RequestParam(name = "search", required = false) @Size(max = 100, message = "검색어는 100자 이하여야 합니다.") String search,
		@RequestParam(name = "sort", defaultValue = "POSITIVE_RATE_ASC") GameListSort sort,
		@RequestParam(name = "limit", defaultValue = "10")
		@Min(value = 1, message = "limit는 1 이상이어야 합니다.")
		@Max(value = 100, message = "limit는 100 이하여야 합니다.") int limit,
		@RequestParam(name = "cursor", required = false) String cursor,
		@RequestParam(name = "genreIds", required = false) String genreIds,
		@RequestParam(name = "releaseYearFrom", required = false) String releaseYearFrom,
		@RequestParam(name = "releaseYearTo", required = false) String releaseYearTo,
		@RequestParam(name = "minReviewCount", required = false) String minReviewCount,
		@RequestParam(name = "maxReviewCount", required = false) String maxReviewCount,
		@RequestParam(name = "minPositiveRate", required = false) String minPositiveRate,
		@RequestParam(name = "maxPositiveRate", required = false) String maxPositiveRate,
		@RequestParam(name = "developer", required = false) String developer) throws BindException {
		var filters = GameListFilters.of(releaseYearFrom, releaseYearTo, minReviewCount, maxReviewCount,
			minPositiveRate, maxPositiveRate, developer);
		return MyGameListResponse.from(gameListService.getGames(principal.memberId(),
			GameListQuery.of(search, sort, limit, genreIds, filters), cursor, GameListScope.MY));
	}
}
