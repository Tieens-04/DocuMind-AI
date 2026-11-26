package com.documind.infrastructure.config;

import com.documind.service.ingestion.IngestionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.task.SimpleAsyncTaskExecutorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.VirtualThreadTaskExecutor;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.AsyncAnnotationBeanPostProcessor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Configures Java 21 Virtual Thread execution, periodic recovery scheduling, and Spring Retry AOP support
 * for asynchronous document ingestion while preserving Spring Boot's primary {@code applicationTaskExecutor}
 * for MVC/SSE and general async tasks.
 */
@Configuration
@EnableAsync
@EnableRetry
@EnableScheduling
public class IngestionAsyncConfig {

    private static final Logger log = LoggerFactory.getLogger(IngestionAsyncConfig.class);

    public static final String INGESTION_TASK_EXECUTOR = "documentIngestionExecutor";
    public static final String VIRTUAL_THREAD_PREFIX = "documind-ingest-vt-";
    public static final String CHAT_SSE_TASK_EXECUTOR = "chatSseExecutor";
    public static final String SSE_VIRTUAL_THREAD_PREFIX = "documind-sse-vt-";
    public static final String AUDIT_LOG_TASK_EXECUTOR = "auditLogExecutor";
    public static final String AUDIT_VIRTUAL_THREAD_PREFIX = "documind-audit-vt-";

    @Bean(name = {
        TaskExecutionAutoConfiguration.APPLICATION_TASK_EXECUTOR_BEAN_NAME,
        AsyncAnnotationBeanPostProcessor.DEFAULT_TASK_EXECUTOR_BEAN_NAME
    })
    @Primary
    @ConditionalOnMissingBean(name = TaskExecutionAutoConfiguration.APPLICATION_TASK_EXECUTOR_BEAN_NAME)
    public SimpleAsyncTaskExecutor applicationTaskExecutor(
        ObjectProvider<SimpleAsyncTaskExecutorBuilder> builderProvider
    ) {
        SimpleAsyncTaskExecutorBuilder builder = builderProvider.getIfAvailable(
            () -> new SimpleAsyncTaskExecutorBuilder().virtualThreads(true)
        );
        return builder.build();
    }

    @Bean(name = INGESTION_TASK_EXECUTOR)
    public AsyncTaskExecutor documentIngestionExecutor(ObjectProvider<IngestionProperties> propertiesProvider) {
        IngestionProperties properties = propertiesProvider.getIfAvailable();
        if (properties != null && !properties.worker().enabled()) {
            log.warn(
                "Asynchronous document ingestion worker is disabled (documind.ingestion.worker.enabled=false); "
                    + "uploaded documents will remain in PENDING until worker execution is enabled"
            );
        }
        return new VirtualThreadTaskExecutor(VIRTUAL_THREAD_PREFIX);
    }

    @Bean(name = CHAT_SSE_TASK_EXECUTOR)
    public AsyncTaskExecutor chatSseExecutor() {
        return new VirtualThreadTaskExecutor(SSE_VIRTUAL_THREAD_PREFIX);
    }

    @Bean(name = AUDIT_LOG_TASK_EXECUTOR)
    public AsyncTaskExecutor auditLogExecutor() {
        return new VirtualThreadTaskExecutor(AUDIT_VIRTUAL_THREAD_PREFIX);
    }
}
