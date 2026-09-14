package com.ssafy.dispatch.spark;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts explicit changes from an already separated, plain-text patch_chunk. No model or DB calls. */
public final class PatchChangeExtractor {
    public static final String RULE_VERSION = "change-rules-2";
    private static final String ACTIONS = "added|removed|fixed|resolved|corrected|increased|decreased|reduced|adjusted|changed|updated|improved|reworked|deprecated|enabled|disabled";
    private static final Pattern LEADING_ACTION = pattern("^(" + ACTIONS + ")\\b\\s+(.+)");
    private static final Pattern SUBJECT_ACTION = pattern(
            "^([^.!?;,]{1,160}?)\\s+(?:was |were |has been |have been )?(increased|decreased|reduced|added|removed|adjusted|changed|deprecated|fixed|corrected|reworked|improved|enabled|disabled)\\b(.*)$");
    private static final Pattern FUTURE_CONTEXT = pattern(
            "\\b(?:upcoming|next patch|next update|not yet live|not yet released|will (?:be |add|remove|increase|decrease)|planned changes)\\b");
    private static final Pattern NEGATED = pattern("\\b(?:not|never)\\s+(?:been\\s+)?(?:" + ACTIONS + ")\\b");
    private static final Pattern SECOND_ACTION = pattern("\\b(?:" + ACTIONS + ")\\b");
    private static final Pattern NAMED_PREFIX = pattern("^([^.!?;:]{1,100}?)\\s+[-–—:]\\s+(.+)$");
    private static final Pattern CURRENT_BEHAVIOR = pattern(
            "^((?:[^.!?;]|(?<=\\d)\\.(?=\\d)){1,160}?)\\s+(?:(?:is|are|can)\\s+now|now|no longer|can no longer)\\s+(.+)$");
    private static final Pattern DISCOURSE_SUBJECT = pattern(
            "^(?:we|we['’]ve|i|you|it|this|that|these|those|our|here|there|they|today|thank you)\\b|\\b(?:as we|we have|we['’]ve|last update|previous update)\\b");
    private static final Pattern NON_GAME_CONTEXT = pattern(
            "\\b(?:merchandise|merch|wishlist|sign up|signup|newsletter|our store|form on the store)\\b");
    private static final Pattern FEATURE_INTRO = pattern(
            "^(?:we['’]ve|we have) added\\b.*\\bin this update\\b");
    private static final Pattern FUTURE_HEADING = pattern(
            "^(?:upcoming|planned|future|next) (?:changes|update|patch|features)(?:\\s*[:.!].*)?$|^(?:this patch is )?not yet (?:live|released)[.!]?$" );
    private static final Pattern CURRENT_HEADING = pattern(
            "^(?:(?:[\\p{L}\\p{N}.]+\\s+){0,6})?(?:changelog|patch notes|bug fixes|fixes)(?:\\s*[:.!])?$" );
    private static final Pattern EXPLICIT_CURRENT_HEADING = pattern("^(?:current|released|live) (?:changes|patch notes|fixes)[:.!]?$" );
    private static final Pattern CONTINUATION = pattern(
            "^(?:it|its|they|their|this|these|when|where|which|can be|gives|maintains|events and|panel,|styling)\\b");

    private record Operation(String action, String targetPhrase) {}

    public record Change(String changeTypeCode, String directionCode, String targetTypeCode,
                         String evidenceQuote, String validationStatus) implements Serializable {}

    private PatchChangeExtractor() {}

    /** Heading metadata only constrains scope; it never guesses an entity's type or condition. */
    public static List<Change> extract(String headingPath, String chunkText) {
        if (chunkText == null || chunkText.isBlank()) throw new IllegalArgumentException("patch_chunk.text must not be blank");
        if (headingPath != null && (pattern("\\b(?:upcoming|planned|future|next|roadmap|previous|merchandise|merch|newsletter)\\b")
                .matcher(headingPath).find() || NON_GAME_CONTEXT.matcher(headingPath).find())) return List.of();
        return extract(chunkText);
    }

