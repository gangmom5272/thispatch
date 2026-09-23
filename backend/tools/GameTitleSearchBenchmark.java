package com.ssafy.thispatch.domain.game.repository;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import com.ssafy.thispatch.domain.game.dto.request.GameListQuery;
import com.ssafy.thispatch.domain.game.dto.request.GameListScope;
import com.ssafy.thispatch.domain.game.dto.request.GameListSort;

/** Explicitly invoked diagnostic. Uses connection-local temporary tables only. */
public class GameTitleSearchBenchmark {
    private static final int ROWS = integer("GAME_BENCH_ROWS", 100_000);
    private static final int RUNS = integer("GAME_BENCH_RUNS", 20);
    private static final int CLIENTS = integer("GAME_BENCH_CLIENTS", 4);
    private static final int CONCURRENT_RUNS = integer("GAME_BENCH_CONCURRENT_RUNS", 10);
    private static final Path OUTPUT = Path.of("build/game-title-search-benchmark");
    private static final Properties CONFIG = new Properties();

    public static void main(String[] args) throws Exception {
        try (var reader = Files.newBufferedReader(Path.of(".env"), StandardCharsets.UTF_8)) {
            CONFIG.load(reader);
        }
        Files.createDirectories(OUTPUT);
        List<String> result = new ArrayList<>();
        result.add("mode,scope,case,query,clients,runs,mean_ms,p95_ms,requests_per_second,backend_cpu_ms");
        try (Connection connection = connect()) {
            var jdbc = prepare(connection);
            System.out.println("PostgreSQL " + connection.getMetaData().getDatabaseProductVersion()
                + "; Java " + System.getProperty("java.version") + "; rows=" + ROWS);
            for (GameListScope scope : GameListScope.values()) {
                for (String term : List.of("game", "rare", "absentzz", "")) {
                    // Alternate first variant by case to reduce fixed-order cache/temperature bias.
                    var modes = term.equals("rare") || term.isEmpty() ? List.of(true, false) : List.of(false, true);
                    for (boolean normalized : modes) {
                        var queries = queries(jdbc, scope, term, normalized);
                        for (int i = 0; i < queries.size(); i++) {
                            var query = queries.get(i);
                            for (int warmup = 0; warmup < 5; warmup++) execute(jdbc, query);
                            double cpu = cpuMillis(jdbc);
                            List<Double> durations = new ArrayList<>();
                            for (int run = 0; run < RUNS; run++) durations.add(timed(jdbc, query));
                            cpu = cpuMillis(jdbc) - cpu;
                            String line = line(normalized, scope, term, i == 0 ? "count" : "page",
                                1, durations, 0, cpu);
                            result.add(line);
                            System.out.println(line);
                            String plan = jdbc.queryForObject("explain (analyze, buffers, format json) " + query.sql,
                                query.parameters, String.class);
                            Files.writeString(OUTPUT.resolve(label(normalized, scope, term) + "-" + i + ".json"), plan);
                        }
                    }
                }
            }
        }
        // Each connection has an identical independent temporary fixture, shared server CPU/cache.
        // Timed request = search normalization + count + page; excludes HTTP/auth/DTO/tag loading.
        for (boolean normalized : List.of(false, true)) {
            for (GameListScope scope : GameListScope.values()) {
                var barrier = new CyclicBarrier(CLIENTS);
                var pool = Executors.newFixedThreadPool(CLIENTS);
                try {
                    List<Callable<Batch>> tasks = new ArrayList<>();
                    for (int client = 0; client < CLIENTS; client++) {
                        tasks.add(() -> {
                            try (Connection connection = connect()) {
                                var jdbc = prepare(connection);
                                var queries = queries(jdbc, scope, "game", normalized);
                                var raw = GameListQuery.of("game", GameListSort.POSITIVE_RATE_ASC, 10, null);
                                var repository = new GameListRepository(jdbc);
                                for (int warmup = 0; warmup < 5; warmup++) {
                                    if (normalized) repository.normalizeSearch(raw);
                                    for (var query : queries) execute(jdbc, query);
                                }
                                barrier.await(120, java.util.concurrent.TimeUnit.SECONDS);
                                double cpu = cpuMillis(jdbc);
                                long start = System.nanoTime();
                                List<Double> durations = new ArrayList<>();
                                for (int run = 0; run < CONCURRENT_RUNS; run++) {
                                    long requestStart = System.nanoTime();
                                    if (normalized) repository.normalizeSearch(raw);
                                    for (var query : queries) execute(jdbc, query);
                                    durations.add((System.nanoTime() - requestStart) / 1_000_000d);
                                }
                                return new Batch(durations, (System.nanoTime() - start) / 1_000_000d,
                                    cpuMillis(jdbc) - cpu);
                            }
                        });
                    }
                    List<Double> durations = new ArrayList<>();
                    double wall = 0, cpu = 0;
                    for (var future : pool.invokeAll(tasks)) {
                        var batch = future.get();
                        durations.addAll(batch.durations);
                        wall = Math.max(wall, batch.wallMillis);
                        cpu += batch.cpuMillis;
                    }
                    String line = line(normalized, scope, "game", "normalize+count+page",
                        CLIENTS, durations, wall, cpu);
                    result.add(line);
                    System.out.println(line);
                } finally {
                    pool.shutdownNow();
                }
            }
        }
        Files.write(OUTPUT.resolve("results.csv"), result, StandardCharsets.UTF_8);
    }

