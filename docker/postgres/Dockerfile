FROM postgres:18-bookworm

# 阶段1：安装编译依赖 + CA 证书
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    postgresql-server-dev-18 \
    git \
    cmake \
    ca-certificates \
    || (apt-get update && apt-get install -y --fix-missing --no-install-recommends \
    build-essential \
    postgresql-server-dev-18 \
    git \
    cmake \
    ca-certificates) \
    && rm -rf /var/lib/apt/lists/*

# 阶段2：克隆并编译 pgvector
WORKDIR /tmp
RUN git clone --branch v0.8.6 https://github.com/pgvector/pgvector.git \
    && cd pgvector \
    && make -j"$(nproc)" \
    && make install \
    && rm -rf /tmp/pgvector

# 阶段3：克隆并编译 pg_jieba（含 submodule）
# cppjieba 覆盖说明：pg_jieba master 的 submodule 钉在 v5.1.0（2022-10），checkout 到
# v5.6.7 以获取词典更新与分词修复（如 v5.6.6 的"习总书记"词条）。嵌套 submodule
# (deps/limonp) 必须在 libjieba 内部再 update 一次——若回到 pg_jieba 根目录跑
# submodule update 会把 libjieba 重置回 v5.1.0
RUN git clone https://github.com/jaiminpan/pg_jieba.git \
    && cd pg_jieba \
    && git submodule update --init --recursive \
    && cd libjieba \
    && git checkout v5.6.7 \
    && git submodule update --init --recursive \
    && cd /tmp/pg_jieba \
    && mkdir build && cd build \
    && PG_SERVER_INC=$(pg_config --includedir-server) \
    && cmake .. -DPostgreSQL_TYPE_INCLUDE_DIR="$PG_SERVER_INC" \
    && make -j"$(nproc)" \
    && make install \
    && rm -rf /tmp/pg_jieba

WORKDIR /
