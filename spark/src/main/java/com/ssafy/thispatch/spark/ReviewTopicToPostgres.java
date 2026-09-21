package com.ssafy.thispatch.spark;

import static org.apache.spark.sql.functions.col;

import com.ssafy.thispatch.common.HdfsPaths;
import com.ssafy.thispatch.common.SparkSessions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Encoders;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;
import org.apache.spark.storage.StorageLevel;

/**
 * AI 가 붙인 리뷰 토픽을 서비스 DB 에 적재한다 — {@code review_topic}.
 *
 * <pre>
 *   DB_URL=... DB_USER=... DB_PASSWORD=... \
 *   spark-submit --class com.ssafy.thispatch.spark.ReviewTopicToPostgres ... thispatch-spark.jar [--dry-run]
 * </pre>
 *
 * <h2>입력 (ai/CONTRACT.md 3-3)</h2>
 *
 * <p>{@code /review_topic/dt=D/*.parquet} — {@code recommendationid long · appid long · topic_id int16 · score float32}.
 * 리뷰 하나에 토픽 여러 행(다중 라벨). {@code score} 는 넣지 않는다.
 *
 * <p><b>언어 8개만 넣는다</b> (CONTRACT 3-3 · 2026-09-19 결정). 파일에는 전 언어가 분류돼 있지만 검증된 것은
 * {@link #LANGUAGES} 뿐이다. 필터는 파일이 아니라 {@code recent_review.language_code} 로 건다 — 언어를
 * 더하거나 빼는 것은 이 목록만 바꾸면 되고 AI 재실행이 필요 없다. 2026-09-21 실측: recent_review 96만 중 82만(85%).
 * 여러 날짜 파티션에 같은 (recommendationid, topic_id) 가 있으면 하나로 본다 — 표의 PK 가 그 둘이다.
 *
 * <h2>왜 recommendationid 를 review_id 로 바꾸는가</h2>
 *
 * <p>{@code review_topic.review_id} 는 {@code recent_review} 의 BIGSERIAL PK 를 참조한다(FK). 스팀 ID 가
 * 아니라 우리 표의 대리키다. 그래서 {@code recent_review} 의 (recommendationid, review_id) 짝을 드라이버가
 * 읽어 브로드캐스트 조인으로 바꾼다. {@code recent_review} 는 최근 14일 창이므로 <b>창 밖 리뷰의 토픽은
 * 매핑이 안 돼 빠진다 — 그게 정상이다.</b> 몇 건이 빠졌는지 찍는다.
 *
 * <h2>순서</h2>
 *
 * <p>{@link RecentReviewToPostgres} 가 표를 갈아엎으면 review_id 가 새로 매겨진다. 그래서 매일
 * 「review_topic 비움 → recent_review 갈아엎음 → review_topic 다시 넣음」 순서로 돌아야 하고, 이 클래스는
 * 그 마지막 단계다. {@code RecentReviewToPostgres} 는 review_topic 에 행이 있으면 멈추므로, 파이프라인은
 * 그 앞에서 이 표를 비운다({@code --clear-only}).
 *
 * <p>{@code topic} 표가 비어 있으면(시드 없음) FK 때문에 한 줄도 못 넣는다 — 멈춘다. DB 는 드라이버만
 * 만진다({@link LoaderSupport}).
 */
public final class ReviewTopicToPostgres {

    private static final int BATCH = 2_000;

    /** review_topic 을 넣는 언어. CONTRACT 3-3 의 검증된 8개 — 순서는 의미 없다. */
    static final List<String> LANGUAGES = List.of(
            "english", "koreana", "schinese", "russian", "japanese", "german", "french", "spanish");

    /** 브로드캐스트 조인용 (recommendationid, review_id) 짝의 스키마. */
    static final StructType PAIR = new StructType()
            .add("recommendationid", DataTypes.LongType, false)
            .add("review_id", DataTypes.LongType, false);

    private ReviewTopicToPostgres() {
    }

