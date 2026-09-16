package com.ssafy.thispatch.domain.game.service;

import static com.ssafy.thispatch.domain.game.exception.MyGameRegistrationErrorCode.GAME_NOT_FOUND;
import static com.ssafy.thispatch.domain.game.exception.MyGameRegistrationErrorCode.MY_GAME_ALREADY_REGISTERED;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.thispatch.domain.game.dto.MyGameRegistrationResponse;
import com.ssafy.thispatch.domain.game.repository.MyGameRegistrationRepository;
import com.ssafy.thispatch.global.exception.BusinessException;
import com.ssafy.thispatch.global.security.MemberPrincipal;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MyGameRegistrationService {

	private final MyGameRegistrationRepository registrationRepository;

	@Transactional
	public MyGameRegistrationResponse register(MemberPrincipal principal, long gameId) {
		if (!registrationRepository.lockExistingGame(gameId)) {
			throw new BusinessException(GAME_NOT_FOUND);
		}
		if (registrationRepository.insertIfAbsent(principal.memberId(), gameId) != 1) {
			throw new BusinessException(MY_GAME_ALREADY_REGISTERED);
		}
		return MyGameRegistrationResponse.successResponse();
	}
}
