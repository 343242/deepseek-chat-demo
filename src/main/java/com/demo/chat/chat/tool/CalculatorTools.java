package com.demo.chat.chat.tool;

import net.objecthunter.exp4j.Expression;
import net.objecthunter.exp4j.ExpressionBuilder;
import net.objecthunter.exp4j.UnknownFunctionException;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 计算器工具
 * <p>
 * 提供数学表达式计算能力，弥补模型在精确数值计算上的不足。
 * 使用 exp4j 库做纯数学表达式解析，无脚本引擎注入风险。
 * <p>
 * 安全措施：exp4j 只支持数学运算（加减乘除、幂、括号），
 * 不支持任何脚本/代码执行，从根本上杜绝注入。
 */
@Component
public class CalculatorTools {

    @Tool(description = "计算数学表达式的结果。支持加减乘除、括号、幂运算等。当需要进行精确数值计算时使用此工具，例如「123 * 456」「(100 + 200) / 3」「2 的 10 次方」。")
    public String calculate(
            @ToolParam(description = "数学表达式，如 '123 * 456' 或 '(100 + 200) / 3'") String expression) {
        String sanitized = expression.trim();
        if (sanitized.isEmpty()) {
            return "错误：表达式不能为空";
        }

        // 安全校验：仅允许数学字符（无逗号，避免歧义）
        if (!sanitized.matches("^[0-9+\\-*/().^\\s]+$")) {
            return "错误：表达式包含非法字符，仅支持数字和数学运算符（+ - * / ^ ()）";
        }

        try {
            Expression exp = new ExpressionBuilder(sanitized).build();
            double result = exp.evaluate();

            // 整数结果不显示小数点
            if (result == Math.floor(result) && !Double.isInfinite(result)) {
                return sanitized + " = " + (long) result;
            }
            return sanitized + " = " + result;
        } catch (UnknownFunctionException e) {
            return "错误：不支持的函数 - " + e.getMessage();
        } catch (IllegalArgumentException e) {
            return "表达式错误：" + e.getMessage();
        } catch (ArithmeticException e) {
            return "计算错误：" + e.getMessage();
        } catch (Exception e) {
            return "无法计算该表达式：" + e.getMessage();
        }
    }
}
