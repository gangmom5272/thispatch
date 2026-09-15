package com.ssafy.thispatch.domain.game.service;

import static com.ssafy.thispatch.domain.game.exception.GameDetailErrorCode.GAME_NOT_FOUND;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.thispatch.common.TimeRule;
import com.ssafy.thispatch.domain.game.dto.response.GameDetailResponse;
import com.ssafy.thispatch.domain.game.dto.response.GameDetailResponse.GameDetailData;
import com.ssafy.thispatch.domain.game.dto.response.GameDetailResponse.TagItem;
import com.ssafy.thispatch.domain.game.repository.GameDetailRepository;
import com.ssafy.thispatch.global.exception.BusinessException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class GameDetailService {

	private static final String CAPSULE_BASE_URL = "https://shared.fastly.steamstatic.com/store_item_assets/steam/apps/";

	private final GameDetailRepository repository;

	@Transactional(readOnly = true)
	public GameDetailResponse getGame(long memberId, long gameId) {
		var game = repository.findByGameId(memberId, gameId)
			.orElseThrow(() -> new BusinessException(GAME_NOT_FOUND));
		String capsuleImageUrl = game.capsulePath() == null || game.capsulePath().isBlank()
			? null : CAPSULE_BASE_URL + game.id() + "/" + game.capsulePath();
		var releasedOn = game.releasedAt() == null ? null : game.releasedAt().atZone(TimeRule.ZONE).toLocalDate();
		var tags = game.tags().stream().map(tag -> new TagItem(tag.id(), tag.name())).toList();
		return GameDetailResponse.success(new GameDetailData(game.id(), capsuleImageUrl, game.title(), tags,
			game.positiveRate(), game.isMine(), game.description(), releasedOn, game.reviewCount(), game.collectedAt()));
	}
}
