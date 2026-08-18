#!/usr/bin/env python3
"""Ragas 测试集生成脚本（ragas 0.4.x）

模型与生成参数从 config.json 读取（复制 config.example.json 填入 key），
命令行参数可覆盖配置文件中的同名项。优先级: 命令行 > config.json > 环境变量 > 内置默认。

config.json 结构:
  {
    "llm":        {"model": "...", "base_url": "...", "api_key": "..."},
    "transforms": {"model": "...", "base_url": "...", "api_key": "..."},   # 可选，缺省回落 llm
    "embedding":  {"model": "...", "base_url": "...", "api_key": "...", "dimensions": 1536, "batch_size": 20},
    "generation": {"size": 50, "max_chunks": 200, "max_workers": 8, "seed": 42, "out": "testset_v1.jsonl"}
  }

两种输入模式:
  --chunks chunks.jsonl  推荐: 直接喂知识库 chunk，跳过文档级 LLM 抽取，最省 token
                         jsonl 每行: {"content": "...", "metadata": {...}}
  --docs docs_dir/       备选: 喂整篇文档，ragas 内部做标题切分

性能设计:
  1. 预切块模式跳过文档级 headline/summary 抽取（每个 chunk 约 4 次 LLM 调用，是主要成本）
  2. max_chunks 采样控制知识图谱阶段规模
  3. transforms 段允许抽取阶段（NER/主题/摘要）用便宜模型，问题生成仍用主模型
  4. max_workers 控制 LLM 并发（RunConfig），SDK 层重试调低避免与 ragas 重试叠加
  5. raise_exceptions=False: 单条失败不影响整批
  6. 生成前预检连通性 + 打印调用次数估算，失败早退出

用法示例:
  cp config.example.json config.json   # 填入 api_key
  python generate_testset.py --chunks chunks.jsonl --size 50
"""

from __future__ import annotations

import argparse
import functools
import json
import os
import random
import socket
import sys
import time
from pathlib import Path

from langchain_openai import ChatOpenAI, OpenAIEmbeddings

from ragas import RunConfig, SingleTurnSample
from ragas.embeddings import LangchainEmbeddingsWrapper
from ragas.llms import LangchainLLMWrapper
from ragas.testset import TestsetGenerator
from ragas.testset.graph import KnowledgeGraph, Node, NodeType
from ragas.testset.persona import Persona
from ragas.testset.synthesizers import default_query_distribution
from ragas.testset.transforms import (
    apply_transforms,
    default_transforms,
    default_transforms_for_prechunked,
)

# 关闭 ragas 遥测上报（避免生成末尾 track() 走外网，也避免数据外发）
os.environ.setdefault("RAGAS_DO_NOT_TRACK", "true")

# 中文输出约束，追加到问题生成提示词后（Ragas 内置提示词是英文的）
ZH_INSTRUCTION_SUFFIX = (
    "\n\n重要约束："
    "\n1. 无论以上说明使用什么语言，生成的 query 和 answer 必须使用简体中文。"
    "\n2. query 要贴近真实中文用户的提问口吻，自然直接，不要翻译腔，不要出现「根据文档」之类的表述。"
    "\n3. answer 必须完全来自给定 context，使用简体中文作答。"
)

# 内置中文 persona（提供 persona_list 可跳过 LLM 生成 persona 的调用）
DEFAULT_PERSONAS = [
    ("企业新员工", "刚入职的员工，对公司制度、流程、术语不熟悉，会提出基础、直接的问题"),
    ("一线业务人员", "日常借助知识库解决具体业务问题的员工，提问具体、面向实操"),
    ("技术工程师", "关注系统设计、集成方式和技术细节，提问专业且深入"),
    ("部门管理员", "负责知识库内容维护与权限管理，关注规范口径和管理流程"),
]

DEFAULT_LLM_CONTEXT = (
    "这是一个中文企业知识库的评估测试集生成任务。"
    "生成的问题应符合中文用户的真实提问习惯：口语化、意图明确，允许包含专业术语。"
)


