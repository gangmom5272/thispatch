package com.ssafy.thispatch.global.text;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.web.util.HtmlUtils;

/** 저장 원문을 변경하지 않고 리뷰·패치의 표시/번역용 본문을 만든다. HTML 출력용 sanitizer는 아니다. */
public final class SteamBodyPlainText {

	private static final Set<String> INLINE = Set.of("b", "i", "u", "s", "strike", "spoiler", "url");
	private static final Set<String> BLOCK = Set.of("p", "h1", "h2", "h3", "h4", "h5", "h6", "list", "olist",
		"quote", "br", "hr", "table", "tr");
	private static final Set<String> HTML_BLOCK = Set.of("div", "section", "article", "header", "footer",
		"ul", "ol", "blockquote", "figure", "figcaption", "thead", "tbody", "tfoot");
	private static final Set<String> MEDIA = Set.of("img", "previewimg", "previewyoutube", "youtube", "video");
	private static final Set<String> LITERAL = Set.of("code", "pre", "noparse");
	private static final Pattern ATTRIBUTE = Pattern.compile("([a-zA-Z][a-zA-Z0-9_-]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s]+))");
	private static final Pattern HTML_ATTRIBUTE = Pattern.compile(
		"\\s+[a-zA-Z_:][a-zA-Z0-9_.:-]*(?:\\s*=\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s\"'=<>`]+))?");
	private static final Pattern ENTITY = Pattern.compile("&(?:#[xX][0-9a-fA-F]+|#[0-9]+|[a-zA-Z][a-zA-Z0-9]+);");
	private static final Pattern RESOURCE = Pattern.compile(
		"(?i)(?:https?://|//|\\{STEAM_[A-Z_]+\\})[^\\s]+"
			+ "|(?<!\\S)[\\p{L}\\p{N}_./\\\\%+-]+\\.(?:png|jpe?g|gif|webp|avif|svg|mp4|webm)(?:\\?[^\\s]*)?(?!\\S)");

	private SteamBodyPlainText() {
	}

	public static String render(String source) {
		StringBuilder text = new StringBuilder(source.length());
		Token literal = null;
		String hidden = null;
		Token media = null;
		int copiedUntil = 0;
		for (int position = 0; position < source.length();) {
			Token tag = tokenAt(source, position);
			if (tag == null) {
				position++;
				continue;
			}
			append(text, source.substring(copiedUntil, position), literal, hidden, media);
			String name = tag.name();
			if (literal != null) {
				if (tag.closing() && tag.html() == literal.html() && name.equals(literal.name())) {
					if (!name.equals("noparse") && !(tag.html() && name.equals("code"))) lineBreak(text);
					literal = null;
				} else if (!(literal.html() && literal.name().equals("pre") && tag.html() && name.equals("code"))) {
					append(text, source.substring(position, tag.end()), literal, null, null);
				}
			} else if (hidden != null) {
				if (tag.html() && tag.closing() && name.equals(hidden)) hidden = null;
			} else if (tag.html() && (name.equals("script") || name.equals("style"))) {
				if (!tag.closing() && !tag.selfClosing()) hidden = name;
			} else if (LITERAL.contains(name)) {
				if (!name.equals("noparse") && !(tag.html() && name.equals("code"))) lineBreak(text);
				if (!tag.closing() && !tag.selfClosing()) literal = tag;
			} else if (MEDIA.contains(name)) {
				space(text);
				if (!tag.closing()) {
					text.append(mediaText(alt(tag.attributes()), "caption"));
					space(text);
					// HTML img와 src/등호형 BBCode img는 닫는 태그 없는 단독 이미지도 허용한다.
					if (!tag.selfClosing() && !(name.equals("img") && (tag.html() || !tag.attributes().isBlank()))) {
						media = tag;
					}
				} else if (media != null && name.equals(media.name())) {
					media = null;
				}
			} else if (name.equals("*") || (tag.html() && name.equals("li"))) {
				lineBreak(text);
				if (!tag.closing()) text.append("- ");
			} else if (name.equals("td") || name.equals("th")) {
				if (tag.closing()) text.append('\t');
			} else if (BLOCK.contains(name) || (tag.html() && HTML_BLOCK.contains(name))) {
				boolean paragraphAfterBullet = !tag.closing() && name.equals("p") && endsWithBullet(text);
				if (!paragraphAfterBullet) lineBreak(text);
			} else if (!tag.html() && !INLINE.contains(name)) {
				text.append(source, position, tag.end());
			}
			position = tag.end();
			copiedUntil = position;
		}
		append(text, source.substring(copiedUntil), literal, hidden, media);
		return text.toString().strip();
	}

	private static void append(StringBuilder text, String value, Token literal, String hidden, Token media) {
		if (hidden != null) return;
		if (literal != null) {
			text.append(literal.html() ? decodeEntities(value) : value);
		} else {
			String decoded = decodeEntities(value.replace("\\[", "[").replace("\\]", "]"));
			text.append(media == null ? decoded
				: mediaText(decoded, media.attributes().isBlank() ? media.name() : "caption"));
		}
	}

	private static String mediaText(String value, String name) {
		String clean = RESOURCE.matcher(value).replaceAll("");
		if ((name.equals("youtube") || name.equals("previewyoutube") || name.equals("video"))
			&& clean.strip().matches("[a-zA-Z0-9_-]{11}|[0-9]+")) return "";
		return clean;
	}

