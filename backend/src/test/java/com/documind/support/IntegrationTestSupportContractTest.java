package com.documind.support;

import com.documind.controller.dto.ChatAskResponse;
import com.documind.controller.dto.CitationDto;
import com.documind.service.rag.RagConstants;
import com.documind.support.AbstractIntegrationTest.RecordingTestChatModel;
import com.documind.support.SampleEnterpriseCorpusFactory.EvalQueryCase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IntegrationTestSupportContractTest {

    @Test
    void resolveTestUserIdCoversAll20MilestoneEvalQueriesAndFailsFastOnUnmappedCombinations() {
        List<EvalQueryCase> evalCases = SampleEnterpriseCorpusFactory.loadWeek1MilestoneQueries(new ObjectMapper());
        assertThat(evalCases).hasSize(20);

        for (EvalQueryCase evalCase : evalCases) {
            UUID deptId = SampleEnterpriseCorpusFactory.departmentIdForCode(evalCase.dept());
            UUID userId = AbstractIntegrationTest.resolveTestUserId(evalCase.role(), deptId);
            assertThat(userId)
                .as("Expected non-null seeded user for %s (%s / %s)", evalCase.id(), evalCase.role(), evalCase.dept())
                .isNotNull();
        }

        // All 7 valid combinations across IT / HR / SALES
        assertThat(AbstractIntegrationTest.resolveTestUserId("SYSTEM_ADMIN", SampleEnterpriseCorpusFactory.IT_DEPT_ID))
            .isEqualTo(AbstractIntegrationTest.ADMIN_USER_ID);
        assertThat(AbstractIntegrationTest.resolveTestUserId("DEPARTMENT_MANAGER", SampleEnterpriseCorpusFactory.IT_DEPT_ID))
            .isEqualTo(AbstractIntegrationTest.IT_MANAGER_USER_ID);
        assertThat(AbstractIntegrationTest.resolveTestUserId("DEPARTMENT_MANAGER", SampleEnterpriseCorpusFactory.HR_DEPT_ID))
            .isEqualTo(AbstractIntegrationTest.HR_MANAGER_USER_ID);
        assertThat(AbstractIntegrationTest.resolveTestUserId("DEPARTMENT_MANAGER", SampleEnterpriseCorpusFactory.SALES_DEPT_ID))
            .isEqualTo(AbstractIntegrationTest.SALES_MANAGER_USER_ID);
        assertThat(AbstractIntegrationTest.resolveTestUserId("STANDARD_EMPLOYEE", SampleEnterpriseCorpusFactory.IT_DEPT_ID))
            .isEqualTo(AbstractIntegrationTest.IT_EMPLOYEE_USER_ID);
        assertThat(AbstractIntegrationTest.resolveTestUserId("STANDARD_EMPLOYEE", SampleEnterpriseCorpusFactory.HR_DEPT_ID))
            .isEqualTo(AbstractIntegrationTest.HR_EMPLOYEE_USER_ID);
        assertThat(AbstractIntegrationTest.resolveTestUserId("STANDARD_EMPLOYEE", SampleEnterpriseCorpusFactory.SALES_DEPT_ID))
            .isEqualTo(AbstractIntegrationTest.SALES_EMPLOYEE_USER_ID);

        // Unmapped or null arguments must fail fast rather than returning null silently
        assertThatThrownBy(() -> AbstractIntegrationTest.resolveTestUserId(
            "SYSTEM_ADMIN",
            SampleEnterpriseCorpusFactory.HR_DEPT_ID
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AbstractIntegrationTest.resolveTestUserId(
            "UNKNOWN_ROLE",
            SampleEnterpriseCorpusFactory.IT_DEPT_ID
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AbstractIntegrationTest.resolveTestUserId(
            null,
            SampleEnterpriseCorpusFactory.IT_DEPT_ID
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AbstractIntegrationTest.resolveTestUserId(
            "STANDARD_EMPLOYEE",
            null
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void isSafeOosRefusalResponseDetectsForbiddenPhraseInAnswerCitationExcerptAndSystemPrompt() {
        EvalQueryCase oosRbac = new EvalQueryCase(
            "OOS-RBAC-TEST",
            "OOS_RBAC",
            "STANDARD_EMPLOYEE",
            "SALES",
            "query",
            null,
            null,
            null,
            "120 triệu"
        );
        RecordingTestChatModel cleanModel = new RecordingTestChatModel();

        ChatAskResponse safeResponse = new ChatAskResponse(
            RagConstants.SAFE_REFUSAL_MESSAGE,
            true,
            List.of(),
            null,
            0,
            0,
            5L
        );
        assertThat(AbstractIntegrationTest.isSafeOosRefusalResponse(oosRbac, safeResponse, cleanModel)).isTrue();

        ChatAskResponse leakedAnswer = new ChatAskResponse(
            "Hạn mức bảo lãnh là 120 TRIỆU đồng/năm.",
            true,
            List.of(),
            null,
            0,
            0,
            5L
        );
        assertThat(AbstractIntegrationTest.isSafeOosRefusalResponse(oosRbac, leakedAnswer, cleanModel)).isFalse();

        // F-03 exact regression scenario: safe answer text, abstained=true, but citation excerpt leaks secret
        CitationDto leakedCitation = new CitationDto(
            UUID.randomUUID(),
            "vn-hr-handbook-2026.pdf",
            3,
            null,
            0.79d,
            "Hạn mức nội trú 120 triệu đồng/năm tại bệnh viện liên kết.",
            "https://minio.local/presigned"
        );
        ChatAskResponse leakedViaCitation = new ChatAskResponse(
            RagConstants.SAFE_REFUSAL_MESSAGE,
            true,
            List.of(leakedCitation),
            null,
            0,
            0,
            5L
        );
        assertThat(AbstractIntegrationTest.isSafeOosRefusalResponse(oosRbac, leakedViaCitation, cleanModel)).isFalse();

        RecordingTestChatModel invokedModel = new RecordingTestChatModel();
        invokedModel.call(new Prompt(List.of(
            new SystemMessage("Context mentions 120 triệu đồng"),
            new UserMessage("query")
        )));
        assertThat(AbstractIntegrationTest.isSafeOosRefusalResponse(oosRbac, safeResponse, invokedModel)).isFalse();
    }

    @Test
    void recordingChatClientPostProcessorInterceptsArbitraryBeanNamesAndSharesSingleModelInstance() {
        RecordingTestChatModel shared = new RecordingTestChatModel();
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("recordingTestChatModel", shared);
        ObjectProvider<RecordingTestChatModel> provider = beanFactory.getBeanProvider(RecordingTestChatModel.class);

        BeanPostProcessor bpp = AbstractIntegrationTest.createRecordingChatClientPostProcessor(provider);

        ChatModel failIfCalled = prompt -> {
            throw new AssertionError("Real ChatModel must never be called");
        };
        ChatClient renamedPrimary = (ChatClient) bpp.postProcessAfterInitialization(
            ChatClient.create(failIfCalled),
            "renamedPrimaryChatClient"
        );
        ChatClient customFallback = (ChatClient) bpp.postProcessAfterInitialization(
            ChatClient.create(failIfCalled),
            "customFallbackChatClient"
        );
        ChatClient.Builder interceptedBuilder = (ChatClient.Builder) bpp.postProcessAfterInitialization(
            ChatClient.builder(failIfCalled),
            "chatClientBuilder"
        );

        Prompt samplePrompt = new Prompt(List.of(
            new SystemMessage("ctx citation=\"[Nguồn: a.pdf]\""),
            new UserMessage("q")
        ));

        assertThat(renamedPrimary.prompt(samplePrompt).call().content()).contains("[Nguồn: a.pdf]");
        assertThat(customFallback.prompt(samplePrompt).call().content()).contains("[Nguồn: a.pdf]");
        assertThat(interceptedBuilder.build().prompt(samplePrompt).call().content()).contains("[Nguồn: a.pdf]");
        assertThat(shared.callCount()).isEqualTo(3);
    }
}
