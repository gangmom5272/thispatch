package com.ssafy.thispatch.spark;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;

/**
 * 서비스 DB 적재기들이 같이 쓰는 것 — 드라이버에서만 연결을 연다.
 *
 * <p>왜 드라이버만인지는 {@link ReviewStatsToPostgres} 클래스 주석에 있다 (executor 는 마스터의
 * SSH 터널 127.0.0.1 에 못 닿는다 · 2026-09-17 실측). 참조 목록은 여기서 읽어 작은 DataFrame 으로
 * 만들고, 결과는 {@code toLocalIterator()} 로 파티션 하나씩 받아 연결 하나 · 트랜잭션 하나로 넣는다.
 *
 * <p>{@link ReviewStatsToPostgres} · {@link RecentReviewToPostgres} · {@link NewsToPostgres} 는 이 헬퍼가
 * 생기기 전에 같은 코드를 각자 들고 있다. 동작이 같으므로 급하게 옮기지 않는다 — 파이프라인 연결
 * (S15P21A202-26) 때 한 번에 정리한다.
 */
final class LoaderSupport {

    private LoaderSupport() {
    }

    /** 행 하나를 PreparedStatement 에 채운다. */
    @FunctionalInterface
    interface RowBinder {
        void bind(PreparedStatement ps, Row row) throws SQLException;
    }

    /**
     * {@code tables} 를 한꺼번에 비우고 첫 표에 {@code rows} 를 전부 넣는다. 연결 하나, 트랜잭션 하나.
     *
     * <p>FK 로 참조되는 표는 단독 TRUNCATE 가 거부되므로 참조하는 표를 같이 지정한다 (호출 전에
     * 그 표가 비어 있음을 확인하는 것은 호출자 책임). 넣는 도중 무엇이든 실패하면 롤백되어
     * TRUNCATE 까지 되돌아간다 — 표는 시작 전 그대로다.
     */
    static long replaceTable(String[] tables, Dataset<Row> rows, String url, String user, String password,
                             String insertSql, int batch, RowBinder binder) {
        long n = 0;
        try (Connection conn = DriverManager.getConnection(url, user, password)) {
            conn.setAutoCommit(false);
            try {
                try (Statement st = conn.createStatement()) {
                    // 표 이름은 호출하는 클래스 안의 상수다. 바깥 입력이 아니다.
                    st.executeUpdate("TRUNCATE TABLE " + String.join(", ", tables));
                }
                try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                    int inBatch = 0;
                    Iterator<Row> it = rows.toLocalIterator();
                    while (it.hasNext()) {
                        binder.bind(ps, it.next());
                        ps.addBatch();
                        if (++inBatch >= batch) {
                            n += ps.executeBatch().length;
                            inBatch = 0;
                            if (n % 200_000 == 0) {
                                System.out.println("  … " + n + "건");
                            }
                        }
                    }
                    if (inBatch > 0) {
                        n += ps.executeBatch().length;
                    }
                }
                conn.commit();
                System.out.println("비우고 넣었다  " + tables[0] + " (한 트랜잭션)");
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(tables[0] + " 적재 실패 (롤백됨): " + e.getMessage(), e);
        }
        return n;
    }

    static long count(String url, String user, String password, String table) {
        try (Connection conn = DriverManager.getConnection(url, user, password);
             Statement st = conn.createStatement();
             var rs = st.executeQuery("SELECT count(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException(table + " count 실패: " + e.getMessage(), e);
        }
    }

    static List<Long> readLongs(String url, String user, String password, String sql) {
        try (Connection conn = DriverManager.getConnection(url, user, password);
             Statement st = conn.createStatement();
             var rs = st.executeQuery(sql)) {
            Set<Long> out = new HashSet<>();
            while (rs.next()) {
                out.add(rs.getLong(1));
            }
            return new ArrayList<>(out);
        } catch (SQLException e) {
            throw new IllegalStateException("조회 실패 (" + sql + "): " + e.getMessage(), e);
        }
    }

    static List<String> readStrings(String url, String user, String password, String sql) {
        try (Connection conn = DriverManager.getConnection(url, user, password);
             Statement st = conn.createStatement();
             var rs = st.executeQuery(sql)) {
            List<String> out = new ArrayList<>();
            while (rs.next()) {
                out.add(rs.getString(1));
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("조회 실패 (" + sql + "): " + e.getMessage(), e);
        }
    }

    /** 있는 경로만 돌려준다. delta 는 첫 증분 전에는 없다. */
    static List<String> existing(SparkSession spark, String[] candidates) {
        List<String> out = new ArrayList<>();
        try {
            FileSystem fs = FileSystem.get(spark.sparkContext().hadoopConfiguration());
            for (String c : candidates) {
                if (fs.exists(new Path(c))) {
                    out.add(c);
                }
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("HDFS 를 못 읽는다: " + e.getMessage(), e);
        }
        return out;
    }

    static String env(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("환경변수 " + name + " 이 없다");
        }
        return v;
    }

    /** 집계가 준 Long/Integer 를 INTEGER 칼럼에 맞춘다. null 은 0 — 세는 값이라 없는 것은 0 이다. */
    static int toInt(Object v) {
        if (v == null) {
            return 0;
        }
        long l = ((Number) v).longValue();
        if (l > Integer.MAX_VALUE) {
            throw new IllegalStateException("INTEGER 범위를 넘는다: " + l);
        }
        return (int) l;
    }
}
