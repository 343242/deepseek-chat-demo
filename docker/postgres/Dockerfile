FROM postgres:18-bookworm

# 编译安装 pg_jieba 扩展
# 结巴分词 PostgreSQL 扩展，提供 jiebacfg 文本搜索配置用于中文分词
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    postgresql-server-dev-18 \
    git \
    cmake \
    && rm -rf /var/lib/apt/lists/* \
    && git clone https://github.com/jaiminpan/pg_jieba.git /tmp/pg_jieba \
    && cd /tmp/pg_jieba \
    && git submodule update --init --recursive \
    && mkdir build && cd build \
    && cmake .. \
    && make -j"$(nproc)" \
    && make install \
    && rm -rf /tmp/pg_jieba
