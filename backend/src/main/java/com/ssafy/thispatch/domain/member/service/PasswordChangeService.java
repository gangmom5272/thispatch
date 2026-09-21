package com.ssafy.thispatch.domain.member.service;

import static com.ssafy.thispatch.domain.member.exception.MemberErrorCode.CURRENT_PASSWORD_MISMATCH;
import static com.ssafy.thispatch.domain.member.exception.MemberErrorCode.PASSWORD_CHANGE_NOT_SUPPORTED;

import java.time.Instant;
import java.util.Map;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.BindException;
import org.springframework.validation.MapBindingResult;

import com.ssafy.thispatch.domain.member.dto.PasswordChangeRequest;
import com.ssafy.thispatch.domain.member.dto.PasswordChangeResponse;
import com.ssafy.thispatch.domain.member.entity.LoginType;
import com.ssafy.thispatch.domain.member.entity.Member;
import com.ssafy.thispatch.domain.member.repository.MemberRepository;
import com.ssafy.thispatch.global.exception.BusinessException;
import com.ssafy.thispatch.global.security.MemberPrincipal;
import com.ssafy.thispatch.global.security.SecurityErrorCode;

import lombok.RequiredArgsConstructor;

@Service
@Transactional(rollbackFor = BindException.class)
@RequiredArgsConstructor
public class PasswordChangeService {

	private final MemberRepository memberRepository;
	private final PasswordEncoder passwordEncoder;

	public PasswordChangeResponse changePassword(MemberPrincipal principal, PasswordChangeRequest request)
		throws BindException {
		Member member = memberRepository.findByIdForPasswordChange(principal.memberId())
			.filter(found -> "ACTIVE".equals(found.getStatus()))
			.orElseThrow(() -> new BusinessException(SecurityErrorCode.UNAUTHORIZED));
		if (member.getLoginType() != LoginType.LOCAL) {
			throw new BusinessException(PASSWORD_CHANGE_NOT_SUPPORTED);
		}
		if (!passwordEncoder.matches(request.currentPassword(), member.getPassword())) {
			throw new BusinessException(CURRENT_PASSWORD_MISMATCH);
		}
		if (request.currentPassword().equals(request.newPassword())) {
			// 비밀번호 원문을 바인딩 오류에 담지 않고 기존 공통 필드 오류 처리로 연결한다.
			var errors = new MapBindingResult(Map.of(), "passwordChangeRequest");
			errors.rejectValue("newPassword", "password.same", "새 비밀번호는 현재 비밀번호와 달라야 합니다.");
			throw new BindException(errors);
		}

		String passwordHash = passwordEncoder.encode(request.newPassword());
		if (memberRepository.changePasswordAndClearRefreshToken(principal.memberId(), passwordHash, Instant.now()) != 1) {
			throw new BusinessException(SecurityErrorCode.UNAUTHORIZED);
		}
		return PasswordChangeResponse.successResponse();
	}
}
