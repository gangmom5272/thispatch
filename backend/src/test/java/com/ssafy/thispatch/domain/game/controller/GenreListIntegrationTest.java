package com.ssafy.thispatch.domain.game.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thispatch.domain.member.entity.LoginType;
import com.ssafy.thispatch.domain.member.entity.Member;
import com.ssafy.thispatch.domain.member.repository.MemberRepository;
import com.ssafy.thispatch.global.security.jwt.JwtTokenProvider;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class GenreListIntegrationTest {

	@Autowired private MockMvc mvc;
	@Autowired private ObjectMapper mapper;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private MemberRepository members;
	@Autowired private JwtTokenProvider tokens;

	@BeforeEach
	void requireTestDatabase() {
		assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo("thispatch_test");
	}

	@Test
	void listsAllTagsOnceInIdOrderIncludingUnlinkedTagsAndDuplicateNames() throws Exception {
		int first = ThreadLocalRandom.current().nextInt(1_000_000_000, 2_000_000_000);
		while (jdbc.queryForObject("select count(*) from tag where tag_id between ? and ?", Integer.class,
			first, first + 2) != 0) {
			first = ThreadLocalRandom.current().nextInt(1_000_000_000, 2_000_000_000);
		}
		String name = "장르-" + UUID.randomUUID();
		// ID와 이름 순서를 다르게 넣고, 같은 이름의 다른 태그도 보존하는지 확인한다.
		insertTag(first + 2, name + "-A");
		insertTag(first, name + "-Z");
		insertTag(first + 1, name + "-A");

		long gameId = ThreadLocalRandom.current().nextLong(4_000_000_000L, 8_000_000_000L);
		while (jdbc.queryForObject("select count(*) from game where appid in (?, ?)", Integer.class,
			gameId, gameId + 1) != 0) {
			gameId = ThreadLocalRandom.current().nextLong(4_000_000_000L, 8_000_000_000L);
		}
		for (long id : List.of(gameId, gameId + 1)) {
			jdbc.update("insert into game (appid, name, collected_at) values (?, ?, now())", id, "genre-test");
			jdbc.update("insert into game_tag (appid, tag_id, weight) values (?, ?, ?)", id, first, 1);
		}
		long memberId = members.saveAndFlush(Member.builder()
			.loginType(LoginType.LOCAL).email(UUID.randomUUID() + "@example.com")
			.nickname(null).status("ACTIVE").createdAt(Instant.now()).build()).getMemberId();

		var result = mvc.perform(get("/genres").header(HttpHeaders.AUTHORIZATION,
			"Bearer " + tokens.issueAccessToken(memberId))).andExpect(status().isOk()).andReturn();
		var body = mapper.readTree(result.getResponse().getContentAsByteArray());
		var items = body.at("/data/items");
		assertThat(body.size()).isEqualTo(5);
		assertThat(body.get("code").asText()).isEqualTo("200");
		assertThat(body.get("message").asText()).isEqualTo("성공했습니다.");
		assertThat(body.get("success").asBoolean()).isTrue();
		assertThat(body.get("data").size()).isEqualTo(1);
		assertThat(items.size()).isEqualTo(jdbc.queryForObject("select count(*) from tag", Integer.class));

		List<Integer> ids = new ArrayList<>();
		List<String> fixtureNames = new ArrayList<>();
		for (var item : items) {
			assertThat(item.size()).isEqualTo(2);
			assertThat(item.get("id").isInt()).isTrue();
			assertThat(item.get("name").isTextual()).isTrue();
			int id = item.get("id").intValue();
			ids.add(id);
			if (id >= first && id <= first + 2) {
				fixtureNames.add(item.get("name").asText());
			}
		}
		assertThat(ids).isSorted().doesNotHaveDuplicates().contains(first, first + 1, first + 2);
		assertThat(fixtureNames).containsExactly(name + "-Z", name + "-A", name + "-A");
	}

	private void insertTag(int id, String name) {
		jdbc.update("insert into tag (tag_id, name_ko) values (?, ?)", id, name);
	}
}
