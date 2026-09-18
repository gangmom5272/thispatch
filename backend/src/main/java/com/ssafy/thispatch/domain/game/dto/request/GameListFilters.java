package com.ssafy.thispatch.domain.game.dto.request;

import static com.ssafy.thispatch.global.exception.CommonErrorCode.INVALID_REQUEST;

import java.util.HashMap;
import java.util.Locale;

import org.springframework.validation.BindException;
import org.springframework.validation.MapBindingResult;

import com.ssafy.thispatch.global.exception.BusinessException;

public record GameListFilters(Integer releaseYearFrom, Integer releaseYearTo,
	Integer minReviewCount, Integer maxReviewCount, Integer minPositiveRate, Integer maxPositiveRate,
	String developer) {

	public static final GameListFilters NONE = new GameListFilters(null, null, null, null, null, null, "");

	public static GameListFilters of(String releaseYearFrom, String releaseYearTo,
		String minReviewCount, String maxReviewCount, String minPositiveRate, String maxPositiveRate,
		String developer) throws BindException {
		var filters = new GameListFilters(parseInteger(releaseYearFrom), parseInteger(releaseYearTo),
			parseInteger(minReviewCount), parseInteger(maxReviewCount),
			parseInteger(minPositiveRate), parseInteger(maxPositiveRate),
			developer == null ? "" : developer.strip().toLowerCase(Locale.ROOT));
		var errors = new MapBindingResult(new HashMap<>(), "gameFilters");
		validateRange(errors, "releaseYearFrom", filters.releaseYearFrom(), 1, 9999);
		validateRange(errors, "releaseYearTo", filters.releaseYearTo(), 1, 9999);
		validateRange(errors, "minReviewCount", filters.minReviewCount(), 0, Integer.MAX_VALUE);
		validateRange(errors, "maxReviewCount", filters.maxReviewCount(), 0, Integer.MAX_VALUE);
		validateRange(errors, "minPositiveRate", filters.minPositiveRate(), 0, 100);
		validateRange(errors, "maxPositiveRate", filters.maxPositiveRate(), 0, 100);
		validateBounds(errors, "releaseYearTo", filters.releaseYearFrom(), filters.releaseYearTo(), "출시연도");
		validateBounds(errors, "maxReviewCount", filters.minReviewCount(), filters.maxReviewCount(), "리뷰 수");
		validateBounds(errors, "maxPositiveRate", filters.minPositiveRate(), filters.maxPositiveRate(), "긍정률");
		if (developer != null && developer.length() > 500) {
			errors.rejectValue("developer", "Size", "개발사명은 500자 이하여야 합니다.");
		}
		if (errors.hasErrors()) {
			throw new BindException(errors);
		}
		return filters;
	}

	private static Integer parseInteger(String value) {
		if (value == null) {
			return null;
		}
		// 빈 값이 생략된 필터로 변환되지 않도록 숫자 형식과 범위를 구분해서 검증한다.
		if (!value.matches("[+-]?[0-9]+")) {
			throw new BusinessException(INVALID_REQUEST);
		}
		try {
			return Integer.valueOf(value);
		} catch (NumberFormatException exception) {
			throw new BusinessException(INVALID_REQUEST);
		}
	}

	private static void validateRange(MapBindingResult errors, String field, Integer value, int min, int max) {
		if (value != null && (value < min || value > max)) {
			errors.rejectValue(field, "Range", min + " 이상 " + max + " 이하여야 합니다.");
		}
	}

	private static void validateBounds(MapBindingResult errors, String upperField, Integer lower,
		Integer upper, String label) {
		if (lower != null && upper != null && lower > upper) {
			errors.rejectValue(upperField, "Range", label + " 상한은 하한 이상이어야 합니다.");
		}
	}

	public String developerPattern() {
		return "%" + developer.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
	}
}
