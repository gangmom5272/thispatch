package com.ssafy.thispatch.domain.patch.dto.request;

import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import com.ssafy.thispatch.domain.patch.dto.PatchChangeCodes.*;

public record CaseSearchRequest(
	@NotEmpty @Size(max = 20) List<@NotNull @Valid ConfirmedSlot> confirmedSlots,
	@NotNull List<@NotNull @Positive Integer> genreIds,
	Sort sort) {

	public CaseSearchRequest {
		if (sort == null) sort = Sort.SIMILARITY_DESC;
	}

	public enum Sort { SIMILARITY_DESC, REVIEW_COUNT_DESC, ABS_DELTA_PP_DESC, PATCHED_ON_DESC }

	public record Target(@NotNull String name, @NotNull TargetRole role) {}

	public record ConfirmedSlot(@NotNull @Valid Target target, @NotNull String attribute,
		@NotNull ChangeType changeType, @NotNull Direction direction, String magnitude, String scope) {}
}
