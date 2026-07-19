#!/usr/bin/env bash
# ============================================================================
# Smart RAG — Let's Encrypt 证书首次申请脚本
# ----------------------------------------------------------------------------
# 原理：
#   1. 先让 nginx 仅以 HTTP 模式启动（提供 /.well-known/acme-challenge/ 路径）
#   2. certbot 用 HTTP-01 challenge 申请证书，写到 /var/www/certbot
#   3. 关掉临时 nginx，恢复完整配置（含 HTTPS server block）后正式启动
#
# 用法：
#   ./scripts/init-ssl.sh
#
# 前置条件：
#   - .env 已填好 SERVER_NAME（指向本机的域名，A 记录已生效）和 CERTBOT_EMAIL
#   - 80/443 端口在公网可达，且没被其他进程占用
#   - 域名 A 记录已生效：dig +short SERVER_NAME 应返回本机公网 IP
# ============================================================================
set -euo pipefail

cd "$(dirname "$0")/.."

# 颜色输出
RED=$'\033[0;31m'; GREEN=$'\033[0;32m'; YELLOW=$'\033[1;33m'; NC=$'\033[0m'
log()  { echo "${GREEN}[init-ssl]${NC} $*"; }
warn() { echo "${YELLOW}[init-ssl]${NC} $*" >&2; }
die()  { echo "${RED}[init-ssl]${NC} $*" >&2; exit 1; }

# ---- 1. 检查前置条件 -------------------------------------------------------
[[ -f .env ]] || die ".env 文件不存在，请先 cp .env.example .env 并填写"
# shellcheck disable=SC1091
set -a; source .env; set +a

[[ -n "${SERVER_NAME:-}" ]]    || die ".env 缺少 SERVER_NAME"
[[ -n "${CERTBOT_EMAIL:-}" ]]  || die ".env 缺少 CERTBOT_EMAIL"

log "域名: ${SERVER_NAME}"
log "邮箱: ${CERTBOT_EMAIL}"

# ---- 2. DNS 解析校验（避免申请时 ACME 一直失败） ---------------------------
PUBLIC_IP=$(curl -fsS https://ifconfig.me 2>/dev/null || curl -fsS https://api.ipify.org 2>/dev/null || echo "")
RESOLVED_IP=$(dig +short "${SERVER_NAME}" A 2>/dev/null | head -1 || echo "")

if [[ -n "${PUBLIC_IP}" && -n "${RESOLVED_IP}" && "${PUBLIC_IP}" != "${RESOLVED_IP}" ]]; then
    warn "本机公网 IP (${PUBLIC_IP}) 与 ${SERVER_NAME} 解析到的 IP (${RESOLVED_IP}) 不一致"
    warn "ACME HTTP-01 验证会失败。请先确认 DNS A 记录已生效且指向本机。"
    read -rp "继续吗？[y/N] " ans
    [[ "${ans}" =~ ^[Yy]$ ]] || exit 1
fi

# ---- 3. 检查证书是否已存在 -------------------------------------------------
CERT_PATH="./certbot/live/${SERVER_NAME}"
if [[ -d "${CERT_PATH}" ]]; then
    log "证书已存在: ${CERT_PATH}，跳过申请。如需强制重申请，删除该目录后重跑。"
    exit 0
fi

# ---- 4. 启动临时 nginx（仅 HTTP） ------------------------------------------
log "启动临时 nginx（仅 HTTP，提供 ACME challenge 路径）..."
# 用 nginx:alpine 直接起一个最小容器，挂载 certbot-webroot，仅监听 80
docker run -d --rm \
    --name smart-rag-certbot-helper \
    -p 80:80 \
    -v "$(pwd)/certbot/webroot:/var/www/certbot:ro" \
    nginx:1.27-alpine \
    sh -c 'echo "server { listen 80; location /.well-known/acme-challenge/ { root /var/www/certbot; } }" > /etc/nginx/conf.d/default.conf && nginx -g "daemon off;"'

cleanup() {
    log "清理临时 nginx 容器..."
    docker stop smart-rag-certbot-helper 2>/dev/null || true
}
trap cleanup EXIT

# 等待 nginx 起来
sleep 3

# ---- 5. 申请证书 -----------------------------------------------------------
log "通过 certbot 申请证书（HTTP-01 challenge）..."
mkdir -p ./certbot/webroot
docker run --rm \
    -v "$(pwd)/certbot/etc:/etc/letsencrypt" \
    -v "$(pwd)/certbot/var:/var/lib/letsencrypt" \
    -v "$(pwd)/certbot/webroot:/var/www/certbot" \
    certbot/certbot certonly \
        --webroot \
        --webroot-path /var/www/certbot \
        --email "${CERTBOT_EMAIL}" \
        --agree-tos \
        --no-eff-email \
        -d "${SERVER_NAME}" \
        --non-interactive

log "证书申请成功: ./certbot/live/${SERVER_NAME}/"

# ---- 6. 提示后续步骤 -------------------------------------------------------
cat <<EOF

${GREEN}✓ 证书已签发${NC}

下一步：
  1. 启动完整栈（nginx 会自动加载证书）：
       docker compose -f docker-compose.prod.yml up -d

  2. 续期由 certbot 容器自动完成（每 12 小时检查一次，到期前 30 天真正续）

  3. 验证 HTTPS：
       curl -I https://${SERVER_NAME}/actuator/health

${YELLOW}提示${NC}：certbot/ 目录含私钥，已被 .gitignore 排除。切勿提交到 git。

EOF
