package com.smart.rag.evaluation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

/**
 * RAG 评估系统配置
 * <p>
 * 对应 application-evaluation.yml 中 app.evaluation.* 配置项。
 * 仅在 evaluation profile 激活时生效。
 * <p>
 * Judge 与生成模型均复用 {@code LlmClientRegistry} 的候选路由体系，
 * 通过 app.llm.capabilities.chat.candidates 中声明的 candidate id 寻址，
 * 留空时回退到默认 chat 候选。
 */
@Component
@Profile("evaluation")
@ConfigurationProperties(prefix = "app.evaluation")
public class EvaluationProperties {

    /** 是否启用评估模块（需同时激活 evaluation profile） */
    private boolean enabled = false;

    /** 生成模型候选 ID（对应 app.llm.capabilities.chat.candidates[].id，用于 Pipeline 答案生成） */
    private String generationModel = "deepseek-v4-flash";

    /** 评估使用的测试用户 ID（需确保该用户有足够数据） */
    private Long testUserId = 1L;

    /** Judge 模型独立配置 */
    private Judge judge = new Judge();

    /** 数据集相关配置 */
    private Dataset dataset = new Dataset();

    /** 运行器相关配置 */
    private Runner runner = new Runner();

    // ======================== 便捷方法 ========================

    /**
     * 获取 Judge 候选 ID（指向 app.llm.capabilities.chat.candidates 中的某一项；为 null/blank 时回退默认 chat 候选）
     */
    public String getJudgeModel() {
        return judge.getCandidateId();
    }

    // ======================== Getters & Setters ========================

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getGenerationModel() {
        return generationModel;
    }

    public void setGenerationModel(String generationModel) {
        this.generationModel = generationModel;
    }

    public Long getTestUserId() {
        return testUserId;
    }

    public void setTestUserId(Long testUserId) {
        this.testUserId = testUserId;
    }

    public Judge getJudge() {
        return judge;
    }

    public void setJudge(Judge judge) {
        this.judge = judge;
    }

    public Dataset getDataset() {
        return dataset;
    }

    public void setDataset(Dataset dataset) {
        this.dataset = dataset;
    }

    public Runner getRunner() {
        return runner;
    }

    public void setRunner(Runner runner) {
        this.runner = runner;
    }

    // ======================== 嵌套配置类 ========================

    /**
     * Judge 候选配置
     * <p>
     * 复用 Provider 路由体系（LlmClientRegistry），通过 candidate id 寻址。
     * 留空时回退到默认 chat 候选。
     */
    public static class Judge {
        /**
         * 候选 ID，对应 app.llm.capabilities.chat.candidates[].id。
         * 为 null/blank 时使用默认 chat 候选。
         */
        private String candidateId;

        public String getCandidateId() {
            return candidateId;
        }

        public void setCandidateId(String candidateId) {
            this.candidateId = candidateId;
        }
    }

    public static class Dataset {
        /** LLM 自动生成时的采样 chunk 数 */
        private int sampleSize = 50;

        /** 每个 chunk 生成的问题数 */
        private int questionsPerChunk = 2;

        public int getSampleSize() {
            return sampleSize;
        }

        public void setSampleSize(int sampleSize) {
            this.sampleSize = sampleSize;
        }

        public int getQuestionsPerChunk() {
            return questionsPerChunk;
        }

        public void setQuestionsPerChunk(int questionsPerChunk) {
            this.questionsPerChunk = questionsPerChunk;
        }
    }

    public static class Runner {
        /** 默认评估的 topK */
        private int defaultK = 10;

        /** 单次 run 内 item 级并发数（1=串行，>1 用 ScopedTasks 并发 fork） */
        private int concurrency = 1;

        /**
         * 信号量获取超时（秒）。
         * <p>
         * 并发 run 数达 {@link #maxConcurrentRuns} 上限时，新 run 等待获取信号量的最长时间。
         * 超时后该 run 标记为 FAILED（快速失败，客户端可重试）。
         */
        private int acquireTimeoutSeconds = 60;

        /**
         * 单条 item 评测的 ScopedTasks 作用域超时（秒）。
         * <p>
         * 用于 {@code ScopeOptions.defaultTimeout}——单个 item 的所有 fork 必须在此时间内完成。
         * 注意：真正的 LLM 调用级超时需在 llm 模块配置（retry/probe），此处只约束 fork 作用域。
         */
        private int itemTimeoutSeconds = 300;

        /**
         * run 被判定为 stale 的阈值（分钟）。
         * <p>
         * {@code EvaluationRunSweeper} 定期扫描，超过此阈值仍处于 running 的 run 被标记 FAILED
         * （应对 JVM 崩溃导致的 stuck-running 记录）。需根据数据集大小调整。
         */
        private int staleRunMinutes = 30;

        /**
         * 同时执行的最大 run 数（背压，防打爆下游 LLM API）。
         * <p>
         * 虚拟线程本身 unlimited（JEP 444 不池化），并发上限在更高层用 Semaphore 限制。
         * 超过此数的 run 提交后会等待 acquire 超时（{@link #acquireTimeoutSeconds}）后标记 FAILED。
         */
        private int maxConcurrentRuns = 2;

        public int getDefaultK() {
            return defaultK;
        }

        public void setDefaultK(int defaultK) {
            this.defaultK = defaultK;
        }

        public int getConcurrency() {
            return concurrency;
        }

        public void setConcurrency(int concurrency) {
            this.concurrency = concurrency;
        }

        public int getAcquireTimeoutSeconds() {
            return acquireTimeoutSeconds;
        }

        public void setAcquireTimeoutSeconds(int acquireTimeoutSeconds) {
            this.acquireTimeoutSeconds = acquireTimeoutSeconds;
        }

        public int getItemTimeoutSeconds() {
            return itemTimeoutSeconds;
        }

        public void setItemTimeoutSeconds(int itemTimeoutSeconds) {
            this.itemTimeoutSeconds = itemTimeoutSeconds;
        }

        public int getStaleRunMinutes() {
            return staleRunMinutes;
        }

        public void setStaleRunMinutes(int staleRunMinutes) {
            this.staleRunMinutes = staleRunMinutes;
        }

        public int getMaxConcurrentRuns() {
            return maxConcurrentRuns;
        }

        public void setMaxConcurrentRuns(int maxConcurrentRuns) {
            this.maxConcurrentRuns = maxConcurrentRuns;
        }
    }
}