	private static String alt(String attributes) {
		if (attributes.stripLeading().startsWith("=")) return "";
		var matcher = ATTRIBUTE.matcher(attributes);
		while (matcher.find()) {
			if (!matcher.group(1).equalsIgnoreCase("alt")) continue;
			for (int group = 2; group <= 4; group++) {
				if (matcher.group(group) != null) return decodeEntities(matcher.group(group));
			}
		}
		return "";
	}

	private static String decodeEntities(String value) {
		return ENTITY.matcher(value).replaceAll(match -> {
			String entity = match.group();
			String decoded;
			if (entity.startsWith("&#")) {
				try {
					boolean hex = entity.charAt(2) == 'x' || entity.charAt(2) == 'X';
					int codePoint = Integer.parseInt(entity.substring(hex ? 3 : 2, entity.length() - 1), hex ? 16 : 10);
					decoded = Character.isValidCodePoint(codePoint) && !(codePoint >= 0xd800 && codePoint <= 0xdfff)
						? new String(Character.toChars(codePoint)) : entity;
				} catch (NumberFormatException ignored) {
					decoded = entity;
				}
			} else {
				decoded = entity.equals("&apos;") ? "'" : HtmlUtils.htmlUnescape(entity);
			}
			return java.util.regex.Matcher.quoteReplacement(decoded.replace('\u00a0', ' '));
		});
	}

	/** 따옴표 안의 ] / > 는 속성 값이므로 태그 끝으로 해석하지 않는다. */
	private static Token tokenAt(String source, int start) {
		char opening = source.charAt(start);
		if ((opening != '[' && opening != '<') || (start > 0 && source.charAt(start - 1) == '\\')) return null;
		boolean html = opening == '<';
		if (html && source.startsWith("<!--", start)) {
			int end = source.indexOf("-->", start + 4);
			return new Token("comment", false, true, true, "", end < 0 ? source.length() : end + 3);
		}
		if (html && source.regionMatches(true, start, "<!doctype", 0, 9)) {
			int end = source.indexOf('>', start + 9);
			if (end >= 0) return new Token("doctype", false, true, true, "", end + 1);
		}
		int cursor = start + 1;
		boolean closing = cursor < source.length() && source.charAt(cursor) == '/';
		if (closing) cursor++;
		int nameStart = cursor;
		if (cursor >= source.length()) return null;
		if (!html && source.charAt(cursor) == '*') {
			cursor++;
		} else {
			if (!asciiLetter(source.charAt(cursor))) return null;
			while (cursor < source.length() && (asciiLetter(source.charAt(cursor))
				|| Character.isDigit(source.charAt(cursor)) || (html && source.charAt(cursor) == '-'))) cursor++;
		}
		String name = source.substring(nameStart, cursor).toLowerCase(Locale.ROOT);
		char ending = html ? '>' : ']';
		if (cursor >= source.length()) return null;
		char next = source.charAt(cursor);
		if (next != ending && next != '=' && next != '/' && !Character.isWhitespace(next)) return null;
		int attributesStart = cursor;
		char quote = 0;
		for (; cursor < source.length(); cursor++) {
			char ch = source.charAt(cursor);
			if (quote != 0) {
				if (ch == quote) quote = 0;
			} else if ((ch == '"' || ch == '\'') && cursor > attributesStart
				&& (source.charAt(cursor - 1) == '=' || Character.isWhitespace(source.charAt(cursor - 1)))) {
				quote = ch;
			} else if (ch == ending) {
				String attributes = source.substring(attributesStart, cursor);
				boolean selfClosing = attributes.endsWith("/");
				if (selfClosing) attributes = attributes.substring(0, attributes.length() - 1);
				if (html && !validHtmlAttributes(attributes)) return null;
				return new Token(name, closing, html, selfClosing, attributes, cursor + 1);
			} else if (ch == '[' || ch == '<') {
				return null;
			}
		}
		return null;
	}

	private static boolean validHtmlAttributes(String attributes) {
		String value = attributes.stripTrailing();
		var matcher = HTML_ATTRIBUTE.matcher(value);
		int consumed = 0;
		while (matcher.find()) {
			if (matcher.start() != consumed) return false;
			consumed = matcher.end();
		}
		return consumed == value.length();
	}

	private static boolean asciiLetter(char ch) {
		return (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z');
	}

	private static boolean endsWithBullet(StringBuilder text) {
		int length = text.length();
		return length >= 2 && text.charAt(length - 2) == '-' && text.charAt(length - 1) == ' '
			&& (length == 2 || text.charAt(length - 3) == '\n');
	}

	private static void space(StringBuilder text) {
		if (!text.isEmpty() && !Character.isWhitespace(text.charAt(text.length() - 1))) text.append(' ');
	}

	private static void lineBreak(StringBuilder text) {
		while (!text.isEmpty() && (text.charAt(text.length() - 1) == '\t' || text.charAt(text.length() - 1) == ' ')) {
			text.setLength(text.length() - 1);
		}
		if (!text.isEmpty() && text.charAt(text.length() - 1) != '\n') text.append('\n');
	}

	private record Token(String name, boolean closing, boolean html, boolean selfClosing, String attributes, int end) {
	}
}
