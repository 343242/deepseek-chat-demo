package com.smart.rag.infrastructure.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注需要审计的管理员操作方法（自动经 {@link AdminAuditAspect} 切入记录到 {@code admin_audit_log}）。
 * <p>
 * <b>自调用约束（v4 C7）</b>：被本注解标注的方法之间<b>禁止</b>直接相互调用（{@code this.xxx()}），
 * 否则内层审计不生效——Spring AOP 基于代理，同类内调用绕过代理。如需共用逻辑，抽到 private 方法或单独 Bean。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AdminAudit {

    /** 资源类型，e.g. "mcp_server", "mcp_tool", "llm_model", "rag_pipeline" */
    String resourceType();

    /** 操作类型，e.g. "create", "update", "delete", "enable", "disable", "reconnect" */
    String action();

    /**
     * 资源 ID SpEL 表达式，相对于方法入参。
     * e.g. {@code "#request.serverId"}, {@code "#id"}, {@code "#result.id"}
     * 留空则不记录 resourceId。
     */
    String resourceIdExpr() default "";

    /** 是否记录请求 payload（默认 true） */
    boolean logRequest() default true;

    /** 敏感字段路径（自动脱敏为 "***"），e.g. {"bearerToken", "password"} */
    String[] sensitiveFields() default {};
}
