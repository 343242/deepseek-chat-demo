# Ragas 评估工作区

用 [Ragas](https://github.com/vibrantlabsai/ragas)（0.4.3）为 smart-rag 生成中文评估测试集。
本目录是纯 Python 工具侧，不依赖 Java 后端运行；生成的测试集后续可导入 evaluation 模块管理。

## 环境

```bash
source ragas-eval/bin/activate        # uv 创建的虚拟环境（依赖清单见 requirements.txt）
# 复现环境: uv venv ragas-eval && uv pip install -r requirements.txt --python ragas-eval/bin/python
```

注意：`langchain-community` 已锁定 0.3.31（ragas 0.4.3 与 0.4.x 不兼容，见 requirements.txt），升级 ragas 前先重测。

## 第一步：从 vector_store 导出 chunk（推荐输入）

预切块模式最省 token：跳过文档级 headline/summary 抽取，且 chunk 粒度与线上检索一致。

```bash
psql "$DATABASE_URL" -c \
  "\copy (SELECT jsonb_build_object('content', content, 'metadata', metadata) FROM vector_store WHERE metadata->>'userId'='1' ORDER BY random() LIMIT 300) TO 'chunks.jsonl'"
```

命令逐段解释：

| 片段 | 作用 |
|------|------|
| `psql "$DATABASE_URL" -c "..."` | 用连接串（`postgres://user:pass@host:5432/db`）连库并执行引号内的命令，密码不落 shell 历史 |
| `\copy (查询) TO 'chunks.jsonl'` | psql 元命令：在**客户端机器**上把查询结果写成文件（SQL 层 `COPY TO` 写的是数据库服务器上的文件且需高权限，`\copy` 都不需要） |
| `jsonb_build_object('content', content, 'metadata', metadata)` | 每行拼一个 JSON 对象 `{"content": ..., "metadata": {...}}`，正好是脚本的 jsonl 输入格式 |
| `FROM vector_store` | pgvector 存储表，已入库文档的 chunk（content 正文 + metadata JSON + 向量列）都在这里 |
| `WHERE metadata->>'userId'='1'` | `->>` 取 JSON 字段的文本值；按用户过滤——评估谁的知识库就填谁的 userId（多租户隔离） |
| `ORDER BY random() LIMIT 300` | 均匀随机抽 300 条；300 是导出量，最终参与生成多少由脚本的 `max_chunks` 再裁 |

本机没装 psql 时走 Docker 容器导出（等效，输出直接重定向到宿主机文件）：

```bash
set -a && source ../.env && set +a
docker exec smart-rag-db psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tA -c \
  "SELECT jsonb_build_object('content', content, 'metadata', metadata) FROM vector_store WHERE metadata->>'userId'='1' ORDER BY random() LIMIT 300" > chunks.jsonl
```

导出的 `chunks.jsonl` 每行一个 JSON 对象（`text` / `page_content` 键名也兼容）。
没有数据库时也可用 `--docs 目录/` 喂整篇 .md/.txt 文档。

## 第二步：生成测试集

模型与生成参数放在 `config.json`（已 gitignore，模板 `config.example.json`）：

```bash
cp config.example.json config.json   # 填入三个 api_key
python generate_testset.py --chunks chunks.jsonl
```

config.json 四个段：`llm`（主模型，生成问题与参考答案）、`transforms`（抽取阶段用的便宜模型，
可配独立端点，删掉则用主模型）、`embedding`（向量模型，端点/key 未填时自动回落 `llm` 段）、
`generation`（条数、并发等运行参数）。另有两个顶层开关：

- `"proxy": "direct"`——让配置的端点绕过系统代理（本机代理 `127.0.0.1:2334` 无法转发阿里云端点）；
- `"force_ipv4": true`——进程级强制 IPv4 解析。WSL2 的 IPv6 链路在并发下会间歇性
  `APIConnectionError`（实测 12 并发仅 1/12 成功），强制 IPv4 后 12/12 通过，**勿关**。

命令行参数优先于配置文件，临时改量直接加 `--size 5 --max-chunks 20` 这类参数即可。

### 性能相关参数

| 参数 | 作用 | 建议 |
|------|------|------|
| `max_chunks` / `--max-chunks` | 知识图谱阶段的 chunk 上限（超出随机采样） | 目标 size 的 3~4 倍即可，200 chunk ≈ 800 次抽取调用 |
| `max_workers` / `--max-workers` | LLM 并发（RunConfig） | 端点限流的 50%~80%，429 频繁就调低 |
| `transforms` 段 / `--transforms-model` | 抽取阶段换便宜模型 | 强烈建议开启 |
| `timeout` / `max_retries` / `max_wait` | 单次超时与重试 | 默认值即可；SDK 层重试已固定为 2，避免与 ragas 重试叠加 |
| `--skip-preflight` | 跳过连通性预检 | 调试时用 |

内置行为：内容去重后再采样；单条失败不中断整批（`raise_exceptions=False`）；
生成后自动去重问题、丢弃空条目；多跳合成器在 chunk 间无关系时自动跳过。

## 第三步：人工审核

输出 jsonl 每行一个样本，字段与 Ragas 对齐：

```json
{"user_input": "...", "reference": "...", "reference_contexts": ["..."], "synthesizer_name": "single_hop_specific"}
```

建议抽样 20~30% 检查：答案是否有据、问题是否像真实用户口吻；并手工补充拒答类（知识库外）问题。
审核通过的文件**固定版本入库**（git），之后每次评估跑同一份才有可比性。

## 后续衔接

- 导入 Java evaluation 模块：`user_input → question`、`reference → ground_truth_answer`、
  `reference_contexts → relevant_content`（evaluation_dataset_item 表）
- 评估脚本：读 jsonl → 调 RAG HTTP API 收集 `response` / `retrieved_contexts` → Ragas 打分
  （指标新路径：`from ragas.metrics.collections import ...`）
