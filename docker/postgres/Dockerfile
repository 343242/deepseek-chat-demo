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
RUN git clone --branch v0.8.2 https://github.com/pgvector/pgvector.git \
    && cd pgvector \
    && make -j"$(nproc)" \
    && make install \
    && rm -rf /tmp/pgvector

# 阶段3：克隆并编译 pg_jieba（含 submodule）
RUN git clone https://github.com/jaiminpan/pg_jieba.git \
    && cd pg_jieba \
    && git submodule update --init --recursive \
    && mkdir build && cd build \
    && PG_SERVER_INC=$(pg_config --includedir-server) \
    && cmake .. -DPostgreSQL_TYPE_INCLUDE_DIR="$PG_SERVER_INC" \
    && make -j"$(nproc)" \
    && make install \
    && rm -rf /tmp/pg_jieba

WORKDIR /
