package com.ssafy.thispatch.global.text;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SteamBodyPlainTextTest {

	@Test
	void keepsParagraphsAndBulletItemsSeparate() {
		assertThat(SteamBodyPlainText.render("[h1]Balance[/h1][list][*]HP +20%[/*][*][b]ATK[/b] -10%[/*][/list]"))
			.isEqualTo("Balance\n- HP +20%\n- ATK -10%");
	}

	@Test
	void preservesUnknownHeadingsAndEscapedBrackets() {
		assertThat(SteamBodyPlainText.render("[p]\\[ MAPS ][/p][p][Warden] buffed[/p]"))
			.isEqualTo("[ MAPS ]\n[Warden] buffed");
	}

	@Test
	void keepsTagsInsideCodeExamplesLiteral() {
		assertThat(SteamBodyPlainText.render("[code][b]example[/b][/code][p]Description[/p]"))
			.isEqualTo("[b]example[/b]\nDescription");
	}

	@Test
	void linksKeepVisibleTextWithoutAddingAddresses() {
		assertThat(SteamBodyPlainText.render("[url=https://example.com]Details[/url]"))
			.isEqualTo("Details");
		assertThat(SteamBodyPlainText.render("<a href='https://example.com'>Details</a>"))
			.isEqualTo("Details");
	}

	@Test
	void ordinaryTextAndEmptyTextRemainReadable() {
		assertThat(SteamBodyPlainText.render("Damage [10, 20] -> 30\nNext line")).isEqualTo("Damage [10, 20] -> 30\nNext line");
		assertThat(SteamBodyPlainText.render("")).isEmpty();
	}
	@Test
	void steamParagraphInsideListKeepsBulletWithText() {
		assertThat(SteamBodyPlainText.render("[list][*][p]HP +20%[/p][/*][*][p]ATK +10%[/p][/*][/list]"))
			.isEqualTo("- HP +20%\n- ATK +10%");
	}

	@Test
	void pubgStandaloneImageLeavesOnlyAnnouncementText() {
		assertThat(SteamBodyPlainText.render("[img src=\"{STEAM_CLAN_IMAGE}/27971017/"
			+ "2ee855dbefc298e4233fc117bce06a8e1e531cb4.jpg\"] Read the full announcement here!"))
			.isEqualTo("Read the full announcement here!");
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"[img]{STEAM_CLAN_IMAGE}/27971017/map.jpg[/img]",
		"[img]https://example.com/map.jpg[/img]", "[img]map.jpg[/img]", "[img]지도.png[/img]",
		"[img=https://example.com/map.jpg]", "<img src='https://example.com/map.jpg'>",
		"[previewyoutube=abc123DEF45;full][/previewyoutube]", "[youtube]abc123DEF45[/youtube]",
		"[video webm='https://example.com/clip.webm' autoplay=true][/video]",
		"[previewimg=123;sizeFull;map.jpg]map.jpg[/previewimg]"
	})
	void mediaResourcesDoNotBecomeVisibleText(String source) {
		assertThat(SteamBodyPlainText.render(source)).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"[img src=\"map.jpg\" alt=\"신규 전장 지도\"]",
		"<img src='map.jpg' alt='신규 전장 지도' />",
		"[previewimg=123;sizeFull;map.jpg]신규 전장 지도[/previewimg]",
		"[img src='map.jpg']신규 전장 지도[/img]",
		"[previewyoutube=abc123DEF45;full]신규 전장 지도[/previewyoutube]"
	})
	void mediaCaptionsAndAltArePreserved(String source) {
		assertThat(SteamBodyPlainText.render(source)).isEqualTo("신규 전장 지도");
	}

	@Test
	void quotedAttributeDelimitersAndCaseDoNotLeakAttributes() {
		assertThat(SteamBodyPlainText.render("[IMG src=\"map.jpg\" alt=\"[신규] 지도\"]"))
			.isEqualTo("[신규] 지도");
		assertThat(SteamBodyPlainText.render("<IMG SRC='map.jpg' ALT='전 > 후'/>"))
			.isEqualTo("전 > 후");
		assertThat(SteamBodyPlainText.render("[P align='center'][B]내용[/B][/P]"))
			.isEqualTo("내용");
	}

	@ParameterizedTest
	@ValueSource(strings = {"h1", "h2", "h3", "h4", "h5", "h6"})
	void allHeadingLevelsKeepSectionBoundaries(String heading) {
		assertThat(SteamBodyPlainText.render("[" + heading + "]Title[/" + heading + "][p]Body[/p]"))
			.isEqualTo("Title\nBody");
	}

	@Test
	void bbcodeAndHtmlTablesKeepRowsAndCellsSeparate() {
		assertThat(SteamBodyPlainText.render("[table][tr][th]Name[/th][th]Damage[/th][/tr]"
			+ "[tr][td][b]Warden[/b][/td][td]20[/td][/tr][/table]"))
			.isEqualTo("Name\tDamage\nWarden\t20");
		assertThat(SteamBodyPlainText.render("<table><tr><th>Name</th><th>Damage</th></tr>"
			+ "<tr><td><b>Warden</b></td><td>20</td></tr></table>"))
			.isEqualTo("Name\tDamage\nWarden\t20");
	}

	@Test
	void htmlListsParagraphsAndEntitiesBecomeReadableText() {
		assertThat(SteamBodyPlainText.render("<h4>Balance &amp; fixes</h4><ul>"
			+ "<li><p>HP&nbsp;+20%</p></li><li>ATK &#43;10%</li></ul><p>Next<br/>Line</p>"))
			.isEqualTo("Balance & fixes\n- HP +20%\n- ATK +10%\nNext\nLine");
	}

	@Test
	void htmlCommentsScriptsAndStylesAreNotDisplayed() {
		assertThat(SteamBodyPlainText.render("<p>Before</p><!-- [b]hidden[/b] -->"
			+ "<script>if (a < b) alert('[b]hidden[/b]');</script><style>.x { color: red; }</style><p>After</p>"))
			.isEqualTo("Before\nAfter");
	}

	@ParameterizedTest
	@ValueSource(strings = {"code", "pre", "noparse"})
	void literalBbcodeProtectsInnerFormattingEntitiesAndEscapes(String tag) {
		String literal = "[b]example[/b] <img src='map.jpg'> &amp; \\[Warden]";
		assertThat(SteamBodyPlainText.render("[" + tag + "]" + literal + "[/" + tag + "]"))
			.isEqualTo(literal);
	}

	@Test
	void htmlCodeDecodesEntitiesWithoutReinterpretingTheCode() {
		assertThat(SteamBodyPlainText.render("<pre>&lt;b&gt;[b]example[/b]&lt;/b&gt; &amp;</pre>"))
			.isEqualTo("<b>[b]example[/b]</b> &");
		assertThat(SteamBodyPlainText.render("Use <code>&lt;br&gt;</code> here"))
			.isEqualTo("Use <br> here");
		assertThat(SteamBodyPlainText.render("<pre><code>&lt;br&gt; [b]example[/b]</code></pre>"))
			.isEqualTo("<br> [b]example[/b]");
	}

	@Test
	void attributesDoNotMistakeQuotedSourceTextForAltAndSupportMultilineHtml() {
		assertThat(SteamBodyPlainText.render("<img src='https://example.com/? alt=hidden'>"))
			.isEmpty();
		assertThat(SteamBodyPlainText.render("<img\n src='map.jpg'\n alt='NewFeatures'>"))
			.isEqualTo("NewFeatures");
		assertThat(SteamBodyPlainText.render("<!DOCTYPE html><p>Text</p>"))
			.isEqualTo("Text");
		assertThat(SteamBodyPlainText.render("[previewyoutube=abc123DEF45;full]NewFeatures[/previewyoutube]"))
			.isEqualTo("NewFeatures");
		assertThat(SteamBodyPlainText.render("[video src='clip.webm' alt='NewFeatures'][/video]"))
			.isEqualTo("NewFeatures");
	}

	@Test
	void entitiesAreDecodedOnceAndSupportSupplementaryUnicode() {
		assertThat(SteamBodyPlainText.render("&lt;b&gt;literal&lt;/b&gt; &amp;lt; &#x1F600; &#128512; &apos;"))
			.isEqualTo("<b>literal</b> &lt; 😀 😀 '");
		assertThat(SteamBodyPlainText.render("&#99999999999999999; &unknown; &#xD800;"))
			.isEqualTo("&#99999999999999999; &unknown; &#xD800;");
	}

	@Test
	void unknownBracketsAndOrdinaryComparisonsAndUrlsArePreserved() {
		String source = "{TL;DR} [Update] [Warden] [imgur] HP < 20 && ATK > 10 https://example.com";
		assertThat(SteamBodyPlainText.render(source)).isEqualTo(source);
		assertThat(SteamBodyPlainText.render("\\[b]literal\\[/b]")).isEqualTo("[b]literal[/b]");
		assertThat(SteamBodyPlainText.render("a<b && c>d")).isEqualTo("a<b && c>d");
	}

	@Test
	void unclosedLiteralKeepsRemainingTextAndMalformedTagDoesNotSwallowNextParagraph() {
		assertThat(SteamBodyPlainText.render("[noparse][b]example[/b] <br>"))
			.isEqualTo("[b]example[/b] <br>");
		assertThat(SteamBodyPlainText.render("[img src=oops\n[p]Next[/p]"))
			.isEqualTo("[img src=oops\nNext");
	}
}
