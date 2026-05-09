package com.demo.chat.chat.tool;

import com.demo.chat.chat.tool.sandbox.Language;
import com.demo.chat.chat.tool.sandbox.SandboxResult;
import com.demo.chat.chat.tool.sandbox.SandboxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 代码执行工具
 * <p>
 * 在 Docker 沙箱容器中执行用户代码，返回执行结果。
 * 每次执行创建独立容器，用完即弃（--rm 自动清理）。
 * <p>
 * 安全措施：
 * <ul>
 *   <li>网络隔离（--network=none）</li>
 *   <li>文件系统只读（--read-only）</li>
 *   <li>非 root 用户（--user nobody）</li>
 *   <li>资源限制（内存、CPU、进程数、超时）</li>
 *   <li>代码长度限制（5000 字符）</li>
 * </ul>
 * <p>
 * Docker 不可用时工具返回错误提示，不影响其他功能。
 */
@Component
public class CodeExecutionTool {

    private static final Logger log = LoggerFactory.getLogger(CodeExecutionTool.class);
    private static final int MAX_CODE_LENGTH = 5000;

    private final SandboxService sandboxService;

    public CodeExecutionTool(SandboxService sandboxService) {
        this.sandboxService = sandboxService;
    }

    @Tool(description = "在安全的沙箱环境中执行代码并返回运行结果。" +
            "支持 Python、JavaScript、Java 三种语言。" +
            "当需要运行代码验证结果、执行数学计算、处理数据、测试算法时使用此工具。" +
            "每次执行都在隔离的 Docker 容器中，不会影响主服务。")
    public String executeCode(
            @ToolParam(description = "要执行的代码，必须是完整可运行的代码。" +
                    "Python: 直接写代码体。" +
                    "JavaScript: 直接写代码。" +
                    "Java: 写一个包含 main 方法的类（类名必须是 Main）。" +
                    "代码长度不能超过 5000 字符。") String code,
            @ToolParam(description = "编程语言，可选值：python、javascript、java。默认 python。") String language) {

        // 前置校验
        if (code == null || code.isBlank()) {
            return "错误：代码不能为空";
        }
        if (code.length() > MAX_CODE_LENGTH) {
            return "错误：代码过长（" + code.length() + " 字符），最多 " + MAX_CODE_LENGTH + " 字符";
        }

        // 解析语言
        Language lang = Language.fromString(language);
        if (lang == null) {
            lang = Language.PYTHON; // 默认 Python
        }

        // 检查沙箱可用性
        if (!sandboxService.isAvailable()) {
            return "错误：代码执行沙箱不可用（Docker 未运行），请联系管理员";
        }

        log.info("Executing {} code in sandbox ({} chars)", lang.getDisplayName(), code.length());

        SandboxResult result = sandboxService.execute(lang, code);

        log.info("Sandbox execution completed: exitCode={}, timedOut={}, duration={}ms",
                result.exitCode(), result.timedOut(), result.durationMs());

        return result.toModelString();
    }
}
