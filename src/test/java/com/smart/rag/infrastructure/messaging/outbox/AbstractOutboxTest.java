package com.smart.rag.infrastructure.messaging.outbox;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;

/**
 * Outbox 测试基座——PostgreSQL container + 手工装配 MyBatis-Plus（项目无 SpringBootTest 惯例，
 * 保持轻量：Flyway baseline 22 → 仅执行 V23__outbox.sql，不动业务 schema）。
 * <p>
 * 装配（对应生产链路）：HikariDataSource → MybatisSqlSessionFactoryBean（OutboxMapper.xml）→
 * SqlSessionTemplate → OutboxMapper；DataSourceTransactionManager → TransactionTemplate。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractOutboxTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:18-bookworm")
        .withDatabaseName("outbox_test")
        .withUsername("test")
        .withPassword("test");

    private HikariDataSource dataSource;
    private OutboxMapper mapper;
    private TransactionTemplate tx;
    private final List<String> tables = List.of("outbox");

    @BeforeAll
    void setupDb() throws Exception {
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(PG.getJdbcUrl());
        dataSource.setUsername(PG.getUsername());
        dataSource.setPassword(PG.getPassword());
        dataSource.setMaximumPoolSize(10);

        // 执行真实迁移文件 V23__outbox.sql（单源：测试不吃重复 DDL）
        // 注意：不用 Flyway migrate——空库上 Flyway 会跑 V1–V22（含 pgvector 扩展，plain postgres 镜像没有）。
        String script = new String(
            new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
                .getResource("classpath:db/migration/V23__outbox.sql")
                .getInputStream().readAllBytes(),
            java.nio.charset.StandardCharsets.UTF_8);
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            for (String stmt : script.split(";")) {
                // 去掉纯注释行（文件头/段注释在 DDL 块前，不能整块跳过）
                String sql = stmt.lines()
                    .filter(line -> !line.trim().startsWith("--"))
                    .reduce((a, b) -> a + "\n" + b)
                    .orElse("")
                    .trim();
                if (!sql.isEmpty()) {
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
            new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
                .getResources("classpath*:/mapper/OutboxMapper.xml"));
        SqlSessionTemplate template = new SqlSessionTemplate(factory.getObject());
        mapper = template.getMapper(OutboxMapper.class);

        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @AfterAll
    void teardownDb() {
        dataSource.close();
    }

    protected OutboxMapper mapper() {
        return mapper;
    }

    protected javax.sql.DataSource dataSource() {
        return dataSource;
    }

    protected TransactionTemplate tx() {
        return tx;
    }

    /** 直接 JDBC 清表（BaseMapper delete 也行，但直连更快且无实体约束）。 */
    protected void clearOutbox() {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("TRUNCATE outbox RESTART IDENTITY");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    protected long countRows(String status) {
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT count(*) FROM outbox" + (status == null ? "" : " WHERE status='" + status + "'"))) {
            rs.next();
            return rs.getLong(1);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 插入一条到期 pending 行（next_retry_at 过去）。 */
    protected OutboxEntry insertPending(String topic, int attempts) {
        OutboxEntry e = new OutboxEntry();
        e.setTopic(topic);
        e.setPayload("\"payload-" + topic + "\"");   // 合法 JSON 字符串
        e.setPayloadType(String.class.getName());
        e.setTag("tag-1");
        e.setHashKey("hash-1");
        e.setDedupKey("dedup-1");
        e.setHeaders("{\"traceparent\":\"00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01\"}");
        e.setStatus("pending");
        e.setAttempts(attempts);
        e.setNextRetryAt(Instant.now().minusSeconds(60));
        e.setCreatedAt(Instant.now().minusSeconds(60));
        e.setUpdatedAt(Instant.now().minusSeconds(60));
        mapper.insert(e);
        return e;
    }

    /** 读取行的当前状态（claim 后断言用）。 */
    protected OutboxEntry reload(Long id) {
        return mapper.selectById(id);
    }
}
