package com.smart.rag.infrastructure.exception.errorcode;

/**
 * 客户端错误码 (A类, 100001–199999)
 * <p>
 * 用户提交参数错误、权限不足、重复提交、内容过滤等客户端引起的错误。
 */
public enum ClientErrorCode implements IErrorCode {

    // ==================== 通用 100001–100999 ====================
    BAD_REQUEST(100001, "请求参数错误"),
    VALIDATION_ERROR(100002, "参数校验失败"),
    UNAUTHORIZED(100003, "未认证"),
    FORBIDDEN(100004, "权限不足"),
    RATE_LIMITED(100005, "请求过于频繁"),
    CONTENT_FILTERED(100006, "内容包含敏感词"),
    CONFLICT(100013, "资源已被修改，请刷新重试"),
    OPTIMISTIC_LOCK_CONFLICT(100014, "资源版本冲突，请刷新重试"),

    // ==================== 认证 101001–101999 ====================
    CAPTCHA_PARAM_MISSING(101001, "验证码参数缺失"),
    CAPTCHA_FORMAT_ERROR(101002, "验证码格式错误"),
    CAPTCHA_INVALID(101003, "验证码错误或已过期"),
    CAPTCHA_RATE_LIMIT(101004, "验证码请求过于频繁"),
    LOGIN_FAILED(101005, "用户名或密码错误"),
    TOKEN_EXPIRED(101006, "令牌已过期"),
    TOKEN_INVALID(101007, "令牌无效"),
    TOKEN_REFRESH_INVALID(101008, "无效的刷新令牌"),
    TOKEN_NOT_REFRESH(101009, "不是刷新令牌"),
    TOKEN_REFRESH_EXPIRED(101010, "刷新令牌已过期或已吊销"),
    USER_STATUS_ABNORMAL(101011, "用户状态异常"),
    USER_DISABLED(101012, "账号已被禁用"),
    REFRESH_TOKEN_MISSING(101013, "缺少刷新令牌"),

    // ==================== 用户冲突 102001–102999 ====================
    USERNAME_EXISTS(102001, "用户名已存在"),
    EMAIL_EXISTS(102002, "邮箱已被注册"),
    EMAIL_USED(102003, "邮箱已被使用"),
    USER_STATUS_INVALID(102004, "无效的用户状态，仅支持 0(禁用) 和 1(启用)"),
    OLD_PASSWORD_ERROR(102005, "旧密码错误"),
    PASSWORD_RULE_ERROR(102006, "密码需 8~72 位，至少包含大写、小写、数字、特殊字符中的 3 种"),
    ROLE_NAME_EXISTS(102007, "角色名已存在"),
    PERMISSION_NAME_EXISTS(102008, "权限名称已存在"),
    PERMISSION_KEY_EXISTS(102009, "权限标识已存在"),

    // ==================== 聊天客户端 103001–103999 ====================
    MODEL_EMPTY(103001, "模型不能为空"),
    USAGE_PARAM_MISSING(103002, "请指定 model 或 conversation 参数"),
    UNSUPPORTED_OPERATION(103003, "当前版本不支持此操作"),
    MODEL_CAPABILITY_NOT_CHAT(103004, "所选模型不支持对话，请选择 CHAT 能力模型"),

    // ==================== RAG 上传校验 104001–104999 ====================
    UPLOAD_FILE_EMPTY(104001, "上传文件不能为空"),
    UPLOAD_LIST_EMPTY(104002, "上传文件列表不能为空"),
    UPLOAD_FILE_TOO_LARGE(104003, "文件大小超出限制"),
    UPLOAD_MIME_UNSUPPORTED(104004, "不支持的文件类型"),
    UPLOAD_FAILED(104005, "上传失败"),
    UPLOAD_CHUNK_CHECKSUM_MISMATCH(104006, "分片校验失败，请重传"),
    UPLOAD_FILE_CHECKSUM_MISMATCH(104007, "文件校验失败"),
    UPLOAD_INCOMPLETE(104008, "文件未上传完整"),
    DOCUMENT_PREVIEW_UNSUPPORTED(104009, "该文件类型不支持在线预览"),
    DOCUMENT_PREVIEW_TOO_LARGE(104010, "文件超出预览大小限制"),

    // ==================== 团队客户端 105001–105999 ====================
    TEAM_NAME_DUPLICATE(105001, "团队名称已存在"),
    ALREADY_TEAM_MEMBER(105002, "已经是团队成员"),
    CREATOR_CANNOT_LEAVE(105003, "创建者不能退出团队"),
    CANNOT_CHANGE_CREATOR_ROLE(105004, "不能修改创建者角色"),
    UPLOAD_QUOTA_EXCEEDED(105005, "上传文件超出团队额度"),
    UPLOAD_LIMIT_OUT_OF_RANGE(105006, "上传额度设置超出范围"),
    CANNOT_REMOVE_SELF(105007, "不能移除自己，请使用退出功能"),
    CANNOT_REMOVE_CREATOR(105008, "不能移除团队创建者"),
    CANNOT_ASSIGN_CREATOR(105009, "不能指定为创建者角色"),
    CANNOT_CHANGE_OWN_ROLE(105010, "不能修改自己的角色"),
    ADMIN_CANNOT_REMOVE_ADMIN(105011, "管理员不能移除其他管理员"),
    TEAM_LIMIT_EXCEEDED(105012, "用户团队数超限"),
    TEAM_MEMBER_LIMIT_EXCEEDED(105013, "团队成员数超限"),
    ;

    private final int code;
    private final String message;

    ClientErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override
    public int getCode() { return code; }

    @Override
    public String getMessage() { return message; }
}
