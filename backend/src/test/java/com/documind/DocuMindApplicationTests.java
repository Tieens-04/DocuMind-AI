package com.documind;

import com.documind.service.document.CallerContextResolver;
import com.documind.service.document.DevFallbackCallerContextResolver;
import com.documind.service.document.ProdCallerContextResolver;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class DocuMindApplicationTests {

    @Autowired
    private Environment environment;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private CallerContextResolver callerContextResolver;

    @Autowired
    private ChatClient primaryChatClient;

    @Autowired
    @Qualifier("geminiChatClient")
    private ChatClient geminiChatClient;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Test
    void contextLoadsWithVirtualThreadsSafeSchemaAndDualAiBeans() {
        assertThat(environment.getProperty("spring.threads.virtual.enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("spring.ai.vectorstore.pgvector.initialize-schema", Boolean.class)).isFalse();
        assertThat(environment.getProperty("spring.ai.vectorstore.pgvector.dimensions", Integer.class)).isEqualTo(1024);
        assertThat(applicationContext.getBeansOfType(DevFallbackCallerContextResolver.class)).isEmpty();
        assertThat(callerContextResolver).isInstanceOf(ProdCallerContextResolver.class);

        assertThat(primaryChatClient).isNotNull();
        assertThat(geminiChatClient).isNotNull().isNotSameAs(primaryChatClient);
        assertThat(embeddingModel).isInstanceOf(OllamaEmbeddingModel.class);

        assertThat(applicationContext.containsBean("applicationTaskExecutor")).isTrue();
        assertThat(applicationContext.containsBean("taskExecutor")).isTrue();
        assertThat(applicationContext.containsBean("documentIngestionExecutor")).isTrue();
        Object primaryExecutor = applicationContext.getBean(org.springframework.core.task.TaskExecutor.class);
        assertThat(primaryExecutor)
            .isSameAs(applicationContext.getBean("applicationTaskExecutor"))
            .isNotSameAs(applicationContext.getBean("documentIngestionExecutor"));

        assertThat(environment.getProperty("spring.http.client.connect-timeout")).isEqualTo("10s");
        assertThat(environment.getProperty("spring.http.client.read-timeout")).isEqualTo("90s");
        var httpClientProps = applicationContext.getBean(
            org.springframework.boot.autoconfigure.http.client.HttpClientProperties.class
        );
        assertThat(httpClientProps.getConnectTimeout()).isEqualTo(java.time.Duration.ofSeconds(10));
        assertThat(httpClientProps.getReadTimeout()).isEqualTo(java.time.Duration.ofSeconds(90));
        assertThat(environment.getProperty("spring.datasource.hikari.maximum-pool-size", Integer.class)).isEqualTo(20);
        assertThat(environment.getProperty("spring.datasource.hikari.minimum-idle", Integer.class)).isEqualTo(5);
        assertThat(environment.getProperty("spring.datasource.hikari.connection-timeout", Long.class)).isEqualTo(5000L);
        assertThat(environment.getProperty("spring.data.redis.timeout")).isEqualTo("500ms");
        assertThat(environment.getProperty("spring.data.redis.connect-timeout")).isEqualTo("1s");
        assertThat(environment.getProperty("server.forward-headers-strategy")).isEqualTo("native");
    }
}
