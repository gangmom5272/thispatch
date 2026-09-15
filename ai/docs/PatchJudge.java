package com.ssafy.thispatch.spark;

import static org.apache.spark.sql.functions.callUDF;
import static org.apache.spark.sql.functions.col;

import java.io.Serializable;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.api.java.UDF3;
import org.apache.spark.sql.types.DataTypes;

/**
 * 공지가 패치노트인지 판정한다. news_raw 를 만들 때 is_patch · patch_reason 두 컬럼을 붙인다.
 *
 * <p>모델 없이 정규식 5개. PoC(0904, 209공지, 사람 라벨 490건 검토) 재현율 90%대.
 * 개발사가 붙인 태그 {@code patchnotes} 가 패치의 77% 를 바로 잡고, 나머지는 제목·본문 규칙이다.
 * 규칙 표와 근거: ai/CONTRACT.md 부록 A. 원본 Python: 0904/poc/scripts/16_rule_slots_3games.py judge().
 *
 * <p>AI 노드는 {@code is_patch = true} 행만 임베딩·Qwen 대상으로 읽는다. 여기서 빠지면 검색에 안 나온다.
 *
 * <pre>
 *   Dataset<Row> news = ...;   // gid, appid, title, contents, feed_tags, published_at
 *   news = PatchJudge.apply(spark, news);   // + is_patch (boolean), patch_reason (string)
 * </pre>
 */
public final class PatchJudge implements Serializable {

    private PatchJudge() {
    }

    /** BBCode 태그 제거. [b], [/b], [list], [*], [url=...] 등. */
    private static final Pattern BBCODE = Pattern.compile("\\[/?[a-zA-Z*][^\\]]*\\]");

    /** 본문에서 세는 "변경 동사". 매치 수가 5 이상·15 이상이 판정 기준. */
    private static final Pattern VERB = Pattern.compile(
            "(increased|decreased|reduced|buffed|nerfed|fixed|adjusted|changed|added|removed|lowered|raised"
            + "|improved|tweaked|rebalanced|reworked|replaced|resolved|corrected|no longer|can now|will now"
            + "|now deals|now has|now costs|now takes|now grants|updated|scaled|capped|doubled|halved"
            + "|disabled|enabled|renamed|restored|reverted)", Pattern.CASE_INSENSITIVE);

    /** 제목에 있으면 비패치로 기우는 단어(세일·이벤트·개발 일지 등). */
    private static final Pattern NEG = Pattern.compile(
            "(newsletter|\\bsale\\b|discount|%\\s*off|\\bevent\\b|dev\\s*diary|behind the scenes|deep dive"
            + "|roadmap|survey|soundtrack|\\bost\\b|merch|stream|trailer|recap|wallpaper|contest|giveaway"
            + "|free weekend|community spotlight|fan ?art|interview|anniversary|award|nomination|\\bq&a\\b"
            + "|lore|comic|cosplay|kickstarter|state of the game|celebrating)", Pattern.CASE_INSENSITIVE);

    /** 제목의 패치 키워드. */
    private static final Pattern PATCH_KW = Pattern.compile(
            "(patch|hotfix|hot-fix|update|fix(es|ed)?\\b|patch notes|release notes|changelog|balance|version"
            + "|\\bv?\\d+\\.\\d+(\\.\\d+)?\\b)", Pattern.CASE_INSENSITIVE);

    /** 제목이 예고·로드맵이면 패치 키워드가 있어도 제외. */
    private static final Pattern PREVIEW = Pattern.compile(
            "(preview|coming soon|upcoming|incoming|teaser|sneak peek|roadmap|what.s next|in development"
            + "|announc(e|ing)|reveal|delay|postpone|arrives|will be|on \\w+ \\d+(st|nd|rd|th))",
            Pattern.CASE_INSENSITIVE);

    /** 출시 문구. 버전 번호나 변경 동사가 함께 있으면 패치로 본다. */
    private static final Pattern RELEASE = Pattern.compile(
            "(out now|now available|now live|is live|has arrived|released|launch(es|ed)?\\b|available now)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern VERSION = Pattern.compile("\\bv?\\d+\\.\\d+(\\.\\d+)?\\b");

    /** 판정 결과. reason 은 news.patch_reason 에 그대로 저장한다. */
    public record Verdict(boolean isPatch, String reason) {
    }

    private static int count(Pattern p, String s) {
        Matcher m = p.matcher(s);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    /**
     * @param title    news.title
     * @param contents news.contents (BBCode 그대로. 여기서 지운다)
     * @param feedTags news.feed_tags (스팀 tags 배열을 문자열로 담은 것. "patchnotes" 포함 여부만 본다)
     */
    public static Verdict judge(String title, String contents, String feedTags) {
        String t = title == null ? "" : title;
        String body = BBCODE.matcher(contents == null ? "" : contents).replaceAll(" ");
        int verbs = count(VERB, body);
        boolean version = VERSION.matcher(t).find();

        if (feedTags != null && feedTags.toLowerCase().contains("patchnotes")) {
            return new Verdict(true, "1:tag");
        }
        if (NEG.matcher(t).find() && !PATCH_KW.matcher(t).find() && verbs < 15) {
            return new Verdict(false, "2:negative");
        }
        if (RELEASE.matcher(t).find() && (verbs >= 5 || version)) {
            return new Verdict(true, "3:release");
        }
        if (PATCH_KW.matcher(t).find() && !PREVIEW.matcher(t).find() && (verbs >= 5 || version)) {
            return new Verdict(true, "4:title_kw");
        }
        if (verbs >= 15) {
            return new Verdict(true, "5:body_verbs");
        }
        return new Verdict(false, "6:else");
    }

    /** Dataset 에 is_patch · patch_reason 컬럼을 붙인다. UDF 두 번 대신 reason 하나만 계산하고 is_patch 는 접두 판정. */
    public static Dataset<Row> apply(SparkSession spark, Dataset<Row> news) {
        spark.udf().register("patch_reason",
                (UDF3<String, String, String, String>) (title, contents, tags) -> judge(title, contents, tags).reason(),
                DataTypes.StringType);
        Dataset<Row> withReason = news.withColumn("patch_reason",
                callUDF("patch_reason", col("title"), col("contents"), col("feed_tags")));
        // 패치인 reason 은 1·3·4·5 로 시작한다
        return withReason.withColumn("is_patch",
                col("patch_reason").rlike("^[1345]:"));
    }

    /** 규칙을 고칠 때 최소 확인용. PoC 기준 기대값. */
    public static void main(String[] args) {
        List<String[]> cases = List.of(
                new String[] {"Patch Notes 1.2.3", "[list][*]Fixed a crash[/list]", "patchnotes", "1:tag"},
                new String[] {"Summer Sale is live!", "50% off this week", "", "2:negative"},
                new String[] {"Update 3.4.0", "Fixed one issue. Added two maps. Reduced damage. Changed UI. Removed bug.", "", "4:title_kw"},
                new String[] {"Roadmap: what's next", "Added Fixed Changed Removed Reduced Increased", "", "2:negative"},
                new String[] {"The Neowsletter - August", "[img]x[/img] thanks for playing", "", "6:else"});  // 오타 제목은 NEG 에 안 걸려 6번으로
        for (String[] c : cases) {
            Verdict v = judge(c[0], c[1], c[2]);
            System.out.printf("%-28s -> %-12s %s%n", c[0], v.reason(), v.reason().equals(c[3]) ? "ok" : "EXPECTED " + c[3]);
        }
    }
}
