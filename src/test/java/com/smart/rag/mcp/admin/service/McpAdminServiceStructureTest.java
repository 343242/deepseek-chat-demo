package com.smart.rag.mcp.admin.service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class McpAdminServiceStructureTest {

    @Test
    void adminServiceIsAThinFacade() throws Exception {
        assertThat(ApplicationRunner.class.isAssignableFrom(McpAdminService.class)).isFalse();
        assertThat(McpAdminService.class.getDeclaredConstructors())
                .singleElement()
                .satisfies(constructor -> assertThat(constructor.getParameterCount()).isLessThanOrEqualTo(3));

        Path source = Path.of("src/main/java/com/smart/rag/mcp/admin/service/McpAdminService.java");
        assertThat(Files.readAllLines(source).size()).isLessThanOrEqualTo(300);
    }
}