    public static void main(String[] args) {
        boolean dryRun = false;
        boolean clearOnly = false;
        boolean incremental = false;
        for (String a : args) {
            switch (a.trim()) {
                case "--dry-run" -> dryRun = true;
                case "--clear-only" -> clearOnly = true;
                // 증분: recent_review 가 upsert 로 유지되어 review_id 가 안 바뀔 때. 비우지 않고 새 짝만 더한다(ON CONFLICT DO NOTHING).
                case "--incremental" -> incremental = true;
                default -> throw new IllegalArgumentException("모르는 인자: " + a);
            }
        }
        String url = LoaderSupport.env("DB_URL");
        String user = LoaderSupport.env("DB_USER");
        String password = LoaderSupport.env("DB_PASSWORD");

        if (clearOnly) {
            // recent_review 를 갈아엎기 전에 부른다. 스파크를 띄우지 않는다.
            long before = LoaderSupport.count(url, user, password, "review_topic");
            if (!dryRun) {
                clear(url, user, password);
            }
            System.out.println("review_topic 비움  " + before + "행 → " + (dryRun ? "(dry-run, 그대로)" : "0행"));
            return;
        }

        SparkSession spark = SparkSessions.build("review-topic-to-postgres");
        try {
            if (LoaderSupport.existing(spark, new String[] {HdfsPaths.REVIEW_TOPIC}).isEmpty()) {
                System.out.println("읽을 것이 없다: " + HdfsPaths.REVIEW_TOPIC + " — AI 배치 산출물이 아직 HDFS 에 없다.");
                return;
            }
            Dataset<Row> topics = spark.read().parquet(HdfsPaths.REVIEW_TOPIC)
                    .select(col("recommendationid").cast(DataTypes.LongType),
                            col("appid").cast(DataTypes.LongType),
                            col("topic_id").cast(DataTypes.ShortType))
                    .persist(StorageLevel.MEMORY_AND_DISK());
            try {
                long total = topics.count();
                System.out.println("review_topic 파케이  " + total + "행 (날짜 파티션 전부 · 중복 포함)");
                if (total == 0) {
                    System.out.println("행이 없다. 아무것도 하지 않는다.");
                    return;
                }
                long badRows = topics.filter(invalidInput()).count();
                if (badRows > 0) {
                    System.out.println("⚠ 키가 비었거나 0 이하인 행  " + badRows + "건 — 뺀다");
                }
                Dataset<Row> distinctPairs = topics.filter(invalidInput().equalTo(false))
                        .select("recommendationid", "topic_id").distinct();
                long pairs = distinctPairs.count();
                System.out.println("(리뷰, 토픽) 짝   " + pairs + "건");

                // 참조 목록은 드라이버가 읽는다 (클래스 주석 · LoaderSupport)
                List<Row> pairRows = readPairs(url, user, password);
                List<Long> topicIds = LoaderSupport.readLongs(url, user, password, "SELECT topic_id FROM topic");
                long allRecent = LoaderSupport.count(url, user, password, "recent_review");
                System.out.println("recent_review " + pairRows.size() + "건 (언어 " + LANGUAGES.size() + "개만 · 전체 " + allRecent
                        + "건) · topic " + topicIds.size() + "개 (드라이버가 읽음)");
                if (topicIds.isEmpty()) {
                    throw new IllegalStateException("topic 표가 비어 있다. FK 때문에 review_topic 을 넣을 수 없다 — 시드 마이그레이션(V11)을 볼 것.");
                }
                if (pairRows.isEmpty()) {
                    throw new IllegalStateException("recent_review 가 비어 있다. review_id 를 매길 수 없다 — RecentReviewToPostgres 를 먼저 돌릴 것.");
                }
                Dataset<Row> recent = spark.createDataFrame(pairRows, PAIR);
                Dataset<Row> knownTopics = spark.createDataset(topicIds, Encoders.LONG()).toDF("known_topic");

                Dataset<Row> assigned = assign(distinctPairs, recent, knownTopics).persist(StorageLevel.MEMORY_AND_DISK());
                try {
                    long kept = assigned.count();
                    long unknownTopic = distinctPairs.join(knownTopics,
                            distinctPairs.col("topic_id").cast(DataTypes.LongType).equalTo(knownTopics.col("known_topic")), "left_anti").count();
                    if (unknownTopic > 0) {
                        System.out.println("⚠ topic 표에 없는 topic_id 행  " + unknownTopic + "건 — 뺀다. 시드와 AI 고정값(balance=1 … bm=5)이 어긋났는지 볼 것");
                        distinctPairs.join(knownTopics, distinctPairs.col("topic_id").cast(DataTypes.LongType).equalTo(knownTopics.col("known_topic")), "left_anti")
                                .groupBy("topic_id").count().show(10, false);
                    }
                    long outOfWindow = pairs - unknownTopic - kept;
                    System.out.println("recent_review 창 밖이라 빼는 것  " + outOfWindow + "건 (14일 창 — 정상)");
                    System.out.println("넣을 것   " + kept + "건");
                    assigned.groupBy("topic_id").count().orderBy("topic_id").show(10, false);
                    if (dryRun) {
                        System.out.println("--dry-run 이라 쓰지 않는다.");
                        return;
                    }
                    String insert = "INSERT INTO review_topic (review_id, topic_id) VALUES (?, ?) ON CONFLICT DO NOTHING";
                    LoaderSupport.RowBinder binder = (ps, r) -> {
                        ps.setLong(1, r.<Long>getAs("review_id"));
                        ps.setShort(2, r.<Short>getAs("topic_id"));
                    };
                    if (incremental) {
                        long before = LoaderSupport.count(url, user, password, "review_topic");
                        long sent = LoaderSupport.appendTable(assigned, url, user, password, insert, BATCH, binder);
                        long after = LoaderSupport.count(url, user, password, "review_topic");
                        System.out.println("증분 결과  보낸 " + sent + "건 · 새로 들어간 " + (after - before) + "건 (나머지는 이미 있던 짝) · DB review_topic " + before + " → " + after + "행");
                        return;
                    }
                    long written = LoaderSupport.replaceTable(new String[] {"review_topic"}, assigned, url, user, password,
                            // 같은 (review_id, topic_id) 가 와도 넘어간다 — CONTRACT 3-3 이 권하는 upsert. 표를 비운 뒤 넣고
                            // 넣는 것도 distinct 라 실제로 부딪힐 일은 없지만, 일일 배치에서 수정된 리뷰가 다시 올 때의 안전장치다.
                            "INSERT INTO review_topic (review_id, topic_id) VALUES (?, ?) ON CONFLICT DO NOTHING", BATCH,
                            (ps, r) -> {
                                ps.setLong(1, r.<Long>getAs("review_id"));
                                ps.setShort(2, r.<Short>getAs("topic_id"));
                            });
                    long inDb = LoaderSupport.count(url, user, password, "review_topic");
                    System.out.println("넣었다    " + written + "건 · DB review_topic " + inDb + "행");
                    if (inDb > written) {
                        throw new IllegalStateException("DB 행 수가 넣은 수보다 많다: " + inDb + " vs " + written);
                    }
                    if (inDb < written) {
                        System.out.println("⚠ ON CONFLICT 로 넘어간 행  " + (written - inDb) + "건");
                    }
                } finally {
                    assigned.unpersist();
                }
            } finally {
                topics.unpersist();
            }
        } finally {
            spark.stop();
        }
    }

