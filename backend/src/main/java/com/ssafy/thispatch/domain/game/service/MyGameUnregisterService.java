package com.ssafy.thispatch.domain.game.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.thispatch.domain.game.dto.MyGameUnregisterResponse;
import com.ssafy.thispatch.domain.game.exception.MyGameUnregisterErrorCode;
import com.ssafy.thispatch.domain.game.repository.MyGameUnregisterRepository;
import com.ssafy.thispatch.global.exception.BusinessException;
import com.ssafy.thispatch.global.security.MemberPrincipal;

import lombok.RequiredArgsConstructor;

@Service
@Transactional
@RequiredArgsConstructor
public class MyGameUnregisterService {

	private final MyGameUnregisterRepository repository;

	public MyGameUnregisterResponse unregister(MemberPrincipal principal, long gameId) {
		if (!repository.gameExists(gameId)) {
			throw new BusinessException(MyGameUnregisterErrorCode.GAME_NOT_FOUND);
		}
		if (repository.deleteRegistration(principal.memberId(), gameId) == 0) {
			throw new BusinessException(MyGameUnregisterErrorCode.MY_GAME_NOT_REGISTERED);
		}
		return MyGameUnregisterResponse.successResponse();
	}
}
