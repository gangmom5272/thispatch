package com.ssafy.thispatch.domain.game.dto.response;

import java.util.List;

import com.ssafy.thispatch.domain.game.dto.response.GameListResponse.GameSummary;
import com.ssafy.thispatch.domain.game.dto.response.GameListResponse.Page;
import com.ssafy.thispatch.domain.game.dto.response.GameListResponse.TagItem;

/** 공통 목록 조회 결과에서 내 게임 계약에 없는 isMine만 제외한다. */
public record MyGameListResponse(String code, String message, String responsedAt, MyGameListData data, boolean success) {

	public static MyGameListResponse from(GameListResponse response) {
		var items = response.data().items().stream()
			.map(game -> new MyGameItem(game.id(), game.capsuleImageUrl(), game.title(), game.tags(),
				game.positiveRate(), game.gameSummary()))
			.toList();
		return new MyGameListResponse(response.code(), response.message(), response.responsedAt(),
			new MyGameListData(items, response.data().page()), response.success());
	}

	public record MyGameListData(List<MyGameItem> items, Page page) {
	}

	public record MyGameItem(long id, String capsuleImageUrl, String title, List<TagItem> tags,
		Integer positiveRate, GameSummary gameSummary) {
	}
}