    public static List<Change> extract(String chunkText) {
        if (chunkText == null || chunkText.isBlank()) {
            throw new IllegalArgumentException("patch_chunk.text must not be blank");
        }
        List<Change> changes = new ArrayList<>();
        boolean futureSection = false;
        boolean namedFeatures = false;
        for (String block : inputBlocks(chunkText)) {
            String firstLine = block.lines().findFirst().orElse("").strip();
            if (FUTURE_HEADING.matcher(firstLine).matches()) {
                futureSection = true;
                namedFeatures = false;
                continue;
            }
            if (EXPLICIT_CURRENT_HEADING.matcher(firstLine).matches()) {
                futureSection = false;
                namedFeatures = false;
                continue;
            }
            if (CURRENT_HEADING.matcher(firstLine).matches()) {
                namedFeatures = false;
                continue;
            }
            if (NON_GAME_CONTEXT.matcher(firstLine).find()) {
                namedFeatures = false;
                continue;
            }
            if (futureSection) continue;
            if (FEATURE_INTRO.matcher(firstLine).find()) {
                namedFeatures = true;
                continue;
            }
            Matcher feature = NAMED_PREFIX.matcher(firstLine);
            if (namedFeatures && feature.matches() && !FUTURE_CONTEXT.matcher(firstLine).find()) {
                // The introduction supplies the addition evidence; do not infer a target class from a feature name.
                changes.add(new Change("add", "not_applicable", "unknown", block, "partial"));
                continue;
            }
            namedFeatures = false;
            for (String quote : splitOperations(block)) {
                Operation operation = operation(quote);
                if (operation == null) continue;
                String target = targetType(operation.targetPhrase());
                changes.add(new Change(changeType(operation.action()), direction(operation.action()), target,
                        quote, target.equals("unknown") ? "partial" : "valid"));
            }
        }
        return List.copyOf(changes);
    }

    /** Preserve follow-up explanations; splitting every sentence discards the actual change details. */
    private static List<String> inputBlocks(String text) {
        List<String> blocks = new ArrayList<>();
        int blockStart = -1;
        int blockEnd = -1;
        Matcher lines = Pattern.compile("[^\\r\\n]+").matcher(text);
        while (lines.find()) {
            String line = lines.group();
            String stripped = line.strip();
            if (stripped.isBlank() || stripped.matches("\\p{Cf}+")) continue;
            boolean bullet = stripped.matches("^(?:[-*•]|\\d+[.)])\\s+.*");
            String content = stripped.replaceFirst("^(?:[-*•]|\\d+[.)])\\s+", "");
            boolean explanatory = !bullet && CONTINUATION.matcher(content).find();
            if (blockStart >= 0 && !explanatory) {
                blocks.add(text.substring(blockStart, blockEnd));
                blockStart = -1;
            }
            // Keep the original span, including line endings and indentation, so evidence stays verbatim.
            if (blockStart < 0) blockStart = lines.start() + line.indexOf(content);
            blockEnd = lines.start() + line.stripTrailing().length();
        }
        if (blockStart >= 0) blocks.add(text.substring(blockStart, blockEnd));
        return blocks;
    }

    private static Operation operation(String quote) {
        String mainClause = quote.lines().findFirst().orElse("");
        Matcher prefix = NAMED_PREFIX.matcher(mainClause);
        boolean namedPrefix = prefix.matches();
        if (namedPrefix) mainClause = prefix.group(2);
        if (namedPrefix && pattern("^it['’]s no longer possible to\\b").matcher(mainClause).find()) {
            return new Operation("behavior", "");
        }
        if (namedPrefix && !LEADING_ACTION.matcher(mainClause).matches()
                && !SUBJECT_ACTION.matcher(mainClause).matches()
                && !pattern("^(?:the )?(?:players?|enemies|enemy|npcs?)\\b").matcher(mainClause).find()) {
            return null;
        }
        // Relative clauses describe causes/history, not a second operation on the direct subject.
        mainClause = mainClause.split("(?i)\\b(?:which|where|when|that)\\b|(?<=[.!?])\\s+", 2)[0].strip();
        if (DISCOURSE_SUBJECT.matcher(mainClause).find() || NON_GAME_CONTEXT.matcher(mainClause).find()
                || FUTURE_CONTEXT.matcher(mainClause).find() || NEGATED.matcher(mainClause).find()) return null;
        Matcher leading = LEADING_ACTION.matcher(mainClause);
        if (leading.matches()) return new Operation(leading.group(1), leading.group(2));
        Matcher subject = SUBJECT_ACTION.matcher(mainClause);
        if (subject.matches()) {
            if (SECOND_ACTION.matcher(subject.group(3)).find()) return null;
            return new Operation(subject.group(2), subject.group(1));
        }
        Matcher behavior = CURRENT_BEHAVIOR.matcher(mainClause);
        if (behavior.matches()) {
            if (pattern("^(?:a |the )?(?:fix|patch|update|hotfix|version)\\b").matcher(behavior.group(1)).find()) return null;
            return new Operation("behavior", behavior.group(1));
        }
        if (pattern("^Various (?:fixes|stability|performance)\\b").matcher(mainClause).find()) {
            return new Operation("behavior", "");
        }
        return null;
    }

