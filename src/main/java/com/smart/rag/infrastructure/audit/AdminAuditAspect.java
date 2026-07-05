package com.smart.rag.infrastructure.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.rag.infrastructure.audit.entity.AdminAuditLog;
import com.smart.rag.infrastructure.exception.AbstractException;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.annotation.Nullable;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * {@link AdminAudit} 注解 AOP 切面——自动捕获管理员操作写入 {@code admin_audit_log}。
 * <p>
 * 捕获维度：操作者（SecurityContext）/ IP+UA（RequestContext）/ 耗时 / 成功/失败 / 错误码 / SpEL 资源 ID /
 * 敏感字段脱敏 payload（Jackson 序列化后字段名替换为 "***"）。
 * <p>
 * 异步写入（{@link AdminAuditAsyncWriter}），不阻塞业务响应。
 */
@Aspect
@Component
public class AdminAuditAspect {

    private static final Logger log = LoggerFactory.getLogger(AdminAuditAspect.class);

    private final AdminAuditAsyncWriter writer;
    private final ObjectMapper objectMapper;
    private final ExpressionParser parser = new SpelExpressionParser();
    private final ParameterNameDiscoverer paramNameDiscoverer = new DefaultParameterNameDiscoverer();

    @Autowired
    public AdminAuditAspect(AdminAuditAsyncWriter writer, ObjectMapper objectMapper) {
        this.writer = writer;
        this.objectMapper = objectMapper;
    }

    @Around("@annotation(adminAudit)")
    public Object audit(ProceedingJoinPoint pjp, AdminAudit adminAudit) throws Throwable {
        long start = System.currentTimeMillis();
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        Method method = sig.getMethod();

        String resourceId = evalResourceId(adminAudit, method, pjp.getArgs());
        OperatorInfo operator = OperatorInfo.fromSecurityContext();
        RequestMeta requestMeta = RequestMeta.fromRequestContext();

        boolean success = false;
        String errorCode = null;
        String errorMessage = null;
        try {
            Object result = pjp.proceed();
            success = true;
            return result;
        } catch (AbstractException e) {
            errorCode = e.getErrorCode().getClass().getSimpleName() + "#" + e.getErrorCode().getCode();
            errorMessage = e.getUserMessage();
            throw e;
        } catch (Exception e) {
            errorCode = "INTERNAL_ERROR";
            errorMessage = e.getMessage();
            throw e;
        } finally {
            long durationMs = System.currentTimeMillis() - start;
            String payload = adminAudit.logRequest()
                    ? sanitizePayload(pjp.getArgs(), adminAudit.sensitiveFields(), method)
                    : null;
            AdminAuditLog entry = new AdminAuditLog();
            entry.setOperatorId(operator.userId());
            entry.setOperatorName(operator.username());
            entry.setOperatorRole(operator.role());
            entry.setResourceType(adminAudit.resourceType());
            entry.setResourceId(resourceId);
            entry.setAction(adminAudit.action());
            entry.setRequestPayload(payload);
            entry.setResultStatus(success ? "SUCCESS" : "FAILURE");
            entry.setErrorCode(errorCode);
            entry.setErrorMessage(errorMessage);
            entry.setIpAddress(requestMeta.ip());
            entry.setUserAgent(requestMeta.userAgent());
            entry.setDurationMs((int) durationMs);
            entry.setCreatedAt(LocalDateTime.now());
            try {
                writer.writeAsync(entry);
            } catch (Exception ex) {
                log.error("admin audit writeAsync submit failed", ex);
            }
        }
    }

    private String evalResourceId(AdminAudit ann, Method method, Object[] args) {
        if (ann.resourceIdExpr() == null || ann.resourceIdExpr().isBlank()) {
            return null;
        }
        try {
            EvaluationContext ctx = buildSpelContext(method, args);
            Expression exp = parser.parseExpression(ann.resourceIdExpr());
            Object value = exp.getValue(ctx);
            return value == null ? null : String.valueOf(value);
        } catch (Exception e) {
            log.warn("AdminAudit resourceIdExpr eval failed: expr={} err={}", ann.resourceIdExpr(), e.getMessage());
            return null;
        }
    }

    private EvaluationContext buildSpelContext(Method method, Object[] args) {
        StandardEvaluationContext ctx = new StandardEvaluationContext();
        String[] paramNames = paramNameDiscoverer.getParameterNames(method);
        if (paramNames != null) {
            for (int i = 0; i < paramNames.length && i < args.length; i++) {
                ctx.setVariable(paramNames[i], args[i]);
            }
        }
        return ctx;
    }

    private String sanitizePayload(Object[] args, String[] sensitiveFields, Method method) {
        if (args == null || args.length == 0) {
            return null;
        }
        try {
            String[] paramNames = paramNameDiscoverer.getParameterNames(method);
            Map<String, Object> payload = new HashMap<>();
            for (int i = 0; i < args.length; i++) {
                String name = (paramNames != null && i < paramNames.length) ? paramNames[i] : "arg" + i;
                payload.put(name, args[i]);
            }
            String json = objectMapper.writeValueAsString(payload);
            for (String field : sensitiveFields) {
                json = maskField(json, field);
            }
            return json;
        } catch (Exception e) {
            log.warn("sanitizePayload serialize failed: {}", e.getMessage());
            return null;
        }
    }

    /** 简易字段脱敏：匹配 "fieldName":"value" 模式（值含引号转义也能正确处理双引号字面值） */
    private String maskField(String json, String fieldName) {
        if (json == null || json.isEmpty() || fieldName == null || fieldName.isEmpty()) {
            return json;
        }
        return json.replaceAll("(\""
                + fieldName.replaceAll("([\\[\\]{}()*+?.\\\\^$|])", "\\\\$1")
                + "\"\\s*:\\s*)\"(?:[^\"\\\\]|\\\\.)*\"", "$1\"***\"");
    }

    // ===== Operator / Request 上下文提取 =====

    record OperatorInfo(@Nullable Long userId, @Nullable String username, @Nullable String role) {
        static OperatorInfo fromSecurityContext() {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated() || auth.getPrincipal() == null) {
                return new OperatorInfo(null, null, "ANONYMOUS");
            }
            Object principal = auth.getPrincipal();
            Long uid = principal instanceof Long l ? l : null;
            String name = auth.getName();
            String role = auth.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                    .findFirst().orElse("USER");
            return new OperatorInfo(uid, name, role);
        }
    }

    record RequestMeta(@Nullable String ip, @Nullable String userAgent) {
        static RequestMeta fromRequestContext() {
            try {
                RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
                if (!(attrs instanceof ServletRequestAttributes sra)) {
                    return new RequestMeta(null, null);
                }
                HttpServletRequest req = sra.getRequest();
                return new RequestMeta(resolveClientIp(req), req.getHeader("User-Agent"));
            } catch (Exception e) {
                return new RequestMeta(null, null);
            }
        }

        private static String resolveClientIp(HttpServletRequest req) {
            String forwarded = req.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                int comma = forwarded.indexOf(',');
                return comma > 0 ? forwarded.substring(0, comma).trim() : forwarded.trim();
            }
            String realIp = req.getHeader("X-Real-IP");
            if (realIp != null && !realIp.isBlank()) {
                return realIp.trim();
            }
            return req.getRemoteAddr();
        }
    }
}
