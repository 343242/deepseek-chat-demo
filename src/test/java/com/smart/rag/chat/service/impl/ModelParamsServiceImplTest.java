package com.smart.rag.chat.service.impl;

import com.smart.rag.chat.dto.ModelParamsDTO;
import com.smart.rag.chat.entity.ModelParams;
import com.smart.rag.chat.context.CagProperties;
import com.smart.rag.chat.mapper.ModelParamsMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ModelParamsServiceImpl 单元测试
 * <p>
 * 测试保存/更新时的缓存行为、查询/列表功能。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ModelParamsServiceImpl 单元测试")
class ModelParamsServiceImplTest {

    @Mock
    private ModelParamsMapper mapper;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private CagProperties cagProperties;

    private ModelParamsServiceImpl service;

    @BeforeEach
    void setUp() {
        when(cagProperties.getModelParamsCacheTtlSeconds()).thenReturn(30);
        when(cagProperties.getModelParamsCacheMaxSize()).thenReturn(100);
        service = new ModelParamsServiceImpl(mapper, transactionTemplate, cagProperties);
    }

    @Nested
    @DisplayName("getParams (with cache)")
    class GetParams {

        @Test
        @DisplayName("getParams_cacheMiss_delegatesToMapper")
        void getParams_cacheMiss_delegatesToMapper() {
            ModelParams entity = new ModelParams("deepseek-chat");
            entity.setTemperature(0.7);
            when(mapper.selectByModelId("deepseek-chat")).thenReturn(entity);

            ModelParams result = service.getParams("deepseek-chat");

            assertNotNull(result);
            assertEquals("deepseek-chat", result.getModelId());
            assertEquals(0.7, result.getTemperature());

            // Call again -- should hit cache, not mapper again
            service.getParams("deepseek-chat");
            verify(mapper, times(1)).selectByModelId("deepseek-chat");
        }
    }

    @Nested
    @DisplayName("getParamsDTO")
    class GetParamsDTO {

        @Test
        @DisplayName("getParamsDTO_existingModel_returnsDTO")
        void getParamsDTO_existingModel_returnsDTO() {
            ModelParams entity = new ModelParams("deepseek-chat");
            entity.setTemperature(0.5);
            entity.setMaxTokens(2048);
            when(mapper.selectByModelId("deepseek-chat")).thenReturn(entity);

            var result = service.getParamsDTO("deepseek-chat");

            assertTrue(result.isPresent());
            assertEquals("deepseek-chat", result.get().modelId());
            assertEquals(0.5, result.get().temperature());
            assertEquals(2048, result.get().maxTokens());
        }

        @Test
        @DisplayName("getParamsDTO_nonExistingModel_returnsEmpty")
        void getParamsDTO_nonExistingModel_returnsEmpty() {
            when(mapper.selectByModelId("unknown-model")).thenReturn(null);

            var result = service.getParamsDTO("unknown-model");

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("saveOrUpdate")
    class SaveOrUpdate {

        @Test
        @DisplayName("saveOrUpdate_existingModel_updatesAndInvalidatesCache")
        void saveOrUpdate_existingModel_updatesAndInvalidatesCache() {
            // Prime the cache first
            ModelParams existing = new ModelParams("deepseek-chat");
            existing.setTemperature(0.5);
            when(mapper.selectByModelId("deepseek-chat")).thenReturn(existing);
            service.getParams("deepseek-chat");

            // Now for the saveOrUpdate call inside the transaction
            ModelParamsDTO updateDTO = new ModelParamsDTO("deepseek-chat", 0.9, 4096, 1.0, null, null);
            when(mapper.updateById(any(ModelParams.class))).thenReturn(1);

            when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
                org.springframework.transaction.support.TransactionCallback<ModelParamsDTO> cb =
                        invocation.getArgument(0);
                return cb.doInTransaction(mock(TransactionStatus.class));
            });

            ModelParamsDTO result = service.saveOrUpdate("deepseek-chat", updateDTO);

            assertEquals(0.9, result.temperature());
            assertEquals(4096, result.maxTokens());
            verify(mapper).updateById(any(ModelParams.class));

            // Cache should be invalidated; next getParams should call mapper again
            service.getParams("deepseek-chat");
            verify(mapper, times(2)).selectByModelId("deepseek-chat");
        }

        @Test
        @DisplayName("saveOrUpdate_newModel_insertsAndInvalidatesCache")
        void saveOrUpdate_newModel_insertsAndInvalidatesCache() {
            when(mapper.selectByModelId("new-model")).thenReturn(null);
            when(mapper.insert(any(ModelParams.class))).thenReturn(1);

            ModelParamsDTO dto = new ModelParamsDTO("new-model", 0.7, 2048, null, null, null);
            when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
                org.springframework.transaction.support.TransactionCallback<ModelParamsDTO> cb =
                        invocation.getArgument(0);
                return cb.doInTransaction(mock(TransactionStatus.class));
            });

            ModelParamsDTO result = service.saveOrUpdate("new-model", dto);

            assertEquals("new-model", result.modelId());
            assertEquals(0.7, result.temperature());
            verify(mapper).insert(any(ModelParams.class));
        }
    }

    @Nested
    @DisplayName("delete")
    class Delete {

        @Test
        @DisplayName("delete_existingModel_returnsTrueAndInvalidatesCache")
        void delete_existingModel_returnsTrueAndInvalidatesCache() {
            when(mapper.deleteByModelId("deepseek-chat")).thenReturn(1);

            boolean deleted = service.delete("deepseek-chat");

            assertTrue(deleted);
            verify(mapper).deleteByModelId("deepseek-chat");
        }

        @Test
        @DisplayName("delete_nonExistingModel_returnsFalse")
        void delete_nonExistingModel_returnsFalse() {
            when(mapper.deleteByModelId("non-existent")).thenReturn(0);

            boolean deleted = service.delete("non-existent");

            assertFalse(deleted);
        }
    }

    @Nested
    @DisplayName("listAll")
    class ListAll {

        @Test
        @DisplayName("listAll_returnsAllFromMapper")
        void listAll_returnsAllFromMapper() {
            ModelParams p1 = new ModelParams("deepseek-chat");
            p1.setTemperature(0.7);
            ModelParams p2 = new ModelParams("zhipu/glm-4-flash");
            p2.setTemperature(0.5);
            when(mapper.selectAllOrdered()).thenReturn(List.of(p1, p2));

            List<ModelParamsDTO> result = service.listAll();

            assertEquals(2, result.size());
            assertEquals("deepseek-chat", result.get(0).modelId());
            assertEquals("zhipu/glm-4-flash", result.get(1).modelId());
        }
    }
}
