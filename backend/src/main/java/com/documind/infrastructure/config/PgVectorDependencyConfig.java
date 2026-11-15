package com.documind.infrastructure.config;

import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AbstractDependsOnBeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Ensures Spring AI's {@link PgVectorStore} validates the {@code document_chunks} table
 * strictly after Flyway has executed {@code V1..V2} migrations on a fresh database.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass({PgVectorStore.class, FlywayMigrationInitializer.class})
public class PgVectorDependencyConfig {

    @Bean
    static BeanFactoryPostProcessor pgVectorStoreDependsOnFlywayPostProcessor() {
        return new AbstractDependsOnBeanFactoryPostProcessor(
            PgVectorStore.class,
            FlywayMigrationInitializer.class
        ) {
        };
    }
}