    /** Split only independent explicit operations, outside parentheses and quoted names. */
    private static List<String> splitOperations(String block) {
        List<String> quotes = new ArrayList<>();
        int start = 0;
        int depth = 0;
        boolean quoted = false;
        for (int index = 0; index < block.length(); index++) {
            char character = block.charAt(index);
            if (character == '"') quoted = !quoted;
            if (quoted) continue;
            if (character == '(' || character == '[') depth++;
            if (character == ')' || character == ']') depth = Math.max(0, depth - 1);
            if (depth != 0) continue;
            int next = index + 1;
            if (block.startsWith(" and ", index)) next = index + 5;
            else if (character != '.' && character != '!' && character != '?' && character != ';') continue;
            while (next < block.length() && Character.isWhitespace(block.charAt(next))) next++;
            String remaining = block.substring(next);
            Matcher following = LEADING_ACTION.matcher(remaining);
            if (!following.matches() || pattern("^(?:it|its|them|their|this|these)\\b").matcher(following.group(2)).find()) continue;
            quotes.add(block.substring(start, index + (character == ' ' ? 0 : 1)).strip());
            start = next;
            index = next - 1;
        }
        String tail = block.substring(start).strip();
        if (!tail.isBlank()) quotes.add(tail);
        return quotes;
    }

    private static String changeType(String action) {
        return switch (action.toLowerCase(Locale.ROOT)) {
            case "added" -> "add";
            case "removed" -> "remove";
            case "fixed", "resolved", "corrected" -> "fix";
            case "deprecated" -> "deprecate";
            default -> "modify";
        };
    }

    private static String direction(String action) {
        return switch (action.toLowerCase(Locale.ROOT)) {
            case "increased" -> "increase";
            case "decreased", "reduced" -> "decrease";
            case "added", "removed", "deprecated", "fixed", "resolved", "corrected" -> "not_applicable";
            default -> "unknown";
        };
    }

    private static String targetType(String phrase) {
        // Examine the named subject, not a bug's narrative (e.g. 'crash when an enemy fires a weapon').
        String subject = phrase.split("(?i)\\b(?:when|where|while|caused|causing|that|which|to|from|for|with|of)\\b", 2)[0].strip();
        subject = subject.replaceFirst("(?i)^(?:(?:a|an|the|all|some|several|certain|new)\\s+)+", "");
        if (pattern("\\band\\b").matcher(subject).find()) return "unknown";
        String[][] explicitTypes = {
            {"player", "\\bplayers?\\b"}, {"enemy", "\\b(?:enemies|enemy|bosses|boss)\\b"},
            {"weapon", "\\b(?:weapons?|rifles?|shotguns?|pistols?|swords?|whips?)\\b"},
            {"item", "\\b(?:items?|potions?)\\b"}, {"skill", "\\b(?:skills?|abilities|ability|spells?)\\b"},
            {"map", "\\bmaps?\\b"}, {"system", "\\b(?:ui|menus?|matchmaking|save system|controller support)\\b"}
        };
        for (String[] candidate : explicitTypes) {
            if (pattern("^" + candidate[1]).matcher(subject).find()) return candidate[0];
        }
        return "unknown";
    }

    private static Pattern pattern(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }
}