def _tolerant(coro_fn, label, default):
    """包装合成器协程：单点失败降级为空结果而非整轮崩溃。

    ragas 0.4.3 在 raise_exceptions=False 时用 np.nan 占位失败任务，
    但 generate() 内部直接遍历场景列表（float 不可迭代）并构造
    TestsetSample(eval_sample=nan)，两处都会 TypeError，故在实例层兜住：
    场景生成失败 → 该合成器本次贡献 0 条；单样本失败 → 空样本，后处理过滤。
    """
    @functools.wraps(coro_fn)
    async def wrapped(*args, **kwargs):
        try:
            return await coro_fn(*args, **kwargs)
        except Exception as e:
            print(f"[警告] {label} 失败，跳过: {type(e).__name__}: {str(e)[:120]}")
            return default
    return wrapped


def force_ipv4() -> None:
    """本进程所有域名解析只返回 IPv4 结果。

    WSL2 下 IPv6 链路在并发/间歇场景会随机连接失败（APIConnectionError），
    而所用端点均有 A 记录，强制 IPv4 可规避。若某端点仅 IPv6 则自动回退原结果。
    """
    _orig = socket.getaddrinfo

    def ipv4_only(*args, **kwargs):
        results = _orig(*args, **kwargs)
        v4 = [r for r in results if r[0] == socket.AF_INET]
        return v4 or results

    socket.getaddrinfo = ipv4_only


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Ragas 测试集生成（中文）")
    src = p.add_mutually_exclusive_group(required=True)
    src.add_argument("--chunks", help="预切块 jsonl 文件，每行 {content, metadata}")
    src.add_argument("--docs", help="整篇文档目录（.md/.txt，一文件一篇）")
    p.add_argument("--config", default=str(Path(__file__).parent / "config.json"),
                   help="配置文件路径（默认脚本同目录 config.json，不存在则跳过）")

    # 以下参数均可被 config.json 覆盖默认值；命令行显式传入时优先
    p.add_argument("--out", default=None, help="输出 jsonl 路径")
    p.add_argument("--size", type=int, default=None, help="目标测试集条数")
    p.add_argument("--max-chunks", type=int, default=None,
                   help="参与知识图谱构建的最大 chunk 数（超出则随机采样）")
    p.add_argument("--seed", type=int, default=None)

    # 模型配置（OpenAI 兼容端点），config.json > 环境变量 > 默认值
    p.add_argument("--llm-model", default=None, help="主模型：问题与参考答案生成")
    p.add_argument("--llm-base-url", default=None)
    p.add_argument("--api-key", default=None)
    p.add_argument("--transforms-model", default=None,
                   help="可选：抽取阶段（NER/主题/摘要）使用的便宜模型，缺省用主模型")
    p.add_argument("--transforms-base-url", default=None,
                   help="抽取模型端点，缺省与主模型相同")
    p.add_argument("--transforms-api-key", default=None)
    p.add_argument("--embedding-model", default=None)
    p.add_argument("--embedding-base-url", default=None)
    p.add_argument("--embedding-api-key", default=None)
    p.add_argument("--embedding-batch-size", type=int, default=None)
    p.add_argument("--embedding-dimensions", type=int, default=None,
                   help="请求的向量维度（模型须支持 dimensions 参数）；"
                        "生成阶段非必需，与项目保持一致（如 1536）可便于复用")
    p.add_argument("--personas-file", default=None,
                   help="可选：自定义 persona JSON 数组 "
                   "[{\"name\":..., \"role_description\":...}]，缺省用内置中文 persona")
    p.add_argument("--llm-context", default=None,
                   help="注入到问题生成中的业务上下文说明")

    # 性能 knobs
    p.add_argument("--max-workers", type=int, default=None,
                   help="LLM 并发数（RunConfig.max_workers，按端点限流调整）")
    p.add_argument("--timeout", type=int, default=None, help="单次调用超时秒数")
    p.add_argument("--max-retries", type=int, default=None, help="失败重试次数")
    p.add_argument("--max-wait", type=int, default=None, help="重试最大等待秒数")
    p.add_argument("--skip-preflight", action="store_true", help="跳过连通性预检")
    return p.parse_args()


