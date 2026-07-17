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

        /** 并发评估数（避免打爆 API） */
        private int concurrency = 1;

        /** 单条评估超时（秒） */
        private int timeoutSeconds = 300;

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

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }
    }
}
