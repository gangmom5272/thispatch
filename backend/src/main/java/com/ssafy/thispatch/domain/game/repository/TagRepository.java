package com.ssafy.thispatch.domain.game.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.thispatch.domain.game.entity.Tag;

public interface TagRepository extends JpaRepository<Tag, Integer> {

	@Transactional(readOnly = true)
	List<Tag> findAllByOrderByTagIdAsc();
}