def resolve_config(args: argparse.Namespace) -> argparse.Namespace:
    """合并配置来源: 命令行 > config.json > 环境变量 > 内置默认值"""
    cfg_path = Path(args.config)
    cfg = json.loads(cfg_path.read_text(encoding="utf-8")) if cfg_path.exists() else {}
    llm_cfg, tr_cfg = cfg.get("llm", {}), cfg.get("transforms", {})
    emb_cfg, gen_cfg = cfg.get("embedding", {}), cfg.get("generation", {})

    def pick(cli, *fallbacks):
        for v in (cli, *fallbacks):
            if v is not None:
                return v
        return None

    args.llm_model = pick(args.llm_model, llm_cfg.get("model"), os.getenv("RAGAS_LLM_MODEL"), "gpt-4o")
    args.llm_base_url = pick(args.llm_base_url, llm_cfg.get("base_url"), os.getenv("OPENAI_BASE_URL"))
    args.api_key = pick(args.api_key, llm_cfg.get("api_key"), os.getenv("OPENAI_API_KEY"))
    # 抽取模型：独立端点可选，未配置时端点与 key 回落到主模型
    args.transforms_model = pick(args.transforms_model, tr_cfg.get("model"), os.getenv("RAGAS_TRANSFORMS_MODEL"))
    args.transforms_base_url = pick(args.transforms_base_url, tr_cfg.get("base_url"), llm_cfg.get("base_url"), args.llm_base_url)
    args.transforms_api_key = pick(args.transforms_api_key, tr_cfg.get("api_key"), llm_cfg.get("api_key"), args.api_key)
    # 向量模型：未单独配置端点时回落主模型
    args.embedding_model = pick(args.embedding_model, emb_cfg.get("model"), os.getenv("RAGAS_EMBEDDING_MODEL"), "text-embedding-3-small")
    args.embedding_base_url = pick(args.embedding_base_url, emb_cfg.get("base_url"), llm_cfg.get("base_url"), os.getenv("EMBEDDING_BASE_URL"), args.llm_base_url)
    args.embedding_api_key = pick(args.embedding_api_key, emb_cfg.get("api_key"), llm_cfg.get("api_key"), os.getenv("EMBEDDING_API_KEY"), args.api_key)
    args.embedding_batch_size = pick(args.embedding_batch_size, emb_cfg.get("batch_size"), 64)
    args.embedding_dimensions = pick(args.embedding_dimensions, emb_cfg.get("dimensions"))

    args.out = pick(args.out, gen_cfg.get("out"), "testset.jsonl")
    args.size = pick(args.size, gen_cfg.get("size"), 50)
    args.max_chunks = pick(args.max_chunks, gen_cfg.get("max_chunks"), 200)
    args.seed = pick(args.seed, gen_cfg.get("seed"), 42)
    args.max_workers = pick(args.max_workers, gen_cfg.get("max_workers"), 8)
    args.timeout = pick(args.timeout, gen_cfg.get("timeout"), 120)
    args.max_retries = pick(args.max_retries, gen_cfg.get("max_retries"), 5)
    args.max_wait = pick(args.max_wait, gen_cfg.get("max_wait"), 45)
    args.personas_file = pick(args.personas_file, gen_cfg.get("personas_file"))
    args.llm_context = pick(args.llm_context, gen_cfg.get("llm_context"), DEFAULT_LLM_CONTEXT)
    args.force_ipv4 = cfg.get("force_ipv4", True)

    # proxy: "direct" 时让配置的端点绕过系统代理（本机代理常无法转发阿里云内网/专有端点）
    if cfg.get("proxy") == "direct":
        from urllib.parse import urlparse
        hosts = {urlparse(u).hostname for u in
                 (args.llm_base_url, args.transforms_base_url, args.embedding_base_url) if u}
        if hosts:
            suffix = ",".join(sorted(hosts))
            for var in ("no_proxy", "NO_PROXY"):
                os.environ[var] = f"{os.environ.get(var, '')},{suffix}".lstrip(",")
            print(f"[配置] 端点直连（绕过系统代理）: {suffix}")
    return args


def load_chunks(path: str, max_chunks: int, seed: int) -> list[dict]:
    """读取 chunks jsonl，字段容忍 content/text/page_content 三种键名"""
    items = []
    with open(path, encoding="utf-8") as f:
        for line_no, line in enumerate(f, 1):
            line = line.strip()
            if not line:
                continue
            try:
                row = json.loads(line)
            except json.JSONDecodeError as e:
                sys.exit(f"[错误] chunks 文件第 {line_no} 行不是合法 JSON: {e}")
            content = row.get("content") or row.get("text") or row.get("page_content")
            if not content or not content.strip():
                continue
            items.append({"content": content.strip(), "metadata": row.get("metadata") or {}})

    # 内容去重后再采样，避免重复 chunk 白耗 LLM 调用
    seen, unique = set(), []
    for it in items:
        key = hash(it["content"])
        if key not in seen:
            seen.add(key)
            unique.append(it)

    if len(unique) > max_chunks:
        rng = random.Random(seed)
        unique = rng.sample(unique, max_chunks)
    return unique


