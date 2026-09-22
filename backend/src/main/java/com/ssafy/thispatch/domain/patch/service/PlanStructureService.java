package com.ssafy.thispatch.domain.patch.service;

import static com.ssafy.thispatch.domain.game.exception.GameDetailErrorCode.GAME_NOT_FOUND;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import com.ssafy.thispatch.client.ai.AiPatchClient;
import com.ssafy.thispatch.client.ai.AiPatchContracts.PlanRequest;
import com.ssafy.thispatch.domain.patch.dto.PatchChangeCodes.*;
import com.ssafy.thispatch.domain.patch.dto.request.PlanStructureRequest;
import com.ssafy.thispatch.domain.patch.dto.response.PlanStructureResponse;
import com.ssafy.thispatch.domain.patch.dto.response.PlanStructureResponse.*;
import com.ssafy.thispatch.domain.patch.repository.PatchSearchRepository;
import com.ssafy.thispatch.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PlanStructureService {
	private final PatchSearchRepository repository;
	private final AiPatchClient ai;
	private final PlanStructureStorageService storage;

	public PlanStructureResponse structure(long memberId, long gameId, PlanStructureRequest request) {
		var game = repository.findGame(gameId).orElseThrow(() -> new BusinessException(GAME_NOT_FOUND));
		// 모델 호출 동안 DB 트랜잭션과 커넥션을 점유하지 않는다.
		var response = ai.structure(new PlanRequest("", request.text()));
		List<Slot> slots = new ArrayList<>();
		List<Entity> entities = new ArrayList<>();
		List<Warning> warnings = new ArrayList<>();
		List<String> restatements = new ArrayList<>();
		for (var change : response.changes()) {
			int slotId = slots.size() + 1;
			String target = change.target() == null ? "" : change.target();
			TargetRole role = TargetRole.valueOf(change.targetType().toUpperCase(Locale.ROOT));
			String attribute = change.attribute() == null ? "" : change.attribute();
			String scope = change.conditions().isEmpty() ? null : String.join(", ", change.conditions());
			slots.add(new Slot(slotId, target, role, attribute,
				ChangeType.valueOf(change.changeType().toUpperCase(Locale.ROOT)),
				Direction.valueOf(change.direction().toUpperCase(Locale.ROOT)), change.values(), scope, true));
			if (entities.stream().noneMatch(entity -> entity.name().equals(target) && entity.role() == role)) {
				entities.add(new Entity(entities.size() + 1, target, role, "AI", false));
				if (role == TargetRole.UNKNOWN || target.isBlank()) {
					warnings.add(new Warning("UNKNOWN_ENTITY", "변경 대상을 확인하고 필요하면 슬롯을 수정해주세요.", target));
				}
			}
			restatements.add(change.restatement());
		}
		if (slots.isEmpty()) warnings.add(new Warning("NO_CHANGES", "변경점을 찾지 못했습니다. 기획안을 구체적으로 입력해주세요.", null));
		var attributes = slots.stream().map(Slot::attribute).filter(value -> !value.isBlank()).distinct().toList();
		var scopes = slots.stream().map(Slot::scope).filter(java.util.Objects::nonNull).distinct().toList();
		var highlights = new Highlights(singleOrMixed(slots.stream().map(slot -> slot.targetRole().name()).toList()),
			attributes, singleOrMixed(slots.stream().map(slot -> slot.direction().name()).toList()),
			scopes.isEmpty() ? null : String.join(", ", scopes));
		var restatement = new Restatement(String.join("\n", restatements), highlights, warnings);
		long planId = storage.save(memberId, gameId, request.text(), entities, slots, restatement);
		return new PlanStructureResponse(new PlanData(planId, gameId, request.text(),
			game.genres().stream().map(PatchSearchRepository.Genre::id).toList(), entities, slots,
			restatement));
	}

	private static String singleOrMixed(List<String> values) {
		var distinct = values.stream().distinct().toList();
		if (distinct.isEmpty()) return "UNKNOWN";
		return distinct.size() == 1 ? distinct.get(0) : "MIXED";
	}
}
