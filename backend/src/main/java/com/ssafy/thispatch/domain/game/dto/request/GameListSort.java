package com.ssafy.thispatch.domain.game.dto.request;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;

public enum GameListSort {
	POSITIVE_RATE_ASC("g.store_positive_pct", "asc"),
	REVIEW_COUNT_DESC("g.store_review_count", "desc"),
	REACTION_CHANGE_DESC("abs(ps.delta_pct)", "desc"),
	RELEASE_DATE_DESC("(g.release_ts at time zone 'Asia/Seoul')::date", "desc");

	private final String expression;
	private final String direction;

	GameListSort(String expression, String direction) {
		this.expression = expression;
		this.direction = direction;
	}

	public String expression() {
		return expression;
	}

	public String direction() {
		return direction;
	}

	public String comparison() {
		return this == POSITIVE_RATE_ASC ? ">" : "<";
	}

	public Object cursorValue(String value) {
		if (value == null) {
			return null;
		}
		return switch (this) {
			case POSITIVE_RATE_ASC, REVIEW_COUNT_DESC -> Integer.valueOf(value);
			case REACTION_CHANGE_DESC -> new BigDecimal(value);
			case RELEASE_DATE_DESC -> Date.valueOf(LocalDate.parse(value));
		};
	}
}