# Hermes Agent 监控栈 Review 文档

> 审查时间: 2026-05-17
> 审查范围: Prometheus + Grafana 监控基础设施
> 状态: 待用户确认后实施

---

## 1. 当前架构概览

```
+-------------------+       +---------------+       +-------------------+
|  Hermes Agent     |       |   Node        |       |  Prometheus       |
|  :8084/metrics    | ----> |   Exporter    | ----> |  :9090            |
|  (Python runtime) |       |   :9100       |       |  7天存储           |
+-------------------+       +---------------+       +-------------------+
                                                             |
                                                             v
                                                    +-------------------+
                                                    |   Grafana         |
                                                    |   :3000           |
                                                    |   admin:hermes2026|
                                                    +-------------------+
```

## 2. Review 问题清单

### 2.1 安全问题

| # | 问题 | 严重度 | 当前状态 | 建议修复 |
|---|------|--------|---------|---------|
| 1 | Grafana 默认密码 admin/admin 未强制修改 | 高 | 待修复 | 首次启动时通过环境变量设置强密码 |
| 2 | Prometheus 无认证，裸奔在 :9090 | 中 | 待修复 | 添加 Basic Auth 或绑定 127.0.0.1 |
| 3 | Grafana 匿名访问是否需要关闭 | 低 | 待确认 | 根据使用场景决定 |

### 2.2 可靠性问题

| # | 问题 | 严重度 | 当前状态 | 建议修复 |
|---|------|--------|---------|---------|
| 4 | 容器无 restart policy | 高 | 待修复 | docker compose 添加 restart: unless-stopped |
| 5 | Prometheus 数据仅本地 volume，无备份 | 中 | 待修复 | 添加定期快照或远程写入 |
| 6 | 无健康检查配置 | 中 | 待修复 | 添加 healthcheck 到 docker-compose |
| 7 | 告警规则为空（rules/hermes-alerts.yml 为空） | 中 | 待修复 | 补充关键告警规则 |

### 2.3 监控覆盖问题

| # | 问题 | 严重度 | 当前状态 | 建议修复 |
|---|------|--------|---------|---------|
| 8 | Hermes 自身 Python metrics 未暴露 | 高 | 待实现 | 添加 prometheus_client 到 Hermes Agent |
| 9 | Gateway 指标未采集 | 中 | 待确认 | 检查 Gateway 是否有 /metrics 端点 |
| 10 | 无容器指标（cAdvisor） | 低 | 待评估 | 可选添加 cAdvisor |

### 2.4 配置管理问题

| # | 问题 | 严重度 | 当前状态 | 建议修复 |
|---|------|--------|---------|---------|
| 11 | Grafana 管理员密码硬编码在 docker-compose.yml | 高 | 待修复 | 迁移到 .env 文件 |
| 12 | 数据保留策略需确认 7 天是否足够 | 低 | 待确认 | 根据磁盘空间和需求调整 |

---

## 3. 修复优先级

### P0 — 立即修复（安全+可靠性）
- [ ] Grafana 密码改为强密码（通过 .env）
- [ ] Prometheus 绑定 127.0.0.1 或添加 Basic Auth
- [ ] 所有容器添加 restart: unless-stopped
- [ ] 所有容器添加 healthcheck

### P1 — 短期完善（1-2 天）
- [ ] 补充告警规则（CPU/内存/磁盘/容器重启）
- [ ] Hermes Agent 集成 prometheus_client
- [ ] 确认 Gateway metrics 端点
- [ ] 添加 Alertmanager（可选）

### P2 — 中期优化（1 周内）
- [ ] Prometheus 数据备份策略
- [ ] Grafana 仪表盘优化
- [ ] 日志采集方案（Loki 或其他）

---

## 4. 告警规则草案

```yaml
# rules/hermes-alerts.yml
groups:
  - name: hermes-agent
    rules:
      - alert: HermesAgentDown
        expr: up{job="hermes-agent"} == 0
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "Hermes Agent is down"
          description: "Hermes Agent has been unreachable for 1 minute"

      - alert: HighCPUUsage
        expr: process_cpu_seconds_total > 0.8
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "High CPU usage detected"

      - alert: HighMemoryUsage
        expr: process_resident_memory_bytes / 1024 / 1024 > 500
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "High memory usage (>500MB)"

      - alert: ContainerRestarting
        expr: increase(container_restart_count[5m]) > 0
        labels:
          severity: warning
        annotations:
          summary: "Container {{ $labels.name }} restarting"

      - alert: DiskSpaceLow
        expr: (node_filesystem_avail_bytes{mountpoint="/"} / node_filesystem_size_bytes{mountpoint="/"}) < 0.1
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "Disk space below 10%"
```

---

## 5. 修复后的 docker-compose.yml 结构

```yaml
version: '3.8'

services:
  prometheus:
    image: prom/prometheus:v2.53.0
    container_name: prometheus
    restart: unless-stopped
    command:
      - '--config.file=/etc/prometheus/prometheus.yml'
      - '--storage.tsdb.path=/prometheus'
      - '--storage.tsdb.retention.time=7d'
      - '--web.listen-address=127.0.0.1:9090'
    volumes:
      - ./prometheus.yml:/etc/prometheus/prometheus.yml:ro
      - ./rules:/etc/prometheus/rules:ro
      - prometheus_data:/prometheus
    ports:
      - "127.0.0.1:9090:9090"
    healthcheck:
      test: ["CMD", "wget", "--spider", "-q", "http://localhost:9090/-/healthy"]
      interval: 30s
      timeout: 5s
      retries: 3

  grafana:
    image: grafana/grafana:11.1.0
    container_name: grafana
    restart: unless-stopped
    environment:
      - GF_SECURITY_ADMIN_USER=${GRAFANA_ADMIN_USER:-admin}
      - GF_SECURITY_ADMIN_PASSWORD=${GRAFANA_ADMIN_PASSWORD}
      - GF_USERS_ALLOW_SIGN_UP=false
    volumes:
      - grafana_data:/var/lib/grafana
      - ./grafana/provisioning:/etc/grafana/provisioning:ro
    ports:
      - "3000:3000"
    depends_on:
      prometheus:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "wget", "--spider", "-q", "http://localhost:3000/api/health"]
      interval: 30s
      timeout: 5s
      retries: 3

  node-exporter:
    image: prom/node-exporter:v1.8.1
    container_name: node-exporter
    restart: unless-stopped
    command:
      - '--path.rootfs=/host'
    volumes:
      - /:/host:ro,rslave
    ports:
      - "127.0.0.1:9100:9100"
    healthcheck:
      test: ["CMD", "wget", "--spider", "-q", "http://localhost:9100/metrics"]
      interval: 30s
      timeout: 5s
      retries: 3

volumes:
  prometheus_data:
  grafana_data:
```

---

## 6. 决策待确认

1. Grafana 是否需要对外访问？（当前 :3000 无绑定 127.0.0.1）
2. 数据保留 7 天是否足够？
3. 是否需要 Alertmanager 发送告警到 Telegram？
4. 是否需要添加 cAdvisor 采集容器指标？
5. Hermes Agent 集成 prometheus_client 的优先级？

---

*文档生成于 2026-05-17，待用户确认后按优先级实施修复。*