    // ── 순수 Spark 부분 (테스트가 여기를 본다) ──────────────────────

    /** 키가 비었거나 0 이하인 행. */
    static org.apache.spark.sql.Column invalidInput() {
        return col("recommendationid").isNull().or(col("recommendationid").leq(0))
                .or(col("topic_id").isNull()).or(col("topic_id").leq(0));
    }

    /**
     * (recommendationid, topic_id) 를 (review_id, topic_id) 로 바꾼다.
     * recent_review 에 없는 리뷰(창 밖)와 topic 표에 없는 토픽은 빠진다 — inner join.
     */
    static Dataset<Row> assign(Dataset<Row> pairs, Dataset<Row> recent, Dataset<Row> knownTopics) {
        return pairs.join(recent, "recommendationid")
                .join(knownTopics, pairs.col("topic_id").cast(DataTypes.LongType).equalTo(knownTopics.col("known_topic")))
                .select(col("review_id"), col("topic_id"))
                .distinct();
    }

    // ── DB ─────────────────────────────────────────────────────────

    /** 언어 8개의 (recommendationid, review_id). 언어 이름은 이 클래스의 상수라 SQL 에 그대로 넣는다. */
    static String pairsSql() {
        StringBuilder in = new StringBuilder();
        for (String l : LANGUAGES) {
            in.append(in.length() == 0 ? "'" : ", '").append(l).append('\'');
        }
        return "SELECT recommendationid, review_id FROM recent_review WHERE language_code IN (" + in + ")";
    }

    private static List<Row> readPairs(String url, String user, String password) {
        List<Row> out = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(url, user, password);
             Statement st = conn.createStatement();
             var rs = st.executeQuery(pairsSql())) {
            while (rs.next()) {
                out.add(RowFactory.create(rs.getLong(1), rs.getLong(2)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("recent_review 짝 조회 실패: " + e.getMessage(), e);
        }
        return out;
    }

    /** review_topic 만 비운다 — recent_review 를 갈아엎기 전에. */
    private static void clear(String url, String user, String password) {
        try (Connection conn = DriverManager.getConnection(url, user, password);
             Statement st = conn.createStatement()) {
            st.executeUpdate("TRUNCATE TABLE review_topic");
        } catch (SQLException e) {
            throw new IllegalStateException("review_topic 비우기 실패: " + e.getMessage(), e);
        }
    }
}