def load_docs(path: str) -> list:
    from langchain_core.documents import Document as LCDocument

    docs = []
    for f in sorted(Path(path).rglob("*")):
        if f.suffix.lower() not in (".md", ".txt"):
            continue
        docs.append(LCDocument(page_content=f.read_text(encoding="utf-8"),
                               metadata={"filename": str(f)}))
    return docs


def build_run_config(args) -> RunConfig:
    return RunConfig(
        timeout=args.timeout,
        max_retries=args.max_retries,
        max_wait=args.max_wait,
        max_workers=args.max_workers,
        seed=args.seed,
    )


def build_clients(args):
    # SDK 层重试保持低值：ragas 内部还有 tenacity 重试，两层都高会放大等待时间
    chat = ChatOpenAI(model=args.llm_model, base_url=args.llm_base_url,
                      api_key=args.api_key, timeout=args.timeout, max_retries=2)
    emb = OpenAIEmbeddings(model=args.embedding_model,
                           base_url=args.embedding_base_url,
                           api_key=args.embedding_api_key,
                           timeout=args.timeout, max_retries=2,
                           check_embedding_ctx_length=False,
                           dimensions=args.embedding_dimensions,
                           chunk_size=args.embedding_batch_size)
    # 抽取模型未配置时直接复用主模型客户端
    transforms_chat = (
        ChatOpenAI(model=args.transforms_model, base_url=args.transforms_base_url,
                   api_key=args.transforms_api_key, timeout=args.timeout, max_retries=2)
        if args.transforms_model else chat
    )
    return chat, emb, transforms_chat


def preflight(args, chat, emb) -> None:
    """生成前最小连通性检查，失败立即退出，避免知识图谱阶段中途崩掉"""
    print("[预检] embedding 端点 ...", flush=True)
    vec = emb.embed_query("连接测试")
    print(f"[预检] embedding OK, 维度={len(vec)}")
    print(f"[预检] chat 端点 ({args.llm_model}) ...", flush=True)
    resp = chat.invoke("只回复两个字：正常")
    print(f"[预检] chat OK: {resp.content.strip()[:30]}")


def load_personas(args) -> list[Persona]:
    if args.personas_file:
        data = json.loads(Path(args.personas_file).read_text(encoding="utf-8"))
        return [Persona(name=d["name"], role_description=d["role_description"]) for d in data]
    return [Persona(name=n, role_description=d) for n, d in DEFAULT_PERSONAS]


def build_knowledge_graph(args, generator, transforms_llm, transforms_emb, chunks, docs):
    """手动构建 KG，以便：1) 打印各阶段进度 2) 用真实 KG 挑选可用的合成器"""
    if chunks is not None:
        nodes = [Node(type=NodeType.CHUNK,
                      properties={"page_content": c["content"],
                                  "document_metadata": c["metadata"]})
                 for c in chunks]
        transforms = default_transforms_for_prechunked(llm=transforms_llm,
                                                       embedding_model=transforms_emb)
        label = f"{len(nodes)} 个 chunk"
    else:
        nodes = [Node(type=NodeType.DOCUMENT,
                      properties={"page_content": d.page_content,
                                  "document_metadata": d.metadata})
                 for d in docs]
        transforms = default_transforms(documents=list(docs), llm=transforms_llm,
                                        embedding_model=transforms_emb)
        label = f"{len(nodes)} 篇文档"

    kg = KnowledgeGraph(nodes=nodes)
    print(f"[知识图谱] 开始 transforms（{label}，并发={args.max_workers}）...", flush=True)
    t0 = time.time()
    apply_transforms(kg, transforms, run_config=build_run_config(args))
    rel_count = len(kg.relationships)
    print(f"[知识图谱] 完成，耗时 {time.time() - t0:.0f}s，节点={len(kg.nodes)}，关系={rel_count}")
    return kg


