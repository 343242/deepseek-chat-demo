#!/bin/bash
# RocketMQ 5.x Topic/消费组初始化脚本
#
# 用途：创建消息总线所需的 Topic 和消费组（应用首次启动前执行一次）
# 设计文档：docs/design/messaging-bus.md §5.12
#
# 用法：
#   # 通过 Docker Compose 执行（推荐）
#   docker cp scripts/init-rocketmq-topics.sh smart-rag-rmqbroker:/tmp/
#   docker compose exec rmqbroker bash /tmp/init-rocketmq-topics.sh
#
#   # 本机安装了 mqadmin 时也可直接执行
#   bash scripts/init-rocketmq-topics.sh

set -euo pipefail

# ── 可配置参数 ──────────────────────────────────────────────────────────────

# NameServer 地址：自动检测运行环境
#   Docker 容器内 → rmqnamesrv:9876（Compose 服务名）
#   宿主机       → localhost:9876（端口映射）
if [ -z "${ROCKETMQ_NAMESRV:-}" ]; then
    if grep -q docker /proc/1/cgroup 2>/dev/null || [ -f /.dockerenv ]; then
        NAMESRV="rmqnamesrv:9876"
    else
        NAMESRV="localhost:9876"
    fi
else
    NAMESRV="$ROCKETMQ_NAMESRV"
fi

CLUSTER=${CLUSTER_NAME:-DefaultCluster}
TOPIC_PREFIX=${TOPIC_PREFIX:-SMART_RAG_}

# 消息总线配置属性 app.messaging.topic-prefix 的镜像
# 如果修改了 application.yml 中的 topic-prefix，此处需同步更新
#
# ⚠️ RocketMQ topic 命名规则：只允许 [%|a-zA-Z0-9_-]+，不允许 '.'，用 '_' 分隔

# FIFO Topic 队列数（设计文档建议 16，避免 messageGroup 热点）
FIFO_QUEUE_COUNT=${FIFO_QUEUE_COUNT:-16}

# PushConsumer 默认最大投递次数（设计文档 §4.6 + §6.1）
MAX_DELIVERY_ATTEMPTS=${MAX_DELIVERY_ATTEMPTS:-16}

# ── mqadmin 路径探测 ──────────────────────────────────────────────────────
# 优先使用 PATH 中的 mqadmin，否则搜索常见安装路径
MQADMIN=""
if command -v mqadmin &>/dev/null; then
    MQADMIN="mqadmin"
else
    # shellcheck disable=SC2086
    for candidate in /home/rocketmq/rocketmq-*/bin/mqadmin /opt/rocketmq/bin/mqadmin; do
        if [ -x "$candidate" ]; then
            MQADMIN="$candidate"
            break
        fi
    done
fi

if [ -z "$MQADMIN" ]; then
    echo "ERROR: mqadmin not found. Run inside the broker container:"
    echo "  docker cp scripts/init-rocketmq-topics.sh smart-rag-rmqbroker:/tmp/"
    echo "  docker compose exec rmqbroker bash /tmp/init-rocketmq-topics.sh"
    exit 1
fi

echo "Using mqadmin: $MQADMIN"
echo "NameServer:    $NAMESRV"
echo "Cluster:       $CLUSTER"
echo "Topic prefix:  $TOPIC_PREFIX"
echo ""

# ── 辅助函数 ──────────────────────────────────────────────────────────────
run_cmd() {
    echo "  $ $*"
    "$@"
}

# ── 1. 创建 Topic ────────────────────────────────────────────────────────
echo "=== Creating Topics ==="

# 聊天消息保存（标准 Topic，4 Queue）
run_cmd "$MQADMIN" updateTopic \
    -c "$CLUSTER" \
    -t "${TOPIC_PREFIX}chat_message_save" \
    -n "$NAMESRV"

# Token 用量记录（标准 Topic，4 Queue）
run_cmd "$MQADMIN" updateTopic \
    -c "$CLUSTER" \
    -t "${TOPIC_PREFIX}chat_usage_record" \
    -n "$NAMESRV"

# RAG 索引文档（FIFO Topic，16 Queue — messageGroup 基数为 documentId）
# -o true 设置有序 Topic（5.2.0 使用 -o 而非 -a +messageType=FIFO）
# -r / -w 控制读写队列数（mqadmin 不支持 -q）
run_cmd "$MQADMIN" updateTopic \
    -c "$CLUSTER" \
    -t "${TOPIC_PREFIX}rag_index_document" \
    -o true \
    -r "$FIFO_QUEUE_COUNT" \
    -w "$FIFO_QUEUE_COUNT" \
    -n "$NAMESRV"

echo ""

# ── 2. 创建消费组 ────────────────────────────────────────────────────────
echo "=== Creating Consumer Groups ==="

# PushConsumer 消费组 — 聊天消息保存（Broker 端自动重试，maxDeliveryAttempts 次后进 DLQ）
run_cmd "$MQADMIN" updateSubGroup \
    -c "$CLUSTER" \
    -g save-group \
    -a "maxDeliveryAttempts=$MAX_DELIVERY_ATTEMPTS" \
    -n "$NAMESRV"

# PushConsumer 消费组 — Token 用量记录
run_cmd "$MQADMIN" updateSubGroup \
    -c "$CLUSTER" \
    -g usage-group \
    -a "maxDeliveryAttempts=$MAX_DELIVERY_ATTEMPTS" \
    -n "$NAMESRV"

# SimpleConsumer 消费组 — RAG 索引文档（应用层控制重试，无需 maxDeliveryAttempts）
run_cmd "$MQADMIN" updateSubGroup \
    -c "$CLUSTER" \
    -g index-group \
    -n "$NAMESRV"

echo ""

# ── 3. 创建 DLQ Topic ───────────────────────────────────────────────────
# PushConsumer 组的死信队列 topic。RocketMQ 5.x 不会在 producer.send 时自动创建
# %DLQ%<group>，必须显式建——否则 poison 消息转 DLQ 会 404（No topic route info），
# 消息卡在 RETRY 队列反复重投。SimpleConsumer（index-group）走应用层重试、无 broker
# DLQ，故不建。updateTopic 幂等，重跑安全。
echo "=== Creating DLQ Topics (PushConsumer groups) ==="

run_cmd "$MQADMIN" updateTopic \
    -c "$CLUSTER" \
    -t '%DLQ%save-group' \
    -n "$NAMESRV"

run_cmd "$MQADMIN" updateTopic \
    -c "$CLUSTER" \
    -t '%DLQ%usage-group' \
    -n "$NAMESRV"

echo ""

# ── 4. 验证 ──────────────────────────────────────────────────────────────
echo "=== Verifying Topics ==="

# 5.2.0 的 topicStatus 不支持 -c 参数，改用 topicList + grep 验证
TOPIC_LIST=$("$MQADMIN" topicList -n "$NAMESRV" 2>/dev/null || true)

for topic in \
    "${TOPIC_PREFIX}chat_message_save" \
    "${TOPIC_PREFIX}chat_usage_record" \
    "${TOPIC_PREFIX}rag_index_document"; do
    echo -n "  Checking: $topic → "
    if echo "$TOPIC_LIST" | grep -q "^${topic}$"; then
        echo "✓ exists"
    else
        echo "✗ not found (check Broker logs)"
    fi
done

echo ""
echo "=== RocketMQ init complete ==="
