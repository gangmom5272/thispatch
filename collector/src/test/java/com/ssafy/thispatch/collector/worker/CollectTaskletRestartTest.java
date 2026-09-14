package com.ssafy.thispatch.collector.worker;

import static com.ssafy.thispatch.collector.worker.CollectTaskletTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ssafy.thispatch.collector.client.SteamReviewClient;
import com.ssafy.thispatch.collector.writer.ReviewLandingWriter;
import com.ssafy.thispatch.collector.partition.AppidPartitioner;
import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.hadoop.fs.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ChunkListener;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.explore.support.JobExplorerFactoryBean;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.support.JobRepositoryFactoryBean;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 실제 Spring Batch 저장소와 트랜잭션을 사용하고 HTTP/HDFS만 대역으로 교체한다. */
class CollectTaskletRestartTest {
    private final SteamReviewClient client = mock(SteamReviewClient.class);
    private final ReviewLandingWriter writer = mock(ReviewLandingWriter.class);
    private final MutableClock clock = new MutableClock();
    private DriverManagerDataSource dataSource;
    private DataSourceTransactionManager transactionManager;
    private JobRepository repository;
    private JobRepository repositoryDelegate;
    private JobExplorer explorer;
    private TaskExecutorJobLauncher launcher;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("org/springframework/batch/core/schema-h2.sql"))
                .execute(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
        var repositoryFactory = new JobRepositoryFactoryBean();
        repositoryFactory.setDataSource(dataSource);
        repositoryFactory.setTransactionManager(transactionManager);
        repositoryFactory.afterPropertiesSet();
        repositoryDelegate = repositoryFactory.getObject();
        repository = mock(JobRepository.class, org.mockito.AdditionalAnswers.delegatesTo(repositoryDelegate));
        var explorerFactory = new JobExplorerFactoryBean();
        explorerFactory.setDataSource(dataSource);
        explorerFactory.setTransactionManager(transactionManager);
        explorerFactory.afterPropertiesSet();
        explorer = explorerFactory.getObject();
        launcher = new TaskExecutorJobLauncher();
        launcher.setJobRepository(repository);
        launcher.afterPropertiesSet();
        when(writer.writePage(anyLong(), any(), any())).thenReturn(Optional.of(new Path("/saved.jsonl.gz")));
    }

    @AfterEach
    void cleanUp() {
        Thread.interrupted();
        if (dataSource != null) {
            new JdbcTemplate(dataSource).execute("SHUTDOWN");
        }
    }

    @Test
    void restartingTheSameJobResumesTheFailedPageAndDoesNotRecollectCompletedGames() throws Exception {
        var firstPage = page("c1");
        var secondPage = page("c2");
        when(client.fetchPage(730, "*")).thenReturn(empty(null));
        when(client.fetchPage(570, "*")).thenReturn(firstPage);
        when(client.fetchPage(570, "c1")).thenReturn(secondPage);
        when(client.fetchPage(570, "c2")).thenReturn(empty(null));
        when(writer.writePage(eq(570L), same(secondPage), any()))
                .thenThrow(new IOException("HDFS unavailable"))
                .thenReturn(Optional.of(new Path("/second.jsonl.gz")));

        var first = launcher.run(job(this::advanceOutsideTransaction), new JobParameters());
        assertEquals(BatchStatus.FAILED, first.getStatus());
        var firstStep = first.getStepExecutions().iterator().next();
        var saved = explorer.getStepExecution(first.getId(), firstStep.getId()).getExecutionContext();
        assertEquals(1, saved.getInt(CollectTasklet.KEY_APP_INDEX));
        assertEquals("c1", saved.getString(CollectTasklet.KEY_CURSOR));
        assertEquals(1, firstStep.getWriteCount());

        // 새 Tasklet을 생성한다. 진행 위치는 메모리가 아니라 JobRepository에서 복원된다.
        var restarted = launcher.run(job(this::advanceOutsideTransaction), new JobParameters());
        assertEquals(first.getJobInstance().getId(), restarted.getJobInstance().getId());
        assertNotEquals(first.getId(), restarted.getId());
        assertEquals(BatchStatus.COMPLETED, restarted.getStatus());
        verify(client, times(4)).fetchPage(730, "*");
        verify(client, times(1)).fetchPage(570, "*");
        verify(client, times(2)).fetchPage(570, "c1");
        verify(client, times(4)).fetchPage(570, "c2");
    }

    @Test
    void cooldownIsCommittedBeforeWaitingAndSurvivesAStoppedJobRestart() throws Exception {
        when(client.fetchPage(730, "*")).thenThrow(httpError(403, null)).thenAnswer(call -> {
            assertTrue(clock.millis() >= InstantHolder.START + 3_600_000);
            return empty(null);
        });
        when(client.fetchPage(570, "*")).thenReturn(empty(null));
        var first = launcher.run(job(millis -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            var stored = new JdbcTemplate(dataSource).queryForObject(
                    "SELECT COUNT(*) FROM BATCH_STEP_EXECUTION_CONTEXT", Integer.class);
            assertEquals(1, stored);
            throw new InterruptedException("stop during cooldown");
        }), new JobParameters());
        Thread.interrupted();
        assertEquals(BatchStatus.STOPPED, first.getStatus());
        var firstStep = first.getStepExecutions().iterator().next();
        var saved = explorer.getStepExecution(first.getId(), firstStep.getId()).getExecutionContext();
        assertEquals(InstantHolder.START + 3_600_000, saved.getLong(CollectTasklet.KEY_NEXT_REQUEST_AT));
        assertFalse(saved.containsKey(CollectTasklet.KEY_CURSOR));

        var restarted = launcher.run(job(this::advanceOutsideTransaction), new JobParameters());
        assertEquals(BatchStatus.COMPLETED, restarted.getStatus());
        verify(client, times(5)).fetchPage(730, "*");
    }

    @Test
    void repositoryFailureAfterHdfsWriteReplaysThePageInsteadOfSkippingIt() throws Exception {
        when(client.fetchPage(730, "*")).thenReturn(page("c1"));
        when(client.fetchPage(730, "c1")).thenReturn(empty(null));
        when(client.fetchPage(570, "*")).thenReturn(empty(null));
        var failOnce = new AtomicBoolean(true);
        doAnswer(call -> {
            StepExecution execution = call.getArgument(0);
            if (execution.getExecutionContext().containsKey(CollectTasklet.KEY_CURSOR)
                    && failOnce.getAndSet(false)) {
                throw new DataAccessResourceFailureException("checkpoint storage unavailable");
            }
            repositoryDelegate.updateExecutionContext(execution);
            return null;
        }).when(repository).updateExecutionContext(any(StepExecution.class));

        var first = launcher.run(job(this::advanceOutsideTransaction), new JobParameters());
        assertEquals(BatchStatus.FAILED, first.getStatus());
        var restarted = launcher.run(job(this::advanceOutsideTransaction), new JobParameters());
        assertEquals(BatchStatus.COMPLETED, restarted.getStatus());
        verify(client, times(2)).fetchPage(730, "*");
        verify(writer, times(2)).writePage(eq(730L), any(), any());
    }

    private Job job(CollectTasklet.Sleeper sleeper) {
        var tasklet = new CollectTasklet(client, writer, explorer, Duration.ofMillis(1), 2, clock, sleeper);
        var step = new StepBuilder("collect.worker", repository)
                .tasklet(tasklet, transactionManager)
                .listener((ChunkListener) tasklet)
                .listener(new StepExecutionListener() {
                    @Override
                    public void beforeStep(StepExecution execution) {
                        // 실제 원격 실행에서는 마스터 partitioner가 이 값을 넣는다.
                        if (!execution.getExecutionContext().containsKey(AppidPartitioner.KEY_APPIDS)) {
                            execution.getExecutionContext().putString(AppidPartitioner.KEY_APPIDS, "730,570");
                        }
                    }
                })
                .build();
        return new JobBuilder("review-restart-test", repository).start(step).build();
    }

    private void advanceOutsideTransaction(long millis) {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        clock.advance(millis);
    }

    private static class InstantHolder {
        static final long START = java.time.Instant.parse("2026-09-14T00:00:00Z").toEpochMilli();
    }
}
