package com.smart.rag.infrastructure.exception.errorcode;

/**
 * 服务端错误码 (B类, 200001–299999)
 * <p>
 * 业务逻辑不符合预期、数据不存在、状态异常等服务端内部错误。
 */
public enum ServiceErrorCode implements IErrorCode {

    // ==================== 通用 200001–200999 ====================
    NOT_FOUND(200001, "资源不存在"),
    INTERNAL_ERROR(200002, "服务内部错误，请稍后重试"),
    SERIALIZATION_FAILED(200003, "序列化失败"),

    // ==================== 用户/角色/权限 201001–201999 ====================
    USER_NOT_FOUND(201001, "用户不存在"),
    ROLE_NOT_FOUND(201002, "角色不存在"),
    PERMISSION_NOT_FOUND(201003, "权限不存在"),

    // ==================== 会话 202001–202999 ====================
    CONVERSATION_NOT_FOUND(202001, "会话不存在"),
    CONVERSATION_ACCESS_DENIED(202002, "无权访问该会话"),

    // ==================== 聊天 203001–203999 ====================
    MODEL_NOT_FOUND(203001, "模型不存在"),

    // ==================== RAG 204001–204999 ====================
    DOCUMENT_NOT_FOUND(204001, "文档不存在"),
    DOCUMENT_OWNERSHIP_DENIED(204002, "无权操作该文档"),
    ETL_NO_RESULT(204003, "ETL 处理无结果"),
    ETL_FAILED(204004, "文档处理失败"),
    UPLOAD_SESSION_NOT_FOUND(204005, "上传会话不存在或已过期"),

    // ==================== 团队 205001–205999 ====================
    TEAM_NOT_FOUND(205001, "团队不存在"),
    NOT_TEAM_MEMBER(205002, "不是团队成员"),
    NOT_TEAM_ADMIN(205003, "不是团队管理员/创建者"),
    NOT_TEAM_CREATOR(205004, "不是团队创建者"),
    APPROVAL_NOT_FOUND(205005, "审批记录不存在"),
    APPROVAL_ALREADY_PROCESSED(205006, "审批已处理"),
    NO_PERMISSION_DELETE_TEAM_DOC(205007, "无权删除团队文档"),
    ;

    private final int code;
    private final String message;

    ServiceErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override
    public int getCode() { return code; }

    @Override
    public String getMessage() { return message; }
}
