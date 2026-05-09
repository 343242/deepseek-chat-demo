package com.demo.chat.chat.tool.sandbox;

/**
 * 沙箱执行结果
 *
 * @param exitCode  进程退出码（0=成功，137=被 kill，124=超时）
 * @param stdout    标准输出（已截断）
 * @param stderr    标准错误输出（已截断）
 * @param timedOut  是否超时
 * @param durationMs 执行耗时（毫秒）
 */
public record SandboxResult(
        int exitCode,
        String stdout,
        String stderr,
        boolean timedOut,
        long durationMs
) {

    public boolean isSuccess() {
        return exitCode == 0 && !timedOut;
    }

    /**
     * 格式化为可读字符串，供模型理解执行结果
     */
    public String toModelString() {
        if (timedOut) {
            return "执行超时（" + durationMs + "ms），代码可能存在死循环或执行时间过长";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("退出码: ").append(exitCode);

        if (stdout != null && !stdout.isEmpty()) {
            sb.append("\n输出:\n").append(stdout);
        }
        if (stderr != null && !stderr.isEmpty()) {
            sb.append("\n错误:\n").append(stderr);
        }

        return sb.toString();
    }
}
