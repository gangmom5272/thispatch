package com.ssafy.thispatch.domain.game.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.thispatch.domain.game.dto.request.GameListQuery;
import com.ssafy.thispatch.domain.game.dto.request.GameListScope;
import com.ssafy.thispatch.domain.game.dto.response.GameListResponse;
import com.ssafy.thispatch.domain.game.dto.response.GameListResponse.GameItem;
import com.ssafy.thispatch.domain.game.dto.response.GameListResponse.GameListData;
import com.ssafy.thispatch.domain.game.dto.response.GameListResponse.GameSummary;
import com.ssafy.thispatch.domain.game.dto.response.GameListResponse.Page;
import com.ssafy.thispatch.domain.game.repository.GameListRepository;
import com.ssafy.thispatch.domain.game.service.GameListCursorCodec.Boundary;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class GameListService {

	private static final String CAPSULE_BASE_URL = "https://shared.fastly.steamstatic.com/store_item_assets/steam/apps/";

	private final GameListRepository repository;
	private final GameListCursorCodec cursors;

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public GameListResponse getGames(long memberId, GameListQuery query, String cursor, GameListScope scope) {
		Boundary boundary = cursors.decode(cursor, query, scope, memberId);
		long totalCount = repository.count(memberId, query, scope);
		var rows = repository.findPage(memberId, query, scope, boundary);
		boolean hasNext = rows.size() > query.limit();
		var pageRows = rows.subList(0, Math.min(rows.size(), query.limit()));
		var gameIds = pageRows.stream().map(GameListRepository.GameRow::id).toList();
		var tagsByGame = repository.findTags(gameIds);
		var modesByGame = repository.findPlayModes(gameIds);
		var items = pageRows.stream().map(game -> {
			String image = blankToNull(game.capsulePath()) == null ? null
				: CAPSULE_BASE_URL + game.id() + "/" + game.capsulePath();
			var tags = List.copyOf(tagsByGame.getOrDefault(game.id(), List.of()));
			var summary = new GameSummary(game.id(), game.title(), image, game.releasedOn(),
				blankToNull(game.developer()), List.copyOf(modesByGame.getOrDefault(game.id(), List.of())),
				game.description(), tags.stream().map(GameListResponse.TagItem::name).toList(),
				game.reviewCount(), blankToNull(game.latestPatch()));
			return new GameItem(game.id(), image, game.title(), tags, game.positiveRate(), game.isMine(), summary);
		}).toList();
		String nextCursor = null;
		if (hasNext) {
			var last = pageRows.get(pageRows.size() - 1);
			nextCursor = cursors.encode(query, scope, memberId, new Boundary(last.id(), last.sortValue()));
		}
		return GameListResponse.success(new GameListData(items, new Page(query.limit(), nextCursor, hasNext, totalCount)));
	}

	private String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}
}