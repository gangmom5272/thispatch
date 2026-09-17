package com.ssafy.thispatch.domain.patch.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import com.ssafy.thispatch.client.ai.AiPatchContracts.CaseInput;

/** 카드 호출을 여러 번 나누더라도 패턴의 분모는 결과군 전체 사례 수로 유지한다. */
final class CasePatterns {
	static final int MIN_CASES = 20;
	private static final Map<String, String> TARGET_NAMES = Map.of(
		"player", "플레이어", "enemy", "적", "weapon", "무기", "item", "아이템", "skill", "스킬",
		"map", "맵", "system", "시스템", "other", "기타");

	private CasePatterns() {}

	static List<String> summarize(List<CaseInput> cases) {
		if (cases.size() < MIN_CASES) return List.of();
		long multipleKinds = 0;
		Map<String, Integer> targets = new TreeMap<>();
		Map<String, Integer> directions = new TreeMap<>();
		for (var item : cases) {
			if (item.changes().stream().map(change -> change.changeType()).distinct().count() >= 2) multipleKinds++;
			for (var change : item.changes()) {
				if (TARGET_NAMES.containsKey(change.targetType())) targets.merge(change.targetType(), 1, Integer::sum);
				if (change.direction().equals("increase") || change.direction().equals("decrease")) {
					directions.merge(change.direction(), 1, Integer::sum);
				}
			}
		}
		List<String> patterns = new ArrayList<>();
		if (multipleKinds * 2 >= cases.size()) patterns.add("복수 종류의 변경을 한 패치에서 동시 적용 (" + multipleKinds + "/" + cases.size() + "건)");
		if (!targets.isEmpty()) {
			String target = mostFrequent(targets);
			patterns.add(TARGET_NAMES.get(target) + " 대상 변경이 가장 많음 (" + targets.get(target) + "건)");
		}
		if (!directions.isEmpty()) {
			String direction = mostFrequent(directions);
			patterns.add((direction.equals("increase") ? "상향" : "하향") + " 조정이 다수 (" + directions.get(direction) + "건)");
		}
		return patterns;
	}

	private static String mostFrequent(Map<String, Integer> counts) {
		return counts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
			.thenComparing(Map.Entry.comparingByKey())).findFirst().orElseThrow().getKey();
	}
}
