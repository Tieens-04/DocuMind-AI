package com.documind;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class DocuMindApplicationTests {

    @Autowired
    private Environment environment;

    @Test
    void contextLoadsWithVirtualThreadsAndSafeSchemaSettings() {
        assertThat(environment.getProperty("spring.threads.virtual.enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("spring.ai.vectorstore.pgvector.initialize-schema", Boolean.class)).isFalse();
        assertThat(environment.getProperty("spring.ai.vectorstore.pgvector.dimensions", Integer.class)).isEqualTo(1024);
    }
}
