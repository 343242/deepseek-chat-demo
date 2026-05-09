package com.demo.chat.chat.tool.sandbox;

/**
 * 支持的沙箱执行语言
 * <p>
 * 新增语言只需在此枚举添加值 + SandboxConfig 加镜像映射，零改现有代码（OCP）。
 */
public enum Language {

    PYTHON("python3", "python", ".py"),
    JAVASCRIPT("node", "javascript", ".js"),
    JAVA("java", "java", ".java");

    private final String command;
    private final String displayName;
    private final String extension;

    Language(String command, String displayName, String extension) {
        this.command = command;
        this.displayName = displayName;
        this.extension = extension;
    }

    public String getCommand() {
        return command;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getExtension() {
        return extension;
    }

    /**
     * 从字符串解析语言，不区分大小写
     *
     * @return 对应的 Language，无法识别时返回 null
     */
    public static Language fromString(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toLowerCase();
        for (Language lang : values()) {
            if (lang.name().equalsIgnoreCase(normalized)
                    || lang.displayName.equals(normalized)
                    || lang.command.equals(normalized)) {
                return lang;
            }
        }
        return null;
    }
}
