package com.ssafy.thispatch.domain.game.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "tag")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Tag {

	@Id
	@Column(name = "tag_id")
	private Integer tagId;

	@Column(name = "name_ko", nullable = false, length = 100)
	private String nameKo;

	@Column(name = "collected_at")
	private Instant collectedAt;
}
