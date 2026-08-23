package com.smart.rag.evaluation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * RAG 评估系统配置
 * <p>
 * 对应 application.yml 中 app.evaluation.* 配置项（评估模块全局恒装载）。
 * <p>
 * Judge 与生成模型均复用 {@code LlmClientRegistry} 的候选路由体系，
 * 通过 app.llm.capabilities.chat.candidates 中声明的 candidate id 寻址，
 * 留空时回退到默认 chat 候选。
 */
@Component
@ConfigurationProperties(prefix = "app.evaluation")
public class EvaluationProperties {

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

    /** 指标参数配置（对齐 ragas 各指标构造参数） */
    private Metrics metrics = new Metrics();

    // ======================== 便捷方法 ========================

    /**
     * 获取 Judge 候选 ID（指向 app.llm.capabilities.chat.candidates 中的某一项；为 null/blank 时回退默认 chat 候选）
     */
    public String getJudgeModel() {
        return judge.getCandidateId();
    }

    // ======================== Getters & Setters ========================

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

    public Metrics getMetrics() {
        return metrics;
    }

    public void setMetrics(Metrics metrics) {
        this.metrics = metrics;
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
        /** 目标测试集条数（ragas 式生成；对应 Python 参照实现 eval/generate_testset.py 的 --size） */
        private int size = 50;

        /** 参与知识图谱构建的最大 chunk 数（vector_store 随机采样，超出裁剪） */
        private int maxChunks = 200;

        /**
         * 出题主模型候选 ID（问题与参考答案生成，建议用强模型）。
         * 为 null/blank 时使用默认 chat 候选。
         */
        private String synthesisModel;

        /** 向量余弦相似边阈值（摘要向量，ragas prechunked 默认 0.7） */
        private double cosineThreshold = 0.7;

        /**
         * 固定中文 persona 列表；为空时由 PersonaGenerator 自动生成
         * （对齐 ragas persona_list=None 时 generate_personas_from_kg 的默认行为）。
         */
        private List<PersonaConfig> personas = new ArrayList<>();

        /** 自动生成 persona 数量（personas 为空时生效；ragas num_personas 默认 3） */
        private int numPersonas = 3;

        /** 节点问题潜力过滤（ragas CustomNodeFilter 移植） */
        private NodeFilter nodeFilter = new NodeFilter();

        /**
         * 同时执行的测试集生成任务数。
         * <p>
         * 独立于 {@code runner.max-concurrent-runs}：生成任务是小时级长任务，
         * 与评估 run 共用并发额度时会长期占住评估的许可（max-concurrent-runs=2 时
         * 一个生成任务可占满全部额度，饿死评估 run），因此资源隔离、各自限额。
         */
        private int maxConcurrentJobs = 2;

        public int getSize() {
            return size;
        }

        public void setSize(int size) {
            this.size = size;
        }

        public int getMaxChunks() {
            return maxChunks;
        }

        public void setMaxChunks(int maxChunks) {
            this.maxChunks = maxChunks;
        }

        public String getSynthesisModel() {
            return synthesisModel;
        }

        public void setSynthesisModel(String synthesisModel) {
            this.synthesisModel = synthesisModel;
        }

        public double getCosineThreshold() {
            return cosineThreshold;
        }

        public void setCosineThreshold(double cosineThreshold) {
            this.cosineThreshold = cosineThreshold;
        }

        public List<PersonaConfig> getPersonas() {
            return personas;
        }

        public void setPersonas(List<PersonaConfig> personas) {
            this.personas = personas;
        }

        public int getNumPersonas() {
            return numPersonas;
        }

        public void setNumPersonas(int numPersonas) {
            this.numPersonas = numPersonas;
        }

        public NodeFilter getNodeFilter() {
            return nodeFilter;
        }

        public void setNodeFilter(NodeFilter nodeFilter) {
            this.nodeFilter = nodeFilter;
        }

        public int getMaxConcurrentJobs() {
            return maxConcurrentJobs;
        }

        public void setMaxConcurrentJobs(int maxConcurrentJobs) {
            this.maxConcurrentJobs = maxConcurrentJobs;
        }
    }

