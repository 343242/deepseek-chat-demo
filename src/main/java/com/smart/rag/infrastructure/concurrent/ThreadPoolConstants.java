package com.smart.rag.infrastructure.concurrent;

/**
 * 线程池全局常量 — 基于 CPU 核心数动态计算线程池参数。
 * <p>
 * 归属于 infrastructure.concurrent：纯计算工具，无 Spring/业务依赖，
 * 供 infrastructure 内部及上层业务模块（config/rag 等）共享使用。
 * <p>
 * 遵循 Brian Goetz《Java Concurrency in Practice》的线程数公式：
 * <ul>
 *   <li>CPU 密集型：N_cpu + 1</li>
 *   <li>IO 密集型：N_cpu &lt;&lt; 1（等待时间占比高时位移量可增大）</li>
 * </ul>
 * 所有线程池的 Java 默认值均由此类派生，确保不同硬件规格下自动适配。
 * YAML 配置可覆盖这些默认值。
 */
public final class ThreadPoolConstants {

    /** CPU 核心数 */
    public static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();

    private ThreadPoolConstants() {}

    // ---- CPU-bound (compute-heavy: text chunking, document parsing) ----

    /** CPU 密集型核心线程数 = CPU_COUNT + 1 */
    public static int cpuCore() { return CPU_COUNT + 1; }

    /** CPU 密集型最大线程数 = CPU_COUNT << 1 */
    public static int cpuMax() { return CPU_COUNT << 1; }

    // ---- IO-bound (network/disk wait: MinIO, Embedding API, PGvector, SSE) ----

    /** IO 密集型核心线程数 = CPU_COUNT << 1 */
    public static int ioCore() { return CPU_COUNT << 1; }

    /** IO 密集型最大线程数 = CPU_COUNT << 2 */
    public static int ioMax() { return CPU_COUNT << 2; }

    // ---- Light/utility (rerank, merge, lightweight concurrent work) ----

    /** 轻量级核心线程数 = max(2, CPU_COUNT) */
    public static int lightCore() { return Math.max(2, CPU_COUNT); }

    /** 轻量级最大线程数 = CPU_COUNT << 1 */
    public static int lightMax() { return CPU_COUNT << 1; }
}
