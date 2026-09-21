package com.ssafy.thispatch.domain.patch.dto.response;

import java.util.List;
import com.ssafy.thispatch.domain.patch.dto.PatchChangeCodes.*;

public record PlanStructureResponse(PlanData data) {
	public record PlanData(long planId, long gameId, String rawText, List<Integer> genreIds,
		List<Entity> entities, List<Slot> slots, Restatement restatement) {}
	public record Entity(int id, String name, TargetRole role, String source, boolean editable) {}
	public record Slot(int id, String targetName, TargetRole targetRole, String attribute,
		ChangeType changeType, Direction direction, String magnitude, String scope, boolean editable) {}
	public record Restatement(String text, Highlights highlights, List<Warning> warnings) {}
	public record Highlights(String primaryRole, List<String> attributes, String direction, String scope) {}
	public record Warning(String code, String message, String entityName) {}
}
