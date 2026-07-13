package com.smart.rag.mcp.admin.service;

import com.smart.rag.mcp.admin.mapper.McpServerConfigMapper;
import com.smart.rag.mcp.runtime.McpConnectionRecoveryScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Startup recovery: scans DB for due enabled connections and submits them to the reconciler.
 * <p>
 * No YAML import — PostgreSQL is the sole connection source.
 * An empty DB starts successfully with an empty MCP registry.
 */
@Component
public class McpBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(McpBootstrapRunner.class);

    private final McpServerConfigMapper serverConfigMapper;
    private final McpConnectionRecoveryScheduler scheduler;

    public McpBootstrapRunner(McpServerConfigMapper serverConfigMapper,
                              McpConnectionRecoveryScheduler scheduler) {
        this.serverConfigMapper = serverConfigMapper;
        this.scheduler = scheduler;
    }

    @Override
    public void run(ApplicationArguments args) {
        long count = serverConfigMapper.selectCount(null);
        log.info("MCP startup recovery: {} configured server(s)", count);
        if (count > 0) {
            scheduler.scan();
        }
    }
}
