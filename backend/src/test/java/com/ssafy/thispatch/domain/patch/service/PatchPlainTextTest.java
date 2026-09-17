package com.ssafy.thispatch.domain.patch.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PatchPlainTextTest {

	@Test
	void keepsParagraphsAndBulletItemsSeparate() {
		assertThat(PatchPlainText.render("[h1]Balance[/h1][list][*]HP +20%[/*][*][b]ATK[/b] -10%[/*][/list]"))
			.isEqualTo("Balance\n- HP +20%\n- ATK -10%");
	}

	@Test
	void preservesUnknownHeadingsAndEscapedBrackets() {
		assertThat(PatchPlainText.render("[p]\\[ MAPS ][/p][p][Warden] buffed[/p]"))
			.isEqualTo("[ MAPS ]\n[Warden] buffed");
	}

	@Test
	void keepsTagsInsideCodeExamplesLiteral() {
		assertThat(PatchPlainText.render("[code][b]example[/b][/code][p]Description[/p]"))
			.isEqualTo("[b]example[/b]\nDescription");
	}

	@Test
	void linkKeepsItsVisibleTextWithoutInterpretingHtml() {
		assertThat(PatchPlainText.render("[url=https://example.com]Details[/url] <tag> &value"))
			.isEqualTo("Details <tag> &value");
	}

	@Test
	void ordinaryTextAndEmptyTextRemainReadable() {
		assertThat(PatchPlainText.render("Damage [10, 20] -> 30\nNext line")).isEqualTo("Damage [10, 20] -> 30\nNext line");
		assertThat(PatchPlainText.render("")).isEmpty();
	}
	@Test
	void steamParagraphInsideListKeepsBulletWithText() {
		assertThat(PatchPlainText.render("[list][*][p]HP +20%[/p][/*][*][p]ATK +10%[/p][/*][/list]"))
			.isEqualTo("- HP +20%\n- ATK +10%");
	}
}
