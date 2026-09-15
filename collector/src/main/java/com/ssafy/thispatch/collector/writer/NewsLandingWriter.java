package com.ssafy.thispatch.collector.writer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ssafy.thispatch.common.HdfsPaths;
import com.ssafy.thispatch.common.TimeRule;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

/**
 * 게임 하나의 공지를 HDFS 의 날짜별 landing 에 JSONL.gz 로 쓴다.
 *
 * <p>{@link ReviewLandingWriter} 와 같은 규칙을 따른다 — 임시 이름으로 쓰고 다 되면
 * rename 한다. 중간에 죽으면 숨김 임시 파일만 남고, Spark 의 {@code *.jsonl.gz}
 * 입력에는 걸리지 않는다.
 *
 * <p><b>리뷰와 다른 점.</b> 공지는 게임당 한 번에 다 오므로 파일도 게임당 하나다.
 * 리뷰는 페이지마다 파일이 생겨 25만 개가 됐지만, 공지는 11.7만 개로 끝난다.
 *
 * <p>{@code appid} 와 {@code collected_ts} 를 각 줄에 넣는다. 스팀 응답에는
 * {@code appid} 가 바깥({@code appnews.appid})에만 있어서, 그대로 두면 Parquet 으로
 * 바꿀 때 어느 게임의 공지인지 알 수 없다.
 */
public final class NewsLandingWriter {

    private final FileSystem fileSystem;
    private final ObjectWriter jsonWriter;
    private final Path landingRoot;

    public NewsLandingWriter(FileSystem fileSystem) {
        this(fileSystem, new ObjectMapper(), new Path(HdfsPaths.NEWS_LANDING));
    }

    /** 테스트에서는 별도 저장 경로를 사용한다. */
    NewsLandingWriter(FileSystem fileSystem, ObjectMapper objectMapper, Path landingRoot) {
        this.fileSystem = Objects.requireNonNull(fileSystem);
        this.jsonWriter = Objects.requireNonNull(objectMapper).writer()
                .without(SerializationFeature.INDENT_OUTPUT, SerializationFeature.WRAP_ROOT_VALUE);
        this.landingRoot = Objects.requireNonNull(landingRoot);
    }

    /**
     * 공지가 없으면 파일을 만들지 않는다. 입력 객체는 고치지 않는다.
     *
     * @return 만들어진 파일. 공지가 없으면 빈 값
     */
    public Optional<Path> write(long appid, List<ObjectNode> items, Instant collectedAt)
            throws IOException {
        if (appid <= 0) {
            throw new IllegalArgumentException("appid must be positive");
        }
        Objects.requireNonNull(items);
        Objects.requireNonNull(collectedAt);
        if (items.isEmpty()) {
            return Optional.empty();
        }

        long collectedTs = collectedAt.getEpochSecond();
        Path directory = new Path(landingRoot, "dt=" + TimeRule.partition(collectedTs));
        if (!fileSystem.mkdirs(directory)) {
            throw new IOException("Could not create news landing directory: " + directory);
        }

        String filename = "news-" + appid + "-" + UUID.randomUUID() + ".jsonl.gz";
        Path completed = new Path(directory, filename);
        Path temporary = new Path(directory, "." + filename + ".inprogress");
        boolean created = false;
        try {
            // overwrite=false: 기존 파일을 덮어쓰지 않는다.
            var output = fileSystem.create(temporary, false);
            created = true;
            try (output;
                 var gzip = new GZIPOutputStream(output);
                 var writer = new BufferedWriter(new OutputStreamWriter(gzip, StandardCharsets.UTF_8))) {
                for (ObjectNode item : items) {
                    ObjectNode row = item.deepCopy();
                    row.put("appid", appid);
                    row.put("collected_ts", collectedTs);
                    writer.write(jsonWriter.writeValueAsString(row));
                    writer.write('\n');
                }
            }
            if (!fileSystem.rename(temporary, completed)) {
                throw new IOException("Could not publish news landing file: " + completed);
            }
            return Optional.of(completed);
        } catch (IOException | RuntimeException failure) {
            if (created) {
                cleanup(temporary, failure);
            }
            throw failure;
        }
    }

    private void cleanup(Path temporary, Exception failure) {
        try {
            if (!fileSystem.delete(temporary, false) && fileSystem.exists(temporary)) {
                failure.addSuppressed(new IOException("Could not remove temporary news file: " + temporary));
            }
        } catch (IOException | RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }
}
