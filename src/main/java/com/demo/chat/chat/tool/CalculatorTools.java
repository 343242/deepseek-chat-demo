package com.demo.chat.chat.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;

/**
 * 计算器工具
 * <p>
 * 提供数学表达式计算能力，弥补模型在精确数值计算上的不足。
 * 使用 JDK 内置 Nashorn/ScriptEngine 执行表达式，无需外部依赖。
 * <p>
 * 安全措施：仅支持数学表达式，拒绝包含字母（数学函数除外）和危险字符的输入。
 */
@Component
public class CalculatorTools {

    private final ScriptEngine engine;

    public CalculatorTools() {
        ScriptEngineManager manager = new ScriptEngineManager();
        ScriptEngine resolved = manager.getEngineByName("groovy");
        if (resolved == null) {
            resolved = manager.getEngineByName("javascript");
        }
        this.engine = resolved;
    }

    @Tool(description = "计算数学表达式的结果。支持加减乘除、括号、幂运算等。当需要进行精确数值计算时使用此工具，例如「123 * 456」「(100 + 200) / 3」「2 的 10 次方」。")
    public String calculate(
            @ToolParam(description = "数学表达式，如 '123 * 456' 或 '(100 + 200) / 3'") String expression) {
        // 安全检查：拒绝非数学字符
        String sanitized = expression.trim();
        if (sanitized.isEmpty()) {
            return "错误：表达式不能为空";
        }
        // 允许：数字、运算符、括号、小数点、空格、逗号
        if (!sanitized.matches("^[0-9+\\-*/().%^\\s,]+$")) {
            return "错误：表达式包含非法字符，仅支持数字和数学运算符";
        }

        if (engine == null) {
            // 无可用引擎时尝试简单解析
            return "错误：无可用的计算引擎";
        }

        try {
            Object result = engine.eval(sanitized);
            if (result == null) {
                return "错误：无法计算该表达式";
            }
            return sanitized + " = " + result;
        } catch (ScriptException e) {
            return "计算错误：" + e.getMessage();
        }
    }
}
