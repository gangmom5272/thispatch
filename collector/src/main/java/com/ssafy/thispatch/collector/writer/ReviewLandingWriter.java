package com.ssafy.thispatch.collector.writer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ssafy.thispatch.collector.client.SteamReviewPage;
import com.ssafy.thispatch.common.HdfsPaths;
import com.ssafy.thispatch.common.TimeRule;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

/**
 * 리뷰 페이지를 HDFS의 날짜별 landing에 JSONL.gz로 쓴다.
 * FileSystem의 생성·설정·종료는 호출자가 담당하며, 로컬 파일을 경유하지 않는다.
 *
 * <p>각 호출은 별도 파일을 만든다. 재시도 중복 제거와 수집 체크포인트는 호출자의 책임이다.
 * JVM 강제 종료로 남은 숨김 임시 파일은 Spark의 {@code *.jsonl.gz} 입력에 포함되지 않는다.
 */
public final class ReviewLandingWriter {

    private final FileSystem fileSystem;
    private final ObjectWriter jsonWriter;
    private final Path landingRoot;

    public ReviewLandingWriter(FileSystem fileSystem) {
        this(fileSystem, new ObjectMapper(), new Path(HdfsPaths.REVIEW_LANDING));
    }

    /** 테스트에서는 별도 저장 경로를 사용한다. */
    ReviewLandingWriter(FileSystem fileSystem, ObjectMapper objectMapper, Path landingRoot) {
        this.fileSystem = Objects.requireNonNull(fileSystem);
        this.jsonWriter = Objects.requireNonNull(objectMapper).writer()
                .without(SerializationFeature.INDENT_OUTPUT, SerializationFeature.WRAP_ROOT_VALUE);
        this.landingRoot = Objects.requireNonNull(landingRoot);
    }

    /**
     * 수신 시각으로 KST 폴더와 collected_ts(unix 초)를 함께 정한다.
     * 반환된 경로는 gzip 스트림 종료와 HDFS rename이 모두 성공한 파일이다.
     * 빈 페이지에는 파일을 만들지 않는다. 입력 리뷰 객체는 수정하지 않는다.
     */
    public Optional<Path> writePage(long appid, SteamReviewPage page, Instant collectedAt) throws IOException {
        if (appid <= 0) {
            throw new IllegalArgumentException("appid must be positive");
        }
        Objects.requireNonNull(page);
        Objects.requireNonNull(collectedAt);
        if (page.reviews().isEmpty()) {
            return Optional.empty();
        }

        long collectedTs = collectedAt.getEpochSecond();
        Path directory = new Path(landingRoot, "dt=" + TimeRule.partition(collectedTs));
        if (!fileSystem.mkdirs(directory)) {
            throw new IOException("Could not create review landing directory: " + directory);
        }

        String filename = "reviews-" + appid + "-" + UUID.randomUUID() + ".jsonl.gz";
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
                for (ObjectNode review : page.reviews()) {
                    ObjectNode row = review.deepCopy();
                    row.put("appid", appid);
                    row.put("collected_ts", collectedTs);
                    writer.write(jsonWriter.writeValueAsString(row));
                    writer.write('\n');
                }
            }
            if (!fileSystem.rename(temporary, completed)) {
                throw new IOException("Could not publish review landing file: " + completed);
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
                failure.addSuppressed(new IOException("Could not remove temporary review file: " + temporary));
            }
        } catch (IOException | RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }
}
