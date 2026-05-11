FROM postgres:18-bookworm

# 阶段1：安装编译依赖 + CA 证书
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    postgresql-server-dev-18 \
    git \
    cmake \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# 阶段2：克隆 pg_jieba（含 submodule）
WORKDIR /tmp
RUN git clone https://github.com/jaiminpan/pg_jieba.git \
    && cd pg_jieba \
    && git submodule update --init --recursive

# 阶段3：编译安装
# pg_jieba 的 CMakeLists.txt 用 find_package(PostgreSQL) 但找不到 server include dir
# 需显式传递 PostgreSQL_TYPE_INCLUDE_DIR
WORKDIR /tmp/pg_jieba/build
RUN PG_SERVER_INC=$(pg_config --includedir-server) \
    && cmake .. \
        -DPostgreSQL_TYPE_INCLUDE_DIR="$PG_SERVER_INC" \
    && make -j"$(nproc)" \
    && make install

# 清理
RUN rm -rf /tmp/pg_jieba

WORKDIR /
