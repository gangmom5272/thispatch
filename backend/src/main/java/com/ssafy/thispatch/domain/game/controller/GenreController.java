package com.ssafy.thispatch.domain.game.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ssafy.thispatch.domain.game.dto.response.GenreListResponse;
import com.ssafy.thispatch.domain.game.service.GenreService;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class GenreController {

	private final GenreService genreService;

	@GetMapping("/genres")
	public GenreListResponse genres() {
		return genreService.getGenres();
	}
}
