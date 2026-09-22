package com.ssafy.thispatch.domain.patch.service;

import static com.ssafy.thispatch.domain.game.exception.GameDetailErrorCode.GAME_NOT_FOUND;
import static com.ssafy.thispatch.domain.patch.exception.PatchErrorCode.PATCH_NOT_FOUND;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.thispatch.common.TimeRule;
import com.ssafy.thispatch.domain.patch.dto.response.PatchDetailResponse;
import com.ssafy.thispatch.domain.patch.dto.response.PatchDetailResponse.PatchDetailData;
import com.ssafy.thispatch.domain.patch.repository.PatchReadRepository;
import com.ssafy.thispatch.global.exception.BusinessException;
import com.ssafy.thispatch.global.text.SteamBodyPlainText;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PatchDetailService {

	private final PatchReadRepository repository;

	@Transactional(readOnly = true)
	public PatchDetailResponse getPatch(long gameId, String patchId) {
		if (!repository.gameExists(gameId)) {
			throw new BusinessException(GAME_NOT_FOUND);
		}
		var patch = repository.find(gameId, patchId)
			.orElseThrow(() -> new BusinessException(PATCH_NOT_FOUND));
		var publishedOn = patch.publishedAt().atZone(TimeRule.ZONE).toLocalDate();
		return PatchDetailResponse.success(new PatchDetailData(patch.patchId(), patch.gameId(), patch.title(),
			publishedOn, patch.publishedAt(), SteamBodyPlainText.render(patch.contents()), "PLAIN_TEXT", patch.url()));
	}
}
