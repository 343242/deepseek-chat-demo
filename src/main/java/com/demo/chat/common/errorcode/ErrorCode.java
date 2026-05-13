package com.demo.chat.common.errorcode;

/**
 * 统一错误码枚举
 * <p>
 * 编码规则：
 * <ul>
 *   <li>通用 0xxxx：框架级错误</li>
 *   <li>认证 10xxx：登录/注册/Token/验证码</li>
 *   <li>用户 20xxx：用户/角色/权限管理</li>
 *   <li>会话 30xxx：会话管理</li>
 *   <li>聊天 40xxx：聊天/模型</li>
 *   <li>RAG 50xxx：文档/检索</li>
 * </ul>
 */
public enum ErrorCode {

    // ==================== 通用 ====================
    SUCCESS(0, "ok"),
    BAD_REQUEST(40000, "请求参数错误"),
    VALIDATION_ERROR(40001, "参数校验失败"),
    UNAUTHORIZED(40100, "未认证"),
    FORBIDDEN(40300, "权限不足"),
    NOT_FOUND(40400, "资源不存在"),
    RATE_LIMITED(42900, "请求过于频繁"),
    INTERNAL_ERROR(50000, "服务内部错误，请稍后重试"),

    // ==================== 认证 10xxx ====================
    CAPTCHA_PARAM_MISSING(10001, "验证码参数缺失"),
    CAPTCHA_FORMAT_ERROR(10002, "验证码格式错误"),
    CAPTCHA_INVALID(10003, "验证码错误或已过期"),
    CAPTCHA_RATE_LIMIT(10004, "验证码请求过于频繁"),
    LOGIN_FAILED(10005, "用户名或密码错误"),
    TOKEN_EXPIRED(10006, "令牌已过期"),
    TOKEN_INVALID(10007, "令牌无效"),
    TOKEN_REFRESH_INVALID(10008, "无效的刷新令牌"),
    TOKEN_NOT_REFRESH(10009, "不是刷新令牌"),
    TOKEN_REFRESH_EXPIRED(10010, "刷新令牌已过期或已吊销"),
    USER_STATUS_ABNORMAL(10011, "用户状态异常"),
    USER_DISABLED(10012, "账号已被禁用"),
    REFRESH_TOKEN_MISSING(10013, "缺少刷新令牌"),

    // ==================== 用户 20xxx ====================
    USERNAME_EXISTS(20001, "用户名已存在"),
    EMAIL_EXISTS(20002, "邮箱已被注册"),
    EMAIL_USED(20003, "邮箱已被使用"),
    USER_NOT_FOUND(20004, "用户不存在"),
    USER_STATUS_INVALID(20005, "无效的用户状态，仅支持 0(禁用) 和 1(启用)"),
    OLD_PASSWORD_ERROR(20006, "旧密码错误"),
    PASSWORD_RULE_ERROR(20007, "密码需 8~72 位，至少包含大写、小写、数字、特殊字符中的 3 种"),
    ROLE_NOT_FOUND(20008, "角色不存在"),
    ROLE_NAME_EXISTS(20009, "角色名已存在"),
    PERMISSION_NOT_FOUND(20010, "权限不存在"),
    PERMISSION_NAME_EXISTS(20011, "权限名称已存在"),
    PERMISSION_KEY_EXISTS(20012, "权限标识已存在"),

    // ==================== 会话 30xxx ====================
    CONVERSATION_NOT_FOUND(30001, "会话不存在"),
    CONVERSATION_ACCESS_DENIED(30002, "无权访问该会话"),

    // ==================== 聊天 40xxx ====================
    MODEL_EMPTY(40001, "模型不能为空"),
    MODEL_NOT_FOUND(40002, "模型不存在"),
    PROVIDER_NOT_FOUND(40003, "厂商未配置"),
    CONTENT_FILTERED(40004, "内容包含敏感词"),
    USAGE_PARAM_MISSING(40005, "请指定 model 或 conversation 参数"),

    // ==================== RAG 50xxx ====================
    UPLOAD_FILE_EMPTY(50001, "上传文件不能为空"),
    UPLOAD_LIST_EMPTY(50002, "上传文件列表不能为空"),
    UPLOAD_FILE_TOO_LARGE(50003, "文件大小超出限制"),
    UPLOAD_MIME_UNSUPPORTED(50004, "不支持的文件类型"),
    DOCUMENT_NOT_FOUND(50005, "文档不存在"),
    DOCUMENT_OWNERSHIP_DENIED(50006, "无权操作该文档"),
    ETL_NO_RESULT(50007, "ETL 处理无结果"),
    ETL_FAILED(50008, "文档处理失败"),

    // ---- 分片上传 ----
    UPLOAD_FAILED(50009, "上传失败"),
    UPLOAD_CHUNK_MD5_MISMATCH(50010, "分片校验失败，请重传"),
    UPLOAD_SESSION_NOT_FOUND(50011, "上传会话不存在或已过期"),
    UPLOAD_FILE_MD5_MISMATCH(50012, "文件校验失败"),
    UPLOAD_INCOMPLETE(50013, "文件未上传完整"),

    // ==================== 团队 55xxx ====================
    TEAM_NOT_FOUND(55001, "团队不存在"),
    TEAM_NAME_DUPLICATE(55002, "团队名称已存在"),
    NOT_TEAM_MEMBER(55003, "不是团队成员"),
    NOT_TEAM_ADMIN(55004, "不是团队管理员/创建者"),
    NOT_TEAM_CREATOR(55005, "不是团队创建者"),
    ALREADY_TEAM_MEMBER(55006, "已经是团队成员"),
    CREATOR_CANNOT_LEAVE(55007, "创建者不能退出团队"),
    CANNOT_CHANGE_CREATOR_ROLE(55008, "不能修改创建者角色"),
    UPLOAD_QUOTA_EXCEEDED(55009, "上传文件超出团队额度"),
    UPLOAD_LIMIT_OUT_OF_RANGE(55010, "上传额度设置超出范围"),
    APPROVAL_NOT_FOUND(55011, "审批记录不存在"),
    APPROVAL_ALREADY_PROCESSED(55012, "审批已处理"),
    NO_PERMISSION_DELETE_TEAM_DOC(55013, "无权删除团队文档"),
    TEAM_LIMIT_EXCEEDED(55014, "用户团队数超限"),
    TEAM_MEMBER_LIMIT_EXCEEDED(55015, "团队成员数超限"),
    ;

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
