package com.smart.rag.infrastructure.fallback;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 动态模型选择器
 * <p>
 * 基于候选列表 + 熔断状态构建降级链。
 * 支持按请求模型优先、按优先级排序、按思考能力过滤。
 * <p>
 * 非 {@code @Component}，由 {@link FallbackAutoConfiguration} 条件化创建。
 */
public class DynamicModelSelector implements FallbackChainProvider {

    private static final Logger log = LoggerFactory.getLogger(DynamicModelSelector.class);

    private final ChatCandidatesProperties props;
    private final ModelCircuitBreakerRegistry breakers;

    public DynamicModelSelector(ChatCandidatesProperties props,
                                ModelCircuitBreakerRegistry breakers) {
        this.props = props;
        this.breakers = breakers;
    }

    @Override
    public List<String> resolve(String requestedModel) {
        return resolveInternal(requestedModel, false);
    }

    @Override
    public List<String> resolve(String requestedModel, boolean requiresThinking) {
        return resolveInternal(requestedModel, requiresThinking);
    }

    private List<String> resolveInternal(String requestedModel, boolean requiresThinking) {
        Comparator<ModelCandidate> byPriority = Comparator
                .comparingInt(ModelCandidate::priority)
                .thenComparing(ModelCandidate::id);

        List<ModelCandidate> candidates = props.list().stream()
                .filter(ModelCandidate::enabled)
                .filter(c -> !requiresThinking || c.supportsThinking())
                .sorted(byPriority)
                .collect(Collectors.toList());

        // 请求模型优先：即使不支持 thinking 也放在第一位（保留显式用户选择）
        if (requestedModel != null && !requestedModel.isBlank()) {
            candidates.removeIf(c -> c.compositeId().equals(requestedModel));

            // 过滤掉熔断器 OPEN 的模型
            List<String> result = new ArrayList<>();
            result.add(requestedModel);

            for (ModelCandidate c : candidates) {
                if (breakers.isCallAllowed(c.compositeId())) {
                    result.add(c.compositeId());
                }
            }

            if (log.isDebugEnabled()) {
                log.debug("Dynamic chain for '{}' (thinking={}): {}", requestedModel, requiresThinking, result);
            }
            return result;
        }

        // 无请求模型：按优先级返回所有可用候选
        return candidates.stream()
                .filter(c -> breakers.isCallAllowed(c.compositeId()))
                .map(ModelCandidate::compositeId)
                .collect(Collectors.toList());
    }
}
