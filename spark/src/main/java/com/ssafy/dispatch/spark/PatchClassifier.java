package com.ssafy.dispatch.spark;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** English-first Steam notice classifier. No date inference, model calls, or database writes. */
public final class PatchClassifier {
    public static final String RULE_VERSION = "patch-rules-3";

    public enum Decision { PATCH, NOT_PATCH, REVIEW_REQUIRED }
    public enum Scope { DEFAULT, TEST, MIXED, CLIENT, NON_STEAM, OTHER_GAME }

    public record Result(Decision decision, Scope scope, int stage, String reason, String evidence) {}

    private static final Pattern TEST_SCOPE = pattern("\\b(?:public test|stress test|test branch|beta|experimental|unstable)\\b|공개 테스트|테스트 서버");
    private static final Pattern STABLE_TITLE = pattern("\\b(?:stable|live branch|main branch)\\b");
    private static final Pattern TEST_AVAILABLE = pattern(
            "\\b(?:we(?:['’]ve| have) made it|(?:this|the) (?:patch|update) is) available on (?:a |the )?(?:test|beta|experimental|unstable) branch\\b");
    private static final Pattern MOBILE_TITLE = pattern("\\b(?:mobile|android|ios)\\b");
    private static final Pattern PC_TITLE = pattern("\\b(?:pc|steam|all platforms)\\b");
    private static final Pattern EXPLICIT_UPDATE_TARGET = pattern(
            "\\bupdate for ([\\p{L}\\p{N}][\\p{L}\\p{N} :'’&()\\-]{0,100}?) (?:is now live|is live|is now available)\\b");
    private static final Pattern DEFERRED_CURRENT_PATCH = pattern(
            "\\b(?:these patch notes|this patch|this update|the patch notes) (?:will not|won['’]t) (?:go live|be released|be deployed) until [^.!?\\n]{1,80}");
    private static final Pattern COMPLETED_SECURITY_FIX = pattern(
            "\\bwe(?:['’]ve| have) already (?:patched|fixed|resolved) (?:the |this )?(?:issue|vulnerability|exploit) on (?:the )?clients\\b");
    private static final Pattern TEST_ONLY_INTRO = pattern("(?:this|the) (?:update|patch).{0,70}(?:public test|test branch|beta branch).{0,30}only");
    private static final Pattern ANNOUNCEMENT = pattern("\\b(?:patch preview|update preview|preview of.{0,30}(?:patch|update)|upcoming (?:patch|update)|release date|pre[ -]?order|pre[ -]?purchase)\\b|패치 예고|업데이트 예고|사전 예약");
    private static final Pattern FUTURE_TITLE = pattern("\\b(?:coming|arrives?|launches?|releases?|enters|scheduled).{0,90}\\b(?:tomorrow|next|this (?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)|on \\d|on (?:january|february|march|april|may|june|july|august|september|october|november|december))\\b");
    private static final Pattern FUTURE_DEPLOYMENT = pattern("\\b(?:patch|hotfix|update).{0,65}\\b(?:will (?:be released|be deployed|arrive)|scheduled for release|coming next week)\\b");
    private static final Pattern MAIN_GAME_UNAFFECTED = pattern("(?:main|base) game is not (?:impacted|affected)|본편.{0,20}(?:영향이 없|영향을 받지)");
    private static final Pattern MERCHANDISE = pattern("\\b(?:vinyl|soundtrack|statue|replicas?|lamp|ticket sales|pre[ -]?order|loyalty discount|popularity poll)\\b");
    private static final Pattern COMMUNITY_TITLE = pattern("^(?:community update|word from the devs|tell us your)|\\b(?:grand champions|team registration|invitations and qualifiers|community quests|behind the scenes|celebrating our community)\\b");
    private static final Pattern SUBMISSION = pattern("\\b(?:looking for|submit).{0,70}\\b(?:new items|weapon finishes|workshop submissions)\\b");
    private static final Pattern PATCH_TITLE = pattern("\\b(?:hotfix(?:es)?|patch(?:es)?|release notes?|bug fixes|changelog)\\b|패치|핫픽스|업데이트 내역");
    private static final Pattern UPDATE_TITLE = pattern("\\bupdate\\b|업데이트");
    private static final Pattern LAUNCH_TITLE = pattern("\\b(?:official release|official launch|has arrived|launch trailer|launches on steam|DLC.{0,30}(?:available|out now))\\b|정식 출시");
    private static final Pattern DEPLOYED = pattern("\\b(?:patch|hotfix|update|fix).{0,100}\\b(?:now live|is live|out now|now available|available now|has been released|now rolling out|going live today|ready for you today|went live)\\b|(?:패치|업데이트).{0,40}(?:적용 완료|배포 완료)");
    private static final Pattern CHANGE_LINE = pattern("^(?:[-*•]\\s*)?(?:fixed|added|removed|adjusted|increased|decreased|improved|reworked|resolved|updated|修正|추가|수정|개선|삭제)\\b.{4,}");
    private static final Pattern CHANGE_HEADING = pattern("^(?:patch notes.*|patch highlights|changelog.*|bug fixes|fixes(?:\\s*(?:&|and)\\s*improvements)?|balance adjustments|gameplay|new content|패치 노트.*|변경 사항.*):?$");
    private static final Pattern CHANGELOG_TITLE = pattern("\\bchangelog\\b|업데이트 내역");
    private static final Pattern NAMED_CHANGE_HEADING = pattern(
            "^(?:[\\p{L}\\p{N}][\\p{L}\\p{N} .:'’()\\-]{0,90}\\s+)?(?:changelog|patch notes)(?:\\s.*)?$");
    private static final Pattern CHANGE_INTRODUCTION = pattern(
            "\\bwe(?:['’]ve| have)\\s+(?:added|introduced|improved|reworked)\\b[^.!?\\n]{0,160}\\b(?:this|the latest) update\\b"
            + "|\\b(?:this|our latest) update\\s+(?:adds|introduces|includes|brings)\\b");
    private static final Pattern NAMED_FEATURE = pattern(
            "^(?:[-*•]\\s*)?[\\p{L}\\p{N}][\\p{L}\\p{N} '&’()/]{1,70}\\s+[-–—]\\s+.{25,}$");
    private static final Pattern SECTION_STOP = pattern(
            "^(?:merchandise|merch|shop|soundtrack|community news|steam workshop news|development update|roadmap|what['’]s next)\\b");
    private static final Pattern NON_CURRENT_CONTEXT = pattern(
            "\\b(?:upcoming|next update|next patch|last week|last month|last year|previous update|previous patch|"
            + "coming soon|in development|not yet|will (?:add|introduce|include|release)|plan to|planning to)\\b");
    private static final Pattern PREVIEW_OR_CANCELLED_TITLE = pattern(
            "\\b(?:patch|update)\\b.{0,60}\\b(?:preview|cancelled|canceled|postponed|delayed)\\b"
            + "|\\b(?:preview|cancelled|canceled|postponed|delayed)\\b.{0,60}\\b(?:patch|update)\\b");
    private static final Pattern RETROSPECTIVE_TITLE = pattern("\\b(?:state of the game|year in review|retrospective|recap)\\b");

