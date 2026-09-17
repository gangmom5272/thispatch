package com.ssafy.thispatch.domain.patch.dto;

public final class PatchChangeCodes {
	private PatchChangeCodes() {}

	public enum ChangeType { ADD, REMOVE, MODIFY, FIX, DEPRECATE }
	public enum Direction { INCREASE, DECREASE, NONE, NOT_APPLICABLE, UNKNOWN }
	public enum TargetRole { PLAYER, ENEMY, WEAPON, ITEM, SKILL, MAP, SYSTEM, OTHER, UNKNOWN }
}