def main() -> None:
    args = resolve_config(parse_args())
    if args.force_ipv4:
        force_ipv4()
        print("[配置] 已强制 IPv4 连接")
    if not args.api_key:
        sys.exit("[错误] 缺少 API key：在 config.json 的 llm.api_key 填入，"
                 "或设置 OPENAI_API_KEY，或传 --api-key")

    chunks = load_chunks(args.chunks, args.max_chunks, args.seed) if args.chunks else None
    docs = load_docs(args.docs) if args.docs else None
    if chunks is not None and not chunks:
        sys.exit("[错误] chunks 文件为空或全部无效")
    if docs is not None and not docs:
        sys.exit("[错误] 文档目录为空（仅支持 .md/.txt）")

    n_units = len(chunks) if chunks is not None else len(docs)
    est_kg_calls = n_units * (4 if chunks is not None else 6)
    est_gen_calls = args.size * 3
    print(f"[计划] 输入={n_units} 个{'chunk' if chunks is not None else '文档'}, "
          f"目标条数={args.size}")
    print(f"[估算] 知识图谱阶段 ≈{est_kg_calls} 次 LLM 调用, "
          f"问题合成阶段 ≈{est_gen_calls} 次, 合计 ≈{est_kg_calls + est_gen_calls} 次")
    print(f"[估算] 并发={args.max_workers}, 若单次平均 10s, 预计 ≈"
          f"{(est_kg_calls + est_gen_calls) * 10 / args.max_workers / 60:.0f} 分钟")

    chat, emb, transforms_chat = build_clients(args)
    if not args.skip_preflight:
        preflight(args, chat, emb)

    generator = TestsetGenerator.from_langchain(
        llm=chat, embedding_model=emb, llm_context=args.llm_context)
    if args.transforms_model:
        print(f"[配置] 抽取阶段使用便宜模型: {args.transforms_model}")
    transforms_llm = LangchainLLMWrapper(transforms_chat) if args.transforms_model else generator.llm

    run_config = build_run_config(args)
    kg = build_knowledge_graph(args, generator, transforms_llm,
                               generator.embedding_model, chunks, docs)
    generator.knowledge_graph = kg

    personas = load_personas(args)
    generator.persona_list = personas  # 提供 persona 跳过 LLM 生成 persona

    # 基于真实 KG 过滤可用合成器（如无 chunk 关系则自动剔除多跳），再中文化提示词
    query_distribution = default_query_distribution(generator.llm, kg, args.llm_context)
    for synth, weight in query_distribution:
        name = synth.__class__.__name__
        synth.generate_query_reference_prompt.instruction += ZH_INSTRUCTION_SUFFIX
        synth.generate_scenarios = _tolerant(synth.generate_scenarios, f"{name} 场景生成", [])
        synth.generate_sample = _tolerant(synth.generate_sample, f"{name} 样本生成", SingleTurnSample())
        print(f"[合成器] {name} 权重={weight:.2f}")

    print(f"[合成] 生成 {args.size} 条（persona={len(personas)} 个, "
          f"并发={args.max_workers}, 单条失败不中断）...", flush=True)
    t0 = time.time()
    testset = generator.generate(
        testset_size=args.size,
        query_distribution=query_distribution,
        num_personas=min(len(personas), 4),
        run_config=run_config,
        raise_exceptions=False,  # 部分失败保住已生成的结果
    )
    print(f"[合成] 完成，耗时 {time.time() - t0:.0f}s")

    # 后处理：丢弃无效条目、按问题去重（重复问题浪费 size 配额）
    seen, kept, dropped_empty, dropped_dup = set(), [], 0, 0
    for row in testset.to_list():
        q = (row.get("user_input") or "").strip()
        if not q or not (row.get("reference") or "").strip():
            dropped_empty += 1
            continue
        if q in seen:
            dropped_dup += 1
            continue
        seen.add(q)
        kept.append(row)

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    with open(out, "w", encoding="utf-8") as f:
        for row in kept:
            f.write(json.dumps(row, ensure_ascii=False) + "\n")

    by_type: dict[str, int] = {}
    for row in kept:
        by_type[row.get("synthesizer_name", "?")] = by_type.get(row.get("synthesizer_name", "?"), 0) + 1
    print(f"[完成] 有效 {len(kept)} 条 → {out}")
    print(f"[完成] 丢弃：空条目 {dropped_empty}, 重复问题 {dropped_dup}")
    for name, cnt in sorted(by_type.items()):
        print(f"  - {name}: {cnt}")
    print(f"[提示] run_id={testset.run_id}，请人工抽样审核后再投入使用")


if __name__ == "__main__":
    main()