    private PatchClassifier() {}

    public static Result classify(String title, String contents, List<String> tags) {
        return classify(null, title, contents, tags);
    }

    /** sourceGameName is the collected app's canonical display name, not a name inferred from this notice. */
    public static Result classify(String sourceGameName, String title, String contents, List<String> tags) {
        String cleanTitle = plainText(title == null ? "" : title).strip();
        String body = plainText(contents == null ? "" : contents);
        List<String> lines = body.lines().map(String::strip).filter(line -> !line.isBlank()).toList();
        String intro = body.substring(0, Math.min(body.length(), 1200));
        Scope scope = TEST_SCOPE.matcher(cleanTitle).find() || TEST_ONLY_INTRO.matcher(intro).find()
                ? Scope.TEST : Scope.DEFAULT;
        String availableTest = firstMatch(TEST_AVAILABLE, body);
        if (!availableTest.isEmpty()) {
            scope = Scope.TEST;
        }
        if (scope == Scope.TEST && STABLE_TITLE.matcher(cleanTitle).find()) {
            scope = Scope.MIXED;
        }
        if (cleanTitle.isBlank() || body.isBlank()) {
            return new Result(Decision.REVIEW_REQUIRED, scope, 0, "MISSING_TITLE_OR_BODY", cleanTitle);
        }

        // A feed can advertise another game's update. A mismatch is not proof that no patch exists.
        Matcher target = EXPLICIT_UPDATE_TARGET.matcher(intro);
        if (target.find() && (sourceGameName == null || sourceGameName.isBlank()
                || !normalizeGameName(sourceGameName).equals(normalizeGameName(target.group(1))))) {
            return new Result(Decision.REVIEW_REQUIRED, Scope.OTHER_GAME, 2,
                    "UPDATE_TARGET_REQUIRES_VERIFICATION", target.group());
        }
        // Do not reject a PC patch merely because its body mentions a later mobile release.
        if (MOBILE_TITLE.matcher(cleanTitle).find() && !PC_TITLE.matcher(cleanTitle).find()) {
            return new Result(Decision.REVIEW_REQUIRED, Scope.NON_STEAM, 2,
                    "MOBILE_PLATFORM_REQUIRES_VERIFICATION", cleanTitle);
        }
        String deferredPatch = firstMatch(DEFERRED_CURRENT_PATCH, body);
        if (!deferredPatch.isEmpty()) {
            // One notice can announce a future live deployment and an already available test build.
            return new Result(Decision.REVIEW_REQUIRED, availableTest.isEmpty() ? scope : Scope.MIXED, 2,
                    availableTest.isEmpty() ? "DEFERRED_DEPLOYMENT_REQUIRES_VERIFICATION"
                            : "TEST_AVAILABLE_LIVE_DEPLOYMENT_PENDING",
                    deferredPatch + (availableTest.isEmpty() ? "" : "\n" + availableTest));
        }

        // 1. Exact tag match is evidence, not an unconditional early return.
        boolean tagged = tags != null && tags.stream().anyMatch(tag -> tag != null && "patchnotes".equalsIgnoreCase(tag.strip()));
        List<String> changes = new ArrayList<>();
        for (String line : lines) {
            if (CHANGE_LINE.matcher(line).find()) {
                changes.add(line);
            }
        }
        String deployed = firstMatch(DEPLOYED, cleanTitle + "\n" + intro);
        String securityFix = firstMatch(COMPLETED_SECURITY_FIX, intro);
        if (deployed.isEmpty() && !securityFix.isEmpty()) {
            deployed = securityFix;
        }
        if (!securityFix.isEmpty() && scope == Scope.DEFAULT) {
            scope = Scope.CLIENT;
        }
        String sectionEvidence = findChangeSection(lines);
        if (sectionEvidence.isEmpty() && changes.size() >= 2 && CHANGELOG_TITLE.matcher(cleanTitle).find()) {
            sectionEvidence = cleanTitle + "\n" + changes.get(0);
        }
        String narrativeEvidence = findNarrativeChanges(lines);
        String changeQuote = !deployed.isEmpty() ? deployed
                : !sectionEvidence.isEmpty() ? sectionEvidence : narrativeEvidence;
        boolean changeEvidence = !changeQuote.isEmpty();

        // 2. Negative patterns are scoped to the title or deployment statement, not arbitrary 'will/preview'.
        String exclusion = firstMatch(MAIN_GAME_UNAFFECTED, intro);
        String exclusionReason = "MAIN_GAME_UNAFFECTED";
        if (exclusion.isEmpty() && (ANNOUNCEMENT.matcher(cleanTitle).find() || FUTURE_TITLE.matcher(cleanTitle).find()
                || PREVIEW_OR_CANCELLED_TITLE.matcher(cleanTitle).find())) {
            exclusion = cleanTitle;
            exclusionReason = "ANNOUNCEMENT_OR_PREVIEW";
        }
        if (exclusion.isEmpty() && FUTURE_DEPLOYMENT.matcher(intro).find() && deployed.isEmpty()) {
            exclusion = firstMatch(FUTURE_DEPLOYMENT, intro);
            exclusionReason = "FUTURE_DEPLOYMENT";
        }
        if (exclusion.isEmpty() && MERCHANDISE.matcher(cleanTitle).find() && !changeEvidence) {
            exclusion = cleanTitle;
            exclusionReason = "PROMOTION_OR_MERCHANDISE";
        }
        if (exclusion.isEmpty() && SUBMISSION.matcher(intro).find() && !changeEvidence) {
            exclusion = firstMatch(SUBMISSION, intro);
            exclusionReason = "COMMUNITY_SUBMISSION";
        }
        if (exclusion.isEmpty() && COMMUNITY_TITLE.matcher(cleanTitle).find() && !changeEvidence) {
            exclusion = cleanTitle;
            exclusionReason = "COMMUNITY_NEWS";
        }
        // A monthly recap can quote an old changelog. Do not create a new applied-patch case from it.
        if (exclusion.isEmpty() && RETROSPECTIVE_TITLE.matcher(cleanTitle).find() && deployed.isEmpty()) {
            exclusion = cleanTitle;
            exclusionReason = "RETROSPECTIVE_OR_STATUS_NEWS";
        }
        if (!exclusion.isEmpty()) {
            return new Result(tagged ? Decision.REVIEW_REQUIRED : Decision.NOT_PATCH, scope, 2,
                    tagged ? "TAG_CONFLICT_" + exclusionReason : exclusionReason, exclusion);
        }

        if (!securityFix.isEmpty()) {
            return new Result(Decision.PATCH, scope, 5, "COMPLETED_CLIENT_SECURITY_FIX", securityFix);
        }

        // 3. A launch may include an update to an existing early-access game. Do not discard its changelog.
        if (LAUNCH_TITLE.matcher(cleanTitle).find()) {
            if (changeEvidence) {
                return new Result(Decision.PATCH, scope, 3, "RELEASE_WITH_CHANGE_EVIDENCE",
                        changeQuote);
            }
            return new Result(Decision.REVIEW_REQUIRED, scope, 3, "RELEASE_SCOPE_UNCLEAR", cleanTitle);
        }
        if (tagged) {
            return new Result(Decision.PATCH, scope, 1, "PATCHNOTES_TAG", "patchnotes");
        }

        // 4. 'Update' alone may mean a community/status update. Require more support than for 'Hotfix'.
        if (PATCH_TITLE.matcher(cleanTitle).find()) {
            return new Result(Decision.PATCH, scope, 4, "PATCH_TITLE", cleanTitle);
        }
        if (UPDATE_TITLE.matcher(cleanTitle).find()) {
            if (changeEvidence || changes.size() >= 2) {
                return new Result(Decision.PATCH, scope, 4, "UPDATE_TITLE_WITH_CHANGES", cleanTitle);
            }
            return new Result(Decision.REVIEW_REQUIRED, scope, 4, "UPDATE_TITLE_ONLY", cleanTitle);
        }

        // 5. Generic titles need an actual deployment statement or a structured change list.
        if (changeEvidence) {
            return new Result(Decision.PATCH, scope, 5, "BODY_CHANGE_EVIDENCE",
                    changeQuote);
        }
        return new Result(Decision.REVIEW_REQUIRED, scope, 5, "INSUFFICIENT_EVIDENCE", cleanTitle);
    }

