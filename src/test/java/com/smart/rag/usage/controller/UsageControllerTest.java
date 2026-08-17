package com.smart.rag.usage.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.infrastructure.request.PageRequest;
import com.smart.rag.infrastructure.response.PagedResult;
import com.smart.rag.infrastructure.exception.GlobalExceptionHandler;
import com.smart.rag.infrastructure.web.util.SecurityUtils;
import com.smart.rag.usage.dto.UsageEventDTO;
import com.smart.rag.usage.dto.UsageQueryFilter;
import com.smart.rag.usage.dto.UsageStatsDim;
import com.smart.rag.usage.dto.UsageStatsOrder;
import com.smart.rag.usage.dto.UsageStatsSort;
import com.smart.rag.usage.service.UsageEventService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UsageController 测试（standalone MockMvc 惯例）— 参数绑定/过滤条件组装/本人维度缺省。
 * <p>
 * 跨用户访问的管理员门槛是方法级 @PreAuthorize SpEL（usage:view:all），属声明式安全配置，
 * 由 Spring Security 方法安全在运行时执行，不在 standalone MockMvc 中重复验证。
 */
@ExtendWith(MockitoExtension.class)
class UsageControllerTest {

    @Mock
    private UsageEventService usageEventService;

    private MockMvc mockMvc;

    private MockedStatic<SecurityUtils> securityUtils;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        mockMvc = MockMvcBuilders.standaloneSetup(new UsageController(usageEventService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();
        securityUtils = org.mockito.Mockito.mockStatic(SecurityUtils.class);
        securityUtils.when(SecurityUtils::getCurrentUserId).thenReturn(42L);
    }

    @AfterEach
    void tearDown() {
        securityUtils.close();
    }

    @Test
    @DisplayName("records: scene/model 过滤 + 分页参数透传，userId 缺省为当前登录用户")
    void recordsBindsFiltersAndSelfUser() throws Exception {
        when(usageEventService.getRecords(any(), any())).thenReturn(new PagedResult<>(
            List.of(new UsageEventDTO("event-1", 42L, "CHAT", "conv-1", "candidate-a",
                100L, 50L, 150L, false, true, 200L, null)),
            1, 20, 1, 1));

        mockMvc.perform(get("/api/usage/records")
                .param("scene", "CHAT")
                .param("model", "candidate-a")
                .param("page", "2")
                .param("size", "50")
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.content[0].eventId").value("event-1"));

        ArgumentCaptor<UsageQueryFilter> filterCaptor = ArgumentCaptor.forClass(UsageQueryFilter.class);
        ArgumentCaptor<PageRequest> pageCaptor = ArgumentCaptor.forClass(PageRequest.class);
        verify(usageEventService).getRecords(filterCaptor.capture(), pageCaptor.capture());
        assertThat(filterCaptor.getValue().userId()).isEqualTo(42L);
        assertThat(filterCaptor.getValue().scene()).isEqualTo("CHAT");
        assertThat(filterCaptor.getValue().model()).isEqualTo("candidate-a");
        assertThat(pageCaptor.getValue().page()).isEqualTo(2);
        assertThat(pageCaptor.getValue().size()).isEqualTo(50);
    }

    @Test
    @DisplayName("records: 管理员显式传 userId 时以目标用户过滤")
    void recordsUsesExplicitTargetUser() throws Exception {
        when(usageEventService.getRecords(any(), any())).thenReturn(new PagedResult<>(
            List.of(), 1, 20, 0, 0));

        mockMvc.perform(get("/api/usage/records").param("userId", "7"))
            .andExpect(status().isOk());

        ArgumentCaptor<UsageQueryFilter> filterCaptor = ArgumentCaptor.forClass(UsageQueryFilter.class);
        verify(usageEventService).getRecords(filterCaptor.capture(), any());
        assertThat(filterCaptor.getValue().userId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("timeline: granularity 绑定为枚举（非法值 400）")
    void timelineBindsGranularityEnum() throws Exception {
        when(usageEventService.getTimeline(any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/api/usage/timeline").param("granularity", "MONTH"))
            .andExpect(status().isOk());

        verify(usageEventService).getTimeline(
            eq(com.smart.rag.usage.dto.TimelineGranularity.MONTH), any());
    }

    @Test
    @DisplayName("timeline: 非法 granularity 被 GlobalExceptionHandler 拦截（HTTP 200 + 业务码）")
    void timelineRejectsInvalidGranularity() throws Exception {
        mockMvc.perform(get("/api/usage/timeline").param("granularity", "week; DROP TABLE"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(0)));
    }

    @Test
    @DisplayName("stats: dim/sort/order 枚举透传")
    void statsBindsDimSortOrder() throws Exception {
        when(usageEventService.getStats(any(), any(), any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/api/usage/stats")
                .param("dim", "SCENE")
                .param("sort", "REQUEST_COUNT")
                .param("order", "ASC"))
            .andExpect(status().isOk());

        verify(usageEventService).getStats(eq(UsageStatsDim.SCENE), eq(UsageStatsSort.REQUEST_COUNT),
            eq(UsageStatsOrder.ASC), any());
    }

    @Test
    @DisplayName("stats: 非法 dim 被 GlobalExceptionHandler 拦截（HTTP 200 + 业务码）")
    void statsRejectsInvalidDim() throws Exception {
        mockMvc.perform(get("/api/usage/stats").param("dim", "user_id; --"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(0)));
    }
}
