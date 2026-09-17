package com.ssafy.thispatch.domain.patch.service;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Steam 원문의 표시용 서식을 풀고, 알 수 없는 대괄호 표현은 본문으로 보존한다. */
public final class PatchPlainText {

	private static final Pattern TAG = Pattern.compile("(?<!\\\\)\\[(/?)([a-zA-Z][a-zA-Z0-9]*|\\*)(?:=[^\\]\\r\\n]*)?\\]");
	private static final Set<String> INLINE_TAGS = Set.of("b", "i", "u", "s", "strike", "spoiler", "url", "img");
	private static final Set<String> BLOCK_TAGS = Set.of("p", "h1", "h2", "h3", "list", "olist", "quote", "br", "hr");

	private PatchPlainText() {
	}

	public static String render(String contents) {
		var matcher = TAG.matcher(contents);
		StringBuilder text = new StringBuilder(contents.length());
		int copiedUntil = 0;
		String literalBlock = null;
		while (matcher.find()) {
			text.append(contents, copiedUntil, matcher.start());
			String name = matcher.group(2).toLowerCase(Locale.ROOT);
			boolean closing = !matcher.group(1).isEmpty();
			if (literalBlock != null) {
				// 코드 예시 안의 [b] 등은 실제 서식이 아니라 코드 자체다.
				if (closing && literalBlock.equals(name)) {
					literalBlock = null;
					lineBreak(text);
				} else {
					text.append(matcher.group());
				}
			} else if (!closing && (name.equals("code") || name.equals("pre"))) {
				literalBlock = name;
				lineBreak(text);
			} else if (name.equals("*")) {
				lineBreak(text);
				if (!closing) {
					text.append("- ");
				}
			} else if (BLOCK_TAGS.contains(name)) {
				// Steam은 목록 항목 안에도 [p]를 넣는다. 글머리표와 본문을 같은 줄에 둔다.
				boolean paragraphAfterBullet = !closing && name.equals("p")
					&& text.substring(text.lastIndexOf("\n") + 1).equals("- ");
				if (!paragraphAfterBullet) {
					lineBreak(text);
				}
			} else if (!INLINE_TAGS.contains(name)) {
				text.append(matcher.group());
			}
			copiedUntil = matcher.end();
		}
		text.append(contents, copiedUntil, contents.length());
		return text.toString().replace("\\[", "[").replace("\\]", "]").strip();
	}

	private static void lineBreak(StringBuilder text) {
		if (!text.isEmpty() && text.charAt(text.length() - 1) != '\n') {
			text.append('\n');
		}
	}
}
