package com.smart.rag.chat.dto;

/**
 * 取消生成原因枚举（design §6.1）。
 * <p>
 * 作为 {@link CancelStreamRequest#reason()} 的类型，Jackson 反序列化自带校验——
 * 非法值会被拒绝，无需手工 String 校验。仅用于打点维度（{@code chat.stream.cancelled{reason}}）
 * 和 {@code event:canceled} 帧 data，不影响取消行为本身。
 */
public enum CancelReason {

    /** 用户主动点击「停止生成」 */
    USER_ABORT,

    /** 用户离开页面（前端 beforeunload 触发） */
    NAVIGATE_AWAY,

    /** 用户切换会话 */
    SESSION_SWITCH
}