    private static Connection connect() throws Exception {
        String url = "jdbc:postgresql://" + setting("POSTGRES_HOST", "localhost") + ":"
            + setting("POSTGRES_PORT", "5432") + "/thispatch_test";
        Connection connection = DriverManager.getConnection(url,
            setting("POSTGRES_USER", ""), setting("POSTGRES_PASSWORD", ""));
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("select current_database()")) {
            rows.next();
            if (!"thispatch_test".equals(rows.getString(1))) {
                connection.close();
                throw new IllegalStateException("Only thispatch_test is allowed");
            }
        }
        return connection;
    }

    private static NamedParameterJdbcTemplate prepare(Connection connection) {
        var jdbc = new NamedParameterJdbcTemplate(new SingleConnectionDataSource(connection, true));
        var sql = jdbc.getJdbcTemplate();
        sql.execute("set statement_timeout = '60s'");
        for (String table : List.of("game", "my_game", "news", "patch_stat", "game_tag")) {
            sql.execute("create temporary table " + table + " (like public." + table + " including all)");
        }
        sql.execute("set search_path = pg_temp");
        sql.update("""
            insert into game (appid, name, store_positive_pct, store_review_count, release_ts, collected_at)
            select n, case when n % 1000 = 0 then 'Rare ' else '' end
                || 'Benchmark Game-' || n || ' 한글 Pokémon',
                n % 101, n, timestamptz '2026-01-01 00:00:00+09' + n * interval '1 minute', now()
            from generate_series(1, ?) n
            """, ROWS);
        sql.update("""
            insert into my_game (member_id, appid, created_at)
            select 1, n, now() from generate_series(1, ?) n where n % 100 = 0
            """, ROWS);
        sql.update("""
            insert into news (gid, appid, title, contents, published_ts, is_patch, collected_at)
            select n::text, n, 'Patch ' || n, '', now(), true, now() from generate_series(1, ?) n
            """, ROWS);
        for (String table : List.of("game", "my_game", "news", "patch_stat", "game_tag")) {
            sql.execute("analyze " + table);
        }
        return jdbc;
    }

    private static List<Query> queries(NamedParameterJdbcTemplate jdbc, GameListScope scope,
                                       String search, boolean normalized) {
        var recording = new RecordingJdbc(jdbc);
        var repository = new GameListRepository(recording);
        var raw = GameListQuery.of(search, GameListSort.POSITIVE_RATE_ASC, 10, null);
        var query = normalized ? new GameListRepository(jdbc).normalizeSearch(raw)
            : GameListQuery.of(search.strip().toLowerCase(Locale.ROOT), raw.sort(), raw.limit(), null);
        repository.count(1, query, scope);
        repository.findPage(1, query, scope, null);
        return recording.queries.stream().map(q -> normalized ? q
            : new Query(q.sql.replace(GameTitleSearch.expression("g.name"), "lower(g.name)"), q.parameters)).toList();
    }

    private static void execute(NamedParameterJdbcTemplate jdbc, Query query) {
        jdbc.query(query.sql, query.parameters, rows -> { while (rows.next()) { /* consume */ } return null; });
    }

    private static double timed(NamedParameterJdbcTemplate jdbc, Query query) {
        long start = System.nanoTime();
        execute(jdbc, query);
        return (System.nanoTime() - start) / 1_000_000d;
    }

    private static double cpuMillis(NamedParameterJdbcTemplate jdbc) {
        // Docker Linux backend process: /proc/self/stat user+system ticks; host CLK_TCK=100.
        String stat = jdbc.getJdbcTemplate().queryForObject("select pg_read_file('/proc/self/stat')", String.class);
        String[] fields = stat.substring(stat.lastIndexOf(')') + 2).split(" ");
        return (Long.parseLong(fields[11]) + Long.parseLong(fields[12])) * 10d;
    }

    private static String line(boolean normalized, GameListScope scope, String term, String query,
                               int clients, List<Double> values, double wall, double cpu) {
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        double p95 = sorted.get((int) Math.ceil(sorted.size() * .95) - 1);
        return String.format(Locale.ROOT, "%s,%s,%s,%s,%d,%d,%.3f,%.3f,%.3f,%.0f",
            normalized ? "normalized" : "baseline", scope, term.isEmpty() ? "empty" : term,
            query, clients, values.size(), mean, p95, wall == 0 ? 1000 / mean : values.size() * 1000 / wall, cpu);
    }

    private static String label(boolean normalized, GameListScope scope, String term) {
        return (normalized ? "normalized" : "baseline") + "-" + scope + "-" + (term.isEmpty() ? "empty" : term);
    }

    private static int integer(String name, int fallback) {
        String value = System.getenv(name);
        int result = value == null ? fallback : Integer.parseInt(value);
        if (result < 1) throw new IllegalArgumentException(name + " must be positive");
        return result;
    }

    private static String setting(String name, String fallback) {
        return System.getenv().getOrDefault(name, CONFIG.getProperty(name, fallback));
    }

    private record Query(String sql, SqlParameterSource parameters) {}
    private record Batch(List<Double> durations, double wallMillis, double cpuMillis) {}

    private static class RecordingJdbc extends NamedParameterJdbcTemplate {
        final List<Query> queries = new ArrayList<>();
        RecordingJdbc(NamedParameterJdbcTemplate jdbc) { super(jdbc.getJdbcTemplate()); }

        @Override public <T> T queryForObject(String sql, SqlParameterSource params, Class<T> type) {
            queries.add(new Query(sql, new MapSqlParameterSource(copy(params))));
            return type.cast(0L);
        }

        @Override public <T> List<T> query(String sql, SqlParameterSource params, RowMapper<T> mapper) {
            queries.add(new Query(sql, new MapSqlParameterSource(copy(params))));
            return List.of();
        }

        private Map<String, Object> copy(SqlParameterSource params) {
            var result = new java.util.HashMap<String, Object>();
            for (String key : params.getParameterNames()) result.put(key, params.getValue(key));
            return result;
        }
    }
}
