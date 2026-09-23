package com.ssafy.thispatch.domain.game.repository;

/** PostgreSQL에서 제목과 검색어에 같은 Unicode 문자 범위와 소문자 변환을 적용한다. */
final class GameTitleSearch {

	static final String IGNORED_CHARACTERS = ignoredCharacters();

	private GameTitleSearch() {
	}

	static String expression(String operand) {
		// 범위 비교는 코드 포인트 순서로, 소문자 변환은 양쪽 모두 DB 기본 로케일로 수행한다.
		return "lower(regexp_replace(" + operand
			+ " collate \"C\", :ignoredTitleCharacters, '', 'g') collate \"default\")";
	}

	private static String ignoredCharacters() {
		// PostgreSQL 정규식은 Java의 \p{L}/\p{N}/\p{M}을 지원하지 않는다.
		// JDK 17 Unicode 범주에서 연속 범위를 한 번 생성해 locale별 [:alnum:] 차이를 피한다.
		StringBuilder pattern = new StringBuilder("[^");
		for (int start = 0; start <= Character.MAX_CODE_POINT; start++) {
			if (!isRetained(start)) {
				continue;
			}
			int end = start;
			while (end < Character.MAX_CODE_POINT && isRetained(end + 1)) {
				end++;
			}
			pattern.appendCodePoint(start);
			if (end > start) {
				pattern.append('-').appendCodePoint(end);
			}
			start = end;
		}
		return pattern.append("]+").toString();
	}

	private static boolean isRetained(int codePoint) {
		return Character.isLetter(codePoint) || switch (Character.getType(codePoint)) {
			case Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER,
				Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> true;
			default -> false;
		};
	}
}
