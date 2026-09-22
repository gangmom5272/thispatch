package com.ssafy.thispatch.domain.patch.service;

import org.springframework.stereotype.Service;

import com.ssafy.thispatch.client.deepl.DeepLClient;
import com.ssafy.thispatch.domain.patch.dto.response.PatchTranslation;
import com.ssafy.thispatch.domain.patch.exception.PatchErrorCode;
import com.ssafy.thispatch.domain.patch.repository.PatchReadRepository;
import com.ssafy.thispatch.global.exception.BusinessException;
import com.ssafy.thispatch.global.text.SteamBodyPlainText;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PatchTranslationService {

	private final PatchReadRepository repository;
	private final DeepLClient client;

	// 단일 조회 후 DB 연결을 반환하고, 외부 번역은 트랜잭션 밖에서 기다린다.
	public PatchTranslation translate(String patchId) {
		var source = repository.findTranslationSource(patchId)
			.orElseThrow(() -> new BusinessException(PatchErrorCode.PATCH_NOT_FOUND));
		String body = SteamBodyPlainText.render(source.contents());
		return new PatchTranslation(patchId, translateNonBlank(source.title()), translateNonBlank(body));
	}

	private String translateNonBlank(String text) {
		return text.isBlank() ? text : client.translateToKorean(text);
	}
}
