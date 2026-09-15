package com.ssafy.thispatch.collector.writer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ssafy.thispatch.collector.client.SteamReviewPage;
import com.ssafy.thispatch.common.HdfsPaths;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReviewLandingWriterTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private final Map<Path, RecordingStream> files = new ConcurrentHashMap<>();
    private FileSystem fileSystem;
    private ReviewLandingWriter writer;
    private boolean failWrite;
    private boolean failClose;

    @BeforeEach
    void setUp() throws IOException {
        fileSystem = mock(FileSystem.class);
        when(fileSystem.mkdirs(any(Path.class))).thenReturn(true);
        when(fileSystem.create(any(Path.class), eq(false))).thenAnswer(invocation -> {
            Path path = invocation.getArgument(0);
            var stream = new RecordingStream();
            if (files.putIfAbsent(path, stream) != null) {
                throw new org.apache.hadoop.fs.FileAlreadyExistsException(path.toString());
            }
            return new FSDataOutputStream(stream, null);
        });
        when(fileSystem.rename(any(Path.class), any(Path.class))).thenAnswer(invocation -> {
            Path src = invocation.getArgument(0);
            Path dst = invocation.getArgument(1);
            var stream = files.get(src);
            assertNotNull(stream);
            assertTrue(stream.closed, "gzip/HDFS 스트림 종료 후에만 확정해야 한다");
            assertFalse(src.getName().endsWith(".jsonl.gz"));
            assertTrue(src.getName().startsWith("."));
            if (files.putIfAbsent(dst, stream) != null) {
                return false;
            }
            files.remove(src);
            return true;
        });
        when(fileSystem.delete(any(Path.class), eq(false)))
                .thenAnswer(invocation -> files.remove(invocation.getArgument(0)) != null);
        when(fileSystem.exists(any(Path.class)))
                .thenAnswer(invocation -> files.containsKey(invocation.getArgument(0)));
        writer = new ReviewLandingWriter(fileSystem);
    }

    @Test
    void writesUtf8JsonLinesWithMetadataAndPreservesOriginalReviews() throws Exception {
        ObjectNode first = (ObjectNode) mapper.readTree("""
                {"recommendationid":"123","review":"한글\\n본문 🎮","language":"koreana",
                 "timestamp_created":1780000000,"timestamp_updated":1780000010,
                 "voted_up":true,"weighted_vote_score":"0.5",
                 "author":{"steamid":"76561198000000000","future_field":[1,null,"x"]}}
                """);
        ObjectNode second = (ObjectNode) mapper.readTree("""
                {"recommendationid":"124","review":"text","weighted_vote_score":0.123456789012345678901}
                """);
        var page = new SteamReviewPage(List.of(first, second), "opaque-cursor");
        ObjectNode original = first.deepCopy();
        Instant receivedAt = Instant.parse("2026-09-13T15:00:00.999Z");

        Path path = writer.writePage(730, page, receivedAt).orElseThrow();

        assertEquals(new Path(HdfsPaths.reviewLandingOf("2026-09-14")), path.getParent());
        assertTrue(path.getName().startsWith("reviews-730-"));
        assertTrue(path.getName().endsWith(".jsonl.gz"));
        var lines = readLines(path);
        assertEquals(2, lines.size());
        for (int i = 0; i < lines.size(); i++) {
            ObjectNode row = (ObjectNode) mapper.readTree(lines.get(i));
            assertTrue(row.get("appid").isIntegralNumber());
            assertEquals(730L, row.remove("appid").longValue());
            assertEquals(receivedAt.getEpochSecond(), row.remove("collected_ts").longValue());
            assertEquals(page.reviews().get(i), row);
        }
        assertEquals(original, first);
        assertFalse(second.has("appid"));
        assertFalse(second.has("collected_ts"));
        assertEquals(1, files.size());
        verify(fileSystem, never()).close();
    }

    @Test
    void usesReceivedTimeAtTheKstDateBoundary() throws Exception {
        var page = page();
        Path before = writer.writePage(730, page, Instant.parse("2026-09-13T14:59:59Z")).orElseThrow();
        Path after = writer.writePage(730, page, Instant.parse("2026-09-13T15:00:00Z")).orElseThrow();
        assertEquals("dt=2026-09-13", before.getParent().getName());
        assertEquals("dt=2026-09-14", after.getParent().getName());
    }

    @Test
    void leavesNoFileForEmptyPages() throws Exception {
        assertTrue(writer.writePage(730, new SteamReviewPage(List.of(), null), Instant.now()).isEmpty());
        verifyNoInteractions(fileSystem);
    }

    @Test
    void disablesPrettyPrintingToKeepOneReviewPerLine() throws Exception {
        var prettyMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        writer = new ReviewLandingWriter(fileSystem, prettyMapper, new Path(HdfsPaths.REVIEW_LANDING));
        Path path = writer.writePage(730, page(), Instant.now()).orElseThrow();
        assertEquals(1, readLines(path).size());
        assertNotNull(mapper.readTree(readLines(path).get(0)).get("recommendationid"));
    }

    @Test
    void concurrentWorkersAndRepeatedPagesProduceDistinctFiles() throws Exception {
        var page = page();
        var receivedAt = Instant.parse("2026-09-14T00:00:00Z");
        var pool = Executors.newFixedThreadPool(4);
        try {
            var tasks = new ArrayList<java.util.concurrent.Callable<Path>>();
            for (int i = 0; i < 12; i++) {
                tasks.add(() -> new ReviewLandingWriter(fileSystem).writePage(730, page, receivedAt).orElseThrow());
            }
            var paths = new java.util.HashSet<Path>();
            for (var result : pool.invokeAll(tasks)) {
                assertTrue(paths.add(result.get()));
            }
            assertEquals(12, files.size());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void failsBeforeCreatingAFileWhenDirectoryCreationFails() throws Exception {
        when(fileSystem.mkdirs(any(Path.class))).thenReturn(false);
        assertThrows(IOException.class, () -> writer.writePage(730, page(), Instant.now()));
        verify(fileSystem, never()).create(any(Path.class), eq(false));
    }

    @Test
    void doesNotDeleteAFileWhenCreateFails() throws Exception {
        doThrow(new org.apache.hadoop.fs.FileAlreadyExistsException("existing file"))
                .when(fileSystem).create(any(Path.class), eq(false));
        assertThrows(IOException.class, () -> writer.writePage(730, page(), Instant.now()));
        verify(fileSystem, never()).delete(any(Path.class), eq(false));
        verify(fileSystem, never()).rename(any(Path.class), any(Path.class));
    }

    @Test
    void writeFailureRemovesOnlyItsTemporaryFile() throws Exception {
        failWrite = true;
        assertThrows(IOException.class, () -> writer.writePage(730, page(), Instant.now()));
        assertTrue(files.isEmpty());
        verify(fileSystem, never()).rename(any(Path.class), any(Path.class));
        verify(fileSystem).delete(any(Path.class), eq(false));
    }

    @Test
    void closeFailureDoesNotPublishIncompleteGzip() throws Exception {
        failClose = true;
        assertThrows(IOException.class, () -> writer.writePage(730, page(), Instant.now()));
        assertTrue(files.isEmpty());
        verify(fileSystem, never()).rename(any(Path.class), any(Path.class));
    }

    @Test
    void renameFailureIsReportedAndTemporaryFileIsRemoved() throws Exception {
        doReturn(false).when(fileSystem).rename(any(Path.class), any(Path.class));
        assertThrows(IOException.class, () -> writer.writePage(730, page(), Instant.now()));
        assertTrue(files.isEmpty());
        verify(fileSystem).delete(any(Path.class), eq(false));
    }

    @Test
    void cleanupFailureDoesNotHideTheOriginalFailure() throws Exception {
        var renameFailure = new IOException("rename failed");
        var cleanupFailure = new IOException("delete failed");
        doThrow(renameFailure).when(fileSystem).rename(any(Path.class), any(Path.class));
        doThrow(cleanupFailure).when(fileSystem).delete(any(Path.class), eq(false));
        var error = assertThrows(IOException.class, () -> writer.writePage(730, page(), Instant.now()));
        assertSame(renameFailure, error);
        assertArrayEquals(new Throwable[] {cleanupFailure}, error.getSuppressed());
        assertTrue(files.keySet().stream().allMatch(p -> p.getName().endsWith(".inprogress")));
    }

    @Test
    void rejectsInvalidInputBeforeWriting() {
        assertThrows(IllegalArgumentException.class, () -> writer.writePage(0, page(), Instant.now()));
        assertThrows(NullPointerException.class, () -> writer.writePage(730, null, Instant.now()));
        assertThrows(NullPointerException.class, () -> writer.writePage(730, page(), null));
        verifyNoInteractions(fileSystem);
    }

    private SteamReviewPage page() {
        return new SteamReviewPage(List.of(mapper.createObjectNode()
                .put("recommendationid", "123").put("review", "sample")), "next");
    }

    private List<String> readLines(Path path) throws IOException {
        var bytes = new ByteArrayInputStream(files.get(path).bytes.toByteArray());
        try (var reader = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(bytes), StandardCharsets.UTF_8))) {
            return reader.lines().toList();
        }
    }

    private final class RecordingStream extends OutputStream {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private boolean closed;

        @Override
        public void write(int b) throws IOException {
            if (failWrite) {
                throw new IOException("write failed");
            }
            bytes.write(b);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            if (failClose) {
                throw new IOException("close failed");
            }
        }
    }
}
