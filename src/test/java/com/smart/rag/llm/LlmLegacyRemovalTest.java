package com.smart.rag.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 旧 LLM 用户自配功能移除的验收测试（design llm-client-stateless §4 WS-C / §8 AC3/AC4）。
 * <p>
 * 文件级扫描断言（yml/.env.example 非 ArchUnit 管辖，故用平铺扫描而非 ArchUnit）：
 * <ol>
 *   <li>用户命名空间前缀字面量、旧功能关键词（大小写不敏感三形态）、
 *       旧表名与旧路由前缀（连字符/下划线两形态）在 main/test/yml/.env.example 全部清零
 *       （db/migration 历史迁移文件除外——Flyway 追加式历史不改写）</li>
 *   <li>modelconfig 模块目录不存在</li>
 *   <li>旧 admin/user 路由前缀在 main 代码零注册（对应端点随控制器删除，请求 404）</li>
 * </ol>
 * 扫描模式串以拼接构造，避免本测试文件自身命中扫描模式。
 */
@DisplayName("WS-C 验收：旧 LLM 用户自配功能移除清零")
class LlmLegacyRemovalTest {

    /** 旧功能关键词（运行时拼出，避免本文件静态命中大小写不敏感扫描） */
    private static final String LEGACY_TOKEN = String.valueOf(new char[]{'B', 'Y', 'O', 'K'});

    /** 用户命名空间 candidateId 前缀字面量：引号 + u + 冒号 */
    private static final String USER_NS_PREFIX = '"' + "u" + ":";

    /** 旧表名（下划线形态） */
    private static final String LEGACY_TABLE = "llm" + "_" + "config";

    /** 旧路由前缀（连字符形态） */
    private static final String LEGACY_ROUTE = "llm" + "-" + "config";

    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath();

    @Test
    @DisplayName("AC3：旧功能字面量清零（main/test/yml/.env.example，db/migration 除外）")
    void legacyLiteralsRemoved() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : scanTargets()) {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            String rel = PROJECT_ROOT.relativize(file).toString();
            if (content.contains(USER_NS_PREFIX)) {
                violations.add(rel + ": 用户命名空间前缀字面量");
            }
            if (containsIgnoreCase(content, LEGACY_TOKEN)) {
                violations.add(rel + ": 旧功能关键词");
            }
            if (content.contains(LEGACY_TABLE) || content.contains(LEGACY_ROUTE)) {
                violations.add(rel + ": 旧表名/路由前缀");
            }
        }
        assertThat(violations).as("以下文件仍含旧功能字面量：\n%s", String.join("\n", violations)).isEmpty();
    }

    @Test
    @DisplayName("AC4：modelconfig 模块目录不存在")
    void modelconfigModuleAbsent() {
        assertThat(Files.exists(PROJECT_ROOT.resolve("src/main/java/com/smart/rag/modelconfig")))
            .as("modelconfig 模块目录应已删除").isFalse();
        assertThat(Files.exists(PROJECT_ROOT.resolve("src/test/java/com/smart/rag/modelconfig")))
            .as("modelconfig 测试目录应已删除").isFalse();
    }

    @Test
    @DisplayName("AC4：旧 admin/user 路由前缀在 main 代码零注册（端点 404）")
    void legacyRoutesUnregistered() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(PROJECT_ROOT.resolve("src/main/java"))) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(file -> {
                try {
                    if (Files.readString(file, StandardCharsets.UTF_8).contains(LEGACY_ROUTE)) {
                        violations.add(PROJECT_ROOT.relativize(file).toString());
                    }
                } catch (IOException e) {
                    throw uncheck(e);
                }
            });
        }
        assertThat(violations).as("以下 main 文件仍引用旧路由前缀：\n", violations).isEmpty();
    }

    // ===== helpers =====

    private static boolean containsIgnoreCase(String content, String token) {
        return content.toLowerCase().contains(token.toLowerCase());
    }

    /** 扫描面：src/main/java、src/main/resources（db/migration 除外）、src/test/java（本测试除外）、.env.example */
    private static List<Path> scanTargets() throws IOException {
        List<Path> targets = new ArrayList<>();
        Path self = Path.of("src/test/java/com/smart/rag/llm/LlmLegacyRemovalTest.java");
        try (Stream<Path> javaMain = Files.walk(PROJECT_ROOT.resolve("src/main/java"));
             Stream<Path> resources = Files.walk(PROJECT_ROOT.resolve("src/main/resources"));
             Stream<Path> javaTest = Files.walk(PROJECT_ROOT.resolve("src/test/java"))) {
            javaMain.filter(LlmLegacyRemovalTest::isTextFile).forEach(targets::add);
            resources.filter(LlmLegacyRemovalTest::isTextFile)
                .filter(p -> !p.toString().contains("db" + System.getProperty("file.separator") + "migration"))
                .forEach(targets::add);
            javaTest.filter(LlmLegacyRemovalTest::isTextFile)
                .filter(p -> !PROJECT_ROOT.relativize(p).equals(self))
                .forEach(targets::add);
        }
        Path envExample = PROJECT_ROOT.resolve(".env.example");
        if (Files.exists(envExample)) {
            targets.add(envExample);
        }
        return targets;
    }

    private static boolean isTextFile(Path p) {
        String name = p.toString();
        return name.endsWith(".java") || name.endsWith(".yml") || name.endsWith(".yaml")
            || name.endsWith(".xml") || name.endsWith(".properties") || name.endsWith(".sql");
    }

    private static RuntimeException uncheck(IOException e) {
        return new RuntimeException(e);
    }
}
