package com.demo.chat.chat.tool.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * 沙箱执行服务
 * <p>
 * 通过 Docker 容器实现代码隔离执行，每次执行创建独立容器，
 * 执行完毕自动清理（--rm）。多维度安全限制：
 * <ul>
 *   <li>网络隔离：--network=none</li>
 *   <li>文件系统只读：--read-only + tmpfs /tmp</li>
 *   <li>非 root 用户：--user nobody</li>
 *   <li>资源限制：内存、CPU、进程数</li>
 *   <li>超时控制：双重保障（timeout 命令 + Java Future 超时）</li>
 * </ul>
 */
public class SandboxService {

    private static final Logger log = LoggerFactory.getLogger(SandboxService.class);

    private final SandboxConfig config;
    private final ExecutorService executor;
    private final Semaphore concurrencyLimiter;
    private final boolean dockerAvailable;

    public SandboxService(SandboxConfig config) {
        this.config = config;
        this.concurrencyLimiter = new Semaphore(config.maxConcurrency());
        // 虚线程 per-task：每个容器执行独立一个虚线程，通过 Semaphore 限流
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.dockerAvailable = checkDockerAvailable();
        if (dockerAvailable) {
            log.info("SandboxService initialized: maxConcurrency={}, timeout={}ms, docker available",
                    config.maxConcurrency(), config.timeout().toMillis());
        } else {
            log.warn("Docker not available, sandbox code execution disabled");
        }
    }

    /**
     * 在沙箱中执行代码
     *
     * @param language 执行语言
     * @param code     代码内容
     * @return 执行结果
     */
    public SandboxResult execute(Language language, String code) {
        if (!dockerAvailable) {
            return new SandboxResult(-1, "", "沙箱不可用：Docker 未运行或不可访问", false, 0);
        }

        String image = config.getImage(language);
        if (image == null) {
            return new SandboxResult(-1, "", "不支持的语言: " + language, false, 0);
        }

        // 写入临时文件
        Path tempFile;
        try {
            tempFile = Files.createTempFile("sandbox-", language.getExtension());
            Files.writeString(tempFile, code, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to create temp file for sandbox", e);
            return new SandboxResult(-1, "", "沙箱初始化失败，请稍后重试", false, 0);
        }

        try {
            return executeInContainer(image, language, tempFile);
        } finally {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * 是否可用
     */
    public boolean isAvailable() {
        return dockerAvailable;
    }

    /**
     * 在 Docker 容器中执行代码
     */
    private SandboxResult executeInContainer(String image, Language language, Path codeFile) {
        String containerId = null;
        long startTime = System.currentTimeMillis();

        try {
            // 构建 docker create 命令
            List<String> createCmd = buildCreateCommand(image, language, codeFile);
            log.debug("Creating sandbox container: {}", String.join(" ", createCmd));

            // 创建容器
            Process createProcess = new ProcessBuilder(createCmd)
                    .redirectErrorStream(true)
                    .start();
            String createOutput = new String(createProcess.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int createExit = createProcess.waitFor();

            if (createExit != 0) {
                return new SandboxResult(createExit, "", "创建容器失败: " + createOutput.trim(),
                        false, System.currentTimeMillis() - startTime);
            }

            containerId = createOutput.trim();
            log.debug("Container created: {}", containerId);

            // 启动容器并等待（带超时）
            final String cid = containerId;
            Future<SandboxResult> future = executor.submit(() -> {
                concurrencyLimiter.acquire();
                try {
                    return startAndWait(cid, startTime);
                } finally {
                    concurrencyLimiter.release();
                }
            });

            try {
                return future.get(config.timeout().toMillis() + 2000, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                // 超时：强制 kill
                killContainer(cid);
                long duration = System.currentTimeMillis() - startTime;
                return new SandboxResult(137, "", "执行超时（" + config.timeout().toSeconds() + "s）",
                        true, duration);
            }

        } catch (Exception e) {
            log.error("Sandbox execution failed", e);
            return new SandboxResult(-1, "", "执行失败: " + e.getMessage(),
                    false, System.currentTimeMillis() - startTime);
        } finally {
            // 安全清理：即使 --rm 失败也尝试手动删除
            if (containerId != null) {
                forceRemoveContainer(containerId);
            }
        }
    }

    /**
     * 构建 docker create 命令
     */
    private List<String> buildCreateCommand(String image, Language language, Path codeFile) {
        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.add("create");
        cmd.add("--rm");

        // 安全限制
        cmd.add("--network=none");
        cmd.add("--read-only");
        cmd.add("--tmpfs=/tmp:size=10m");
        cmd.add("--user");
        cmd.add("nobody");
        cmd.add("--memory=" + config.maxMemoryMB() + "m");
        cmd.add("--cpus=" + config.maxCpus());
        cmd.add("--pids-limit=64");

        // 挂载代码文件（只读）
        cmd.add("-v");
        cmd.add(codeFile.toAbsolutePath() + ":/tmp/code" + language.getExtension() + ":ro");

        // 镜像
        cmd.add(image);

        // 执行命令：timeout + 语言命令
        cmd.add("timeout");
        cmd.add(String.valueOf(config.timeout().toSeconds()));
        cmd.addAll(buildRunCommand(language));

        return cmd;
    }

    /**
     * 构建语言执行命令
     */
    private List<String> buildRunCommand(Language language) {
        return switch (language) {
            case PYTHON -> List.of("python3", "/tmp/code.py");
            case JAVASCRIPT -> List.of("node", "/tmp/code.js");
            case TYPESCRIPT -> List.of("npx", "tsx", "/tmp/code.ts");
            case JAVA -> List.of("sh", "-c",
                    "cp /tmp/code.java /tmp/Main.java && javac /tmp/Main.java -d /tmp && java -cp /tmp Main");
        };
    }

    /**
     * 启动容器并等待结果
     */
    private SandboxResult startAndWait(String containerId, long startTime) throws Exception {
        Process startProcess = new ProcessBuilder("docker", "start", "-a", containerId)
                .redirectErrorStream(true)
                .start();

        String output = truncateOutput(startProcess.getInputStream().readAllBytes());
        int exitCode = startProcess.waitFor();
        long duration = System.currentTimeMillis() - startTime;

        return new SandboxResult(exitCode, output, "",
                exitCode == 124 || exitCode == 137, duration);
    }

    /**
     * 截断输出到最大长度
     */
    private String truncateOutput(byte[] bytes) {
        if (bytes.length > config.maxOutputBytes()) {
            byte[] truncated = new byte[config.maxOutputBytes()];
            System.arraycopy(bytes, 0, truncated, 0, config.maxOutputBytes());
            return new String(truncated, StandardCharsets.UTF_8)
                    + "\n... (输出已截断，超过 " + config.maxOutputBytes() + " 字节)";
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * 强制终止容器
     */
    private void killContainer(String containerId) {
        try {
            new ProcessBuilder("docker", "kill", containerId)
                    .start().waitFor(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Failed to kill container {}: {}", containerId, e.getMessage());
        }
    }

    /**
     * 强制删除容器（兜底清理）
     */
    private void forceRemoveContainer(String containerId) {
        try {
            new ProcessBuilder("docker", "rm", "-f", containerId)
                    .start().waitFor(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
    }

    /**
     * 检测 Docker 是否可用
     */
    private boolean checkDockerAvailable() {
        try {
            Process process = new ProcessBuilder("docker", "info")
                    .redirectErrorStream(true)
                    .start();
            return process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