    /** 节点问题潜力过滤配置（ragas CustomNodeFilter min_score） */
    public static class NodeFilter {
        /** 潜力评分 ≤ minScore 的 chunk 被剔除（ragas 默认 2） */
        private int minScore = 2;

        public int getMinScore() {
            return minScore;
        }

        public void setMinScore(int minScore) {
            this.minScore = minScore;
        }
    }

    /** persona 配置项（name + 职责描述） */
    public static class PersonaConfig {
        private String name;
        private String roleDescription;

        public PersonaConfig() {
        }

        public PersonaConfig(String name, String roleDescription) {
            this.name = name;
            this.roleDescription = roleDescription;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getRoleDescription() {
            return roleDescription;
        }

        public void setRoleDescription(String roleDescription) {
            this.roleDescription = roleDescription;
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

    /** 指标参数（默认值 = ragas 0.4.3 各指标构造默认） */
    public static class Metrics {

        private AnswerCorrectness answerCorrectness = new AnswerCorrectness();

        private FactualCorrectness factualCorrectness = new FactualCorrectness();

        private NoiseSensitivity noiseSensitivity = new NoiseSensitivity();

        private AnswerRelevancy answerRelevancy = new AnswerRelevancy();

        public AnswerCorrectness getAnswerCorrectness() {
            return answerCorrectness;
        }

        public void setAnswerCorrectness(AnswerCorrectness answerCorrectness) {
            this.answerCorrectness = answerCorrectness;
        }

        public FactualCorrectness getFactualCorrectness() {
            return factualCorrectness;
        }

        public void setFactualCorrectness(FactualCorrectness factualCorrectness) {
            this.factualCorrectness = factualCorrectness;
        }

        public NoiseSensitivity getNoiseSensitivity() {
            return noiseSensitivity;
        }

        public void setNoiseSensitivity(NoiseSensitivity noiseSensitivity) {
            this.noiseSensitivity = noiseSensitivity;
        }

        public AnswerRelevancy getAnswerRelevancy() {
            return answerRelevancy;
        }

        public void setAnswerRelevancy(AnswerRelevancy answerRelevancy) {
            this.answerRelevancy = answerRelevancy;
        }

        /** AnswerCorrectness：事实性 F-beta 与语义相似度加权（ragas weights=[0.75,0.25]、beta=1.0） */
        public static class AnswerCorrectness {
            /** 事实性分量权重 */
            private double factualityWeight = 0.75;

            /** 语义相似度分量权重 */
            private double similarityWeight = 0.25;

            /** F-beta 的 beta（&lt;1 偏精度重罚幻觉，&gt;1 偏召回重罚遗漏） */
            private double beta = 1.0;

            public double getFactualityWeight() {
                return factualityWeight;
            }

            public void setFactualityWeight(double factualityWeight) {
                this.factualityWeight = factualityWeight;
            }

            public double getSimilarityWeight() {
                return similarityWeight;
            }

            public void setSimilarityWeight(double similarityWeight) {
                this.similarityWeight = similarityWeight;
            }

            public double getBeta() {
                return beta;
            }

            public void setBeta(double beta) {
                this.beta = beta;
            }
        }

        /** FactualCorrectness：mode 与 beta（ragas 默认 f1 / 1.0） */
        public static class FactualCorrectness {
            /** precision | recall | f1 */
            private String mode = "f1";

            private double beta = 1.0;

            public String getMode() {
                return mode;
            }

            public void setMode(String mode) {
                this.mode = mode;
            }

            public double getBeta() {
                return beta;
            }

            public void setBeta(double beta) {
                this.beta = beta;
            }
        }

        /** NoiseSensitivity：relevant（ragas 默认）| irrelevant */
        public static class NoiseSensitivity {
            private String mode = "relevant";

            public String getMode() {
                return mode;
            }

            public void setMode(String mode) {
                this.mode = mode;
            }
        }

        /** AnswerRelevancy：反向问题独立采样次数（ragas strictness 默认 3） */
        public static class AnswerRelevancy {
            private int strictness = 3;

            public int getStrictness() {
                return strictness;
            }

            public void setStrictness(int strictness) {
                this.strictness = strictness;
            }
        }
    }
}
