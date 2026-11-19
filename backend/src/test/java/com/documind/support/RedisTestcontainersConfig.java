package com.documind.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * JVM-wide singleton Redis 7 Testcontainer wired into Spring Boot via {@link ServiceConnection}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class RedisTestcontainersConfig {

    public static final String REDIS_IMAGE = "redis:7-alpine";
    private static final int REDIS_PORT = 6379;

    private static final GenericContainer<?> SHARED_REDIS = new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE))
        .withExposedPorts(REDIS_PORT)
        .waitingFor(Wait.forListeningPort());

    static {
        SHARED_REDIS.start();
    }

    @Bean(destroyMethod = "")
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return SHARED_REDIS;
    }

    public static String sharedHost() {
        return SHARED_REDIS.getHost();
    }

    public static int sharedMappedPort() {
        return SHARED_REDIS.getMappedPort(REDIS_PORT);
    }
}