    private static Pattern pattern(String expression) {
        return Pattern.compile(expression, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    private static String normalizeGameName(String name) {
        return name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static String findChangeSection(List<String> lines) {
        for (int index = 0; index < lines.size(); index++) {
            String heading = lines.get(index).replaceAll("\\p{Cf}", "")
                    .replaceFirst("^[^\\p{L}\\p{N}]+", "").strip();
            if (heading.length() > 160 || NON_CURRENT_CONTEXT.matcher(heading).find()) {
                continue;
            }
            if (!CHANGE_HEADING.matcher(heading).matches() && !NAMED_CHANGE_HEADING.matcher(heading).matches()) {
                continue;
            }
            String evidence = findFollowingChanges(lines, index, false);
            if (!evidence.isEmpty()) {
                return evidence;
            }
        }
        return "";
    }

    private static String findNarrativeChanges(List<String> lines) {
        for (int index = 0; index < lines.size(); index++) {
            String introduction = lines.get(index);
            if (!CHANGE_INTRODUCTION.matcher(introduction).find()
                    || NON_CURRENT_CONTEXT.matcher(introduction).find()) {
                continue;
            }
            // Check the preceding paragraph too: 'in development' can govern the whole list.
            if (index > 0 && NON_CURRENT_CONTEXT.matcher(lines.get(index - 1)).find()) {
                continue;
            }
            String evidence = findFollowingChanges(lines, index, true);
            if (!evidence.isEmpty()) {
                return evidence;
            }
        }
        return "";
    }

    private static String findFollowingChanges(List<String> lines, int start, boolean allowNamedFeatures) {
        int count = 0;
        String firstChange = "";
        // Local windows allow subsection labels (Items, NPCs), without pairing a heading with the entire notice.
        int end = Math.min(lines.size(), start + (allowNamedFeatures ? 21 : 51));
        for (int index = start + 1; index < end; index++) {
            String line = lines.get(index);
            String normalized = line.replaceFirst("^[^\\p{L}\\p{N}]+", "");
            if (SECTION_STOP.matcher(normalized).find() || NON_CURRENT_CONTEXT.matcher(line).find()) {
                break;
            }
            if (CHANGE_LINE.matcher(line).find() || (allowNamedFeatures && NAMED_FEATURE.matcher(line).matches())) {
                if (count++ == 0) {
                    firstChange = line;
                }
                if (count == 2) {
                    return lines.get(start) + "\n" + firstChange + "\n" + line;
                }
            }
        }
        return "";
    }

    private static String firstMatch(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group() : "";
    }

    static String plainText(String text) {
        // Remove formatting only; preserve headings, sentence order and change-list boundaries.
        return text.replaceAll("(?is)\\[img[^\\]]*\\].*?\\[/img\\]", " ")
                .replaceAll("(?i)\\[/?(?:h[1-6]|list|olist|\\*|p|tr)[^\\]]*\\]|<\\s*/?(?:p|br|li|h[1-6]|div)[^>]*>", "\n")
                .replaceAll("(?i)\\[/?(?:url|b|i|u|quote|table|th|td|strike|spoiler)[^\\]]*\\]|<[^>]*>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"")
                .replaceAll("[\\t ]+", " ");
    }
}
