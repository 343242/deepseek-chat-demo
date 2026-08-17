package com.smart.rag.usage;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.smart.rag.usage.dto.UsageQueryFilter;
import com.smart.rag.usage.dto.UsageSummaryDTO;
import com.smart.rag.usage.entity.UsageEvent;
import com.smart.rag.usage.mapper.UsageEventMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.dao.DuplicateKeyException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * UsageEvent uuid 持久化集成测试——真 PG + 真 MyBatis-Plus 装配。
 * <p>
 * 回归背景（连续两次线上启动失败）：{@code event_id} 是 PG 原生 uuid 列，而 MyBatis 3.5.19
 * 不内置 UUID TypeHandler（issue #1609 wontfix）——实体字段用 String 会被驱动按 varchar 发参
 * （PG 不做隐式转换），用裸 UUID 又找不到 handler。本用例锁住
 * {@code UuidTypeHandler + autoResultMap} 的完整写读路径，mock 单测覆盖不到这一层。
 * <p>
 * DDL 只执行 V28 的建表段（跳过权限 seed：其引用的 sys_permission/sys_role 在裸测试库不存在，
 * 与本回归无关）。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("UsageEvent event_id(uuid 列) 写读集成回归")
class UsageEventUuidPersistenceTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:18-bookworm")
        .withDatabaseName("usage_event_test")
        .withUsername("test")
        .withPassword("test");

    private HikariDataSource dataSource;
    private UsageEventMapper mapper;

    @BeforeAll
    void setupDb() throws Exception {
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(PG.getJdbcUrl());
        dataSource.setUsername(PG.getUsername());
        dataSource.setPassword(PG.getPassword());

        String script = new String(
            new PathMatchingResourcePatternResolver()
                .getResource("classpath:db/migration/V28__usage_rework.sql")
                .getInputStream().readAllBytes(),
            java.nio.charset.StandardCharsets.UTF_8);
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            for (String stmt : script.split(";")) {
                String sql = stmt.lines()
                    .filter(line -> !line.trim().startsWith("--"))
                    .reduce((a, b) -> a + "\n" + b)
                    .orElse("")
                    .trim();
                // 跳过权限 seed（INSERT ... SELECT 依赖 sys_* 表，裸库不存在）
                if (!sql.isEmpty() && !sql.toUpperCase().startsWith("INSERT")) {
                    s.execute(sql);
                }
            }
        }

        GlobalConfig globalConfig = new GlobalConfig();
        GlobalConfig.DbConfig dbConfig = new GlobalConfig.DbConfig();
        dbConfig.setIdType(IdType.AUTO);
        globalConfig.setDbConfig(dbConfig);

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);

        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setGlobalConfig(globalConfig);
        factory.setConfiguration(configuration);
        factory.setMapperLocations(
            new PathMatchingResourcePatternResolver()
                .getResources("classpath*:/mapper/UsageEventMapper.xml"));
        SqlSessionTemplate template = new SqlSessionTemplate(factory.getObject());
        mapper = template.getMapper(UsageEventMapper.class);
    }

    @AfterAll
    void teardownDb() {
        dataSource.close();
    }

    @Test
    @DisplayName("insert + 按 id 读回：event_id UUID 往返一致；重复 event_id 触发唯一约束")
    void uuidRoundTripAndDuplicateKey() {
        UUID eventId = UUID.randomUUID();
        mapper.insert(new UsageEvent(eventId, 7L, "CHAT", "conv-1", "candidate-a",
            100L, 50L, 150L, false, true, 200L));

        UsageEvent loaded = mapper.selectById(
            mapper.selectPage(new Page<>(1, 10), null).getRecords().get(0).getId());
        assertThat(loaded.getEventId()).isEqualTo(eventId);
        assertThat(loaded.getScene()).isEqualTo("CHAT");
        assertThat(loaded.getTotalTokens()).isEqualTo(150L);

        assertThatThrownBy(() -> mapper.insert(new UsageEvent(eventId, 7L, "CHAT", "conv-1",
            "candidate-a", 1L, 1L, 2L, false, true, 10L)))
            .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("XML 聚合查询与 uuid 列共存（summary 不触碰 event_id 但共用同一 SqlSessionFactory）")
    void aggregateQueryRunsAgainstUuidColumnTable() {
        mapper.insert(new UsageEvent(UUID.randomUUID(), 7L, "AGENT", null, "candidate-b",
            null, null, null, false, false, 50L));

        // scene=非空必须踩通 XML 的 #{filter.scene.name()}——OGNL 属性式 .name 会触发
        // java.base 强封装 InaccessibleObjectException（线上 500 回归点）
        UsageSummaryDTO summary = mapper.selectSummary(
            new UsageQueryFilter(7L, "AGENT", null, null, null, null));
        assertThat(summary.requestCount()).isEqualTo(1);
        assertThat(summary.successCount()).isZero();
    }
}
