package com.demo.chat.chat.context;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 默认策略约束解析器
 * <p>
 * 基于角色生成回答约束文本。
 * <p>
 * <b>v1 硬编码版本</b>：角色映射写在 Java 代码中。
 * v2 将改为配置驱动（yml 或数据库映射），加新约束不改代码。
 */
@Component
public class DefaultPolicyConstraintResolver implements PolicyConstraintResolver {

    @Override
    public PolicyContext resolve(UserContext user, boolean ragEnabled) {
        List<String> constraints = new ArrayList<>();

        if (user == null) {
            // 降级：用户画像不可用时，不生成约束
            return new PolicyContext(constraints, false);
        }

        // 基于角色生成约束
        if (user.roles().contains("ADMIN")) {
            constraints.add("你是管理员，可以访问所有信息");
        } else {
            constraints.add("仅基于用户有权访问的文档回答");
        }

        // RAG 相关约束
        if (ragEnabled) {
            constraints.add("优先使用检索到的知识库内容回答");
        }

        return new PolicyContext(constraints, false);
    }
}
