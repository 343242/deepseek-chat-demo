package com.smart.rag.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("基础设施层包边界")
class InfrastructureBoundaryTest {

    private static final Path INFRASTRUCTURE_ROOT = Path.of(
            "src/main/java/com/smart/rag/infrastructure");

    private static final Set<String> BUSINESS_PACKAGE_PREFIXES = Set.of(
            "com.smart.rag.agent.",
            "com.smart.rag.chat.",
            "com.smart.rag.conversation.",
            "com.smart.rag.rag.",
            "com.smart.rag.security.",
            "com.smart.rag.team.",
            "com.smart.rag.user."
    );

    @Test
    @DisplayName("infrastructure 源码不依赖业务包")
    void infrastructureDoesNotDependOnBusinessPackages() throws IOException {
        List<String> violations = new ArrayList<>();

        try (Stream<Path> files = Files.walk(INFRASTRUCTURE_ROOT)) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> collectViolations(path, violations));
        }

        assertThat(violations)
                .as("infrastructure 只能提供技术支撑，不能持有业务语义依赖")
                .isEmpty();
    }

    private static void collectViolations(Path path, List<String> violations) {
        try {
            List<String> lines = Files.readAllLines(path);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).strip();
                if (!line.startsWith("import ")) {
                    continue;
                }
                for (String prefix : BUSINESS_PACKAGE_PREFIXES) {
                    if (line.startsWith("import " + prefix)) {
                        violations.add("%s:%d %s".formatted(path, i + 1, line));
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to inspect " + path, e);
        }
    }
}
