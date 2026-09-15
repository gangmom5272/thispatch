package com.ssafy.thispatch.spark;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Semantic preparation for rule extraction; not a token-sized embedding chunker or a DB writer. */
public final class PatchChangeSectioner {
    private static final Pattern HEADING = Pattern.compile(
            "(?is)\\[h([1-6])[^\\]]*](.*?)\\[/h\\1]|<h([1-6])(?:\\s[^>]*)?>(.*?)</h\\3>");

    public record Section(String headingPath, String text) {}

    private PatchChangeSectioner() {}

    public static List<Section> split(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) throw new IllegalArgumentException("Notice body must not be blank");
        List<Section> sections = new ArrayList<>();
        String[] headings = new String[6];
        Matcher matcher = HEADING.matcher(rawBody);
        int start = 0;
        while (matcher.find()) {
            append(sections, headings, rawBody.substring(start, matcher.start()));
            int level = Integer.parseInt(matcher.group(1) != null ? matcher.group(1) : matcher.group(3));
            String title = PatchClassifier.plainText(matcher.group(2) != null ? matcher.group(2) : matcher.group(4))
                    .replaceAll("\\p{Cf}", "").strip();
            headings[level - 1] = title;
            for (int index = level; index < headings.length; index++) headings[index] = null;
            start = matcher.end();
        }
        append(sections, headings, rawBody.substring(start));
        return List.copyOf(sections);
    }

    private static void append(List<Section> sections, String[] headings, String rawText) {
        String text = PatchClassifier.plainText(rawText).strip();
        if (text.replaceAll("\\p{Cf}", "").isBlank()) return;
        List<String> path = new ArrayList<>();
        for (String heading : headings) if (heading != null && !heading.isBlank()) path.add(heading);
        // Keep nested lists, plain-text item names, and follow-up paragraphs together.
        // Never carry the text of a previous section into the next section.
        sections.add(new Section(String.join(" > ", path), text));
    }
}
