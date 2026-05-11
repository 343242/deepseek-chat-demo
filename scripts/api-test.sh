#!/usr/bin/env bash
# ============================================================
# chat-demo API 全接口测试脚本
# 用法: ./scripts/api-test.sh [BASE_URL]
# 默认: http://localhost:8080
# ============================================================

set -uo pipefail

BASE="${1:-http://localhost:8080}"
PASS=0
FAIL=0
TOTAL=0

GREEN='\033[0;32m'
RED='\033[0;31m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

log_test() { echo -e "\n${CYAN}${BOLD}[TEST] $1${NC}"; }
log_pass() { echo -e "  ${GREEN}✅ PASS${NC} $1"; ((PASS++)); ((TOTAL++)); }
log_fail() { echo -e "  ${RED}❌ FAIL${NC} $1 ${2:+\n       $2}"; ((FAIL++)); ((TOTAL++)); }

jq_get() { echo "$1" | python3 -c "import sys,json;print(json.load(sys.stdin)$2)" 2>/dev/null; }
has_field() { jq_get "$1" ".get('$2','__MISSING__')" 2>/dev/null; }

assert_status() {
    local name="$1" expected="$2" actual="$3"
    if [[ "$actual" == "$expected" ]]; then
        log_pass "$name (HTTP $actual)"
    else
        log_fail "$name" "expected HTTP $expected, got HTTP $actual"
    fi
}

assert_field() {
    local name="$1" resp="$2" field="$3"
    local val
    val=$(has_field "$resp" "$field")
    if [[ "$val" != "__MISSING__" && "$val" != "" ]]; then
        log_pass "$name"
    else
        log_fail "$name" "field '$field' missing"
    fi
}

assert_value() {
    local name="$1" resp="$2" field="$3" expected="$4"
    local actual
    actual=$(jq_get "$resp" ".get('$field','')")
    if [[ "$actual" == "$expected" ]]; then
        log_pass "$name ($field=$actual)"
    else
        log_fail "$name" "expected $field='$expected', got '$actual'"
    fi
}

assert_contains() {
    local name="$1" resp="$2" substr="$3"
    if [[ "$resp" == *"$substr"* ]]; then
        log_pass "$name"
    else
        log_fail "$name" "expected to contain '$substr'"
    fi
}

get_captcha() { curl -s "$BASE/api/auth/captcha"; }

# ==================== 1. 验证码 ====================
log_test "1. 验证码接口"

RESP=$(curl -s -w "\n%{http_code}" "$BASE/api/auth/captcha")
HTTP=$(echo "$RESP" | tail -1)
BODY=$(echo "$RESP" | sed '$d')
assert_status "GET /api/auth/captcha" "200" "$HTTP"
assert_field "返回 captchaId" "$BODY" "captchaId"
assert_field "返回 backgroundImage" "$BODY" "backgroundImage"
assert_field "返回 puzzleImage" "$BODY" "puzzleImage"
assert_field "dev 返回 answer" "$BODY" "answer"

# ==================== 2. 注册 ====================
log_test "2. 正常注册"

CR=$(get_captcha)
CID=$(jq_get "$CR" "['captchaId']")
CANS=$(jq_get "$CR" "['answer']")
TUSER="testuser_$(date +%s)"
TEMAIL="test_${RANDOM}@example.com"

RESP=$(curl -s -w "\n%{http_code}" -X POST "$BASE/api/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"$TUSER\",\"password\":\"Test1234\",\"email\":\"$TEMAIL\",\"captchaId\":\"$CID\",\"captchaCode\":\"$CANS\"}")
HTTP=$(echo "$RESP" | tail -1); BODY=$(echo "$RESP" | sed '$d')
assert_status "注册 $TUSER" "200" "$HTTP"
assert_field "返回 id" "$BODY" "id"
assert_value "返回 username" "$BODY" "username" "$TUSER"

# 2.1 重复用户名
log_test "2.1 重复用户名"
CR=$(get_captcha); CID=$(jq_get "$CR" "['captchaId']"); CANS=$(jq_get "$CR" "['answer']")
RESP=$(curl -s -w "\n%{http_code}" -X POST "$BASE/api/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"$TUSER\",\"password\":\"Test1234\",\"email\":\"a${RANDOM}@x.com\",\"captchaId\":\"$CID\",\"captchaCode\":\"$CANS\"}")
HTTP=$(echo "$RESP" | tail -1)
[[ "$HTTP" == 400 || "$HTTP" == 409 ]] && log_pass "重复用户名被拒绝 (HTTP $HTTP)" || log_fail "重复用户名" "got HTTP $HTTP"

# 2.2 空用户名
log_test "2.2 空用户名"
CR=$(get_captcha); CID=$(jq_get "$CR" "['captchaId']"); CANS=$(jq_get "$CR" "['answer']")
RESP=$(curl -s -w "\n%{http_code}" -X POST "$BASE/api/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"\",\"password\":\"Test1234\",\"email\":\"x@x.com\",\"captchaId\":\"$CID\",\"captchaCode\":\"$CANS\"}")
HTTP=$(echo "$RESP" | tail -1); BODY=$(echo "$RESP" | sed '$d')
[[ "$HTTP" == 4* ]] && log_pass "空用户名被拒 (HTTP $HTTP)" || log_fail "空用户名" "got HTTP $HTTP"

# 2.3 短密码
log_test "2.3 短密码"
CR=$(get_captcha); CID=$(jq_get "$CR" "['captchaId']"); CANS=$(jq_get "$CR" "['answer']")
RESP=$(curl -s -w "\n%{http_code}" -X POST "$BASE/api/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"sp${RANDOM}\",\"password\":\"1234567\",\"email\":\"x${RANDOM}@x.com\",\"captchaId\":\"$CID\",\"captchaCode\":\"$CANS\"}")
HTTP=$(echo "$RESP" | tail -1)
[[ "$HTTP" == 4* ]] && log_pass "短密码被拒 (HTTP $HTTP)" || log_fail "短密码" "got HTTP $HTTP"

# 2.4 无效邮箱
log_test "2.4 无效邮箱"
CR=$(get_captcha); CID=$(jq_get "$CR" "['captchaId']"); CANS=$(jq_get "$CR" "['answer']")
RESP=$(curl -s -w "\n%{http_code}" -X POST "$BASE/api/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"bm${RANDOM}\",\"password\":\"Test1234\",\"email\":\"not-email\",\"captchaId\":\"$CID\",\"captchaCode\":\"$CANS\"}")
HTTP=$(echo "$RESP" | tail -1)
[[ "$HTTP" == 4* ]] && log_pass "无效邮箱被拒 (HTTP $HTTP)" || log_fail "无效邮箱" "got HTTP $HTTP"

# 2.5 错误验证码
log_test "2.5 错误验证码"
RESP=$(curl -s -w "\n%{http_code}" -X POST "$BASE/api/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"fk${RANDOM}\",\"password\":\"Test1234\",\"email\":\"f${RANDOM}@x.com\",\"captchaId\":\"fake\",\"captchaCode\":\"999\"}")
HTTP=$(echo "$RESP" | tail -1)
[[ "$HTTP" == 4* ]] && log_pass "错误验证码被拒 (HTTP $HTTP)" || log_fail "错误验证码" "got HTTP $HTTP"

# 2.6 空请求体
log_test "2.6 空请求体"
RESP=$(curl -s -w "\n%{http_code}" -X POST "$BASE/api/auth/register" \
    -H "Content-Type: application/json" -d '{}')
HTTP=$(echo "$RESP" | tail -1)
[[ "$HTTP" == 4* ]] && log_pass "空请求体被拒 (HTTP $HTTP)" || log_fail "空请求体" "got HTTP $HTTP"

# ==================== 3. 登录 ====================
log_test "3. 管理员登录"
CR=$(get_captcha); CID=$(jq_get "$CR" "['captchaId']"); CANS=$(jq_get "$CR" "['answer']")

LOGIN_RESP=$(curl -s -D /tmp/chat-hdrs.txt -X POST "$BASE/api/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"admin\",\"password\":\"admin123\",\"captchaId\":\"$CID\",\"captchaCode\":\"$CANS\"}")
HTTP=$(head -1 /tmp/chat-hdrs.txt | awk '{print $2}')
assert_status "管理员登录" "200" "$HTTP"
assert_field "返回 user" "$LOGIN_RESP" "user"

ACCESS_TOKEN=$(grep -i 'set-cookie.*access_token' /tmp/chat-hdrs.txt 2>/dev/null | grep -o 'access_token=[^;]*' | cut -d= -f2 || true)
REFRESH_TOKEN=$(grep -i 'set-cookie.*refresh_token' /tmp/chat-hdrs.txt 2>/dev/null | grep -o 'refresh_token=[^;]*' | cut -d= -f2 || true)

if [[ -n "$ACCESS_TOKEN" ]]; then
    echo -e "  ${GREEN}🔑 token 已获取${NC}"
else
    echo -e "  ${RED}❌ 无法获取 token${NC}"
fi

AUTH="-H \"Authorization: Bearer $ACCESS_TOKEN\""

# 3.1 错误密码
log_test "3.1 错误密码"
CR=$(get_captcha); CID=$(jq_get "$CR" "['captchaId']"); CANS=$(jq_get "$CR" "['answer']")
RESP=$(curl -s -w "\n%{http_code}" -X POST "$BASE/api/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"admin\",\"password\":\"wrong\",\"captchaId\":\"$CID\",\"captchaCode\":\"$CANS\"}")
HTTP=$(echo "$RESP" | tail -1)
[[ "$HTTP" == 4* ]] && log_pass "错误密码被拒 (HTTP $HTTP)" || log_fail "错误密码" "got HTTP $HTTP"

# ==================== 4. 当前用户 ====================
log_test "4. 用户信息"
RESP=$(curl -s -w "\n%{http_code}" -b "access_token=$ACCESS_TOKEN" "$BASE/api/auth/me")
HTTP=$(echo "$RESP" | tail -1); BODY=$(echo "$RESP" | sed '$d')
assert_status "GET /api/auth/me" "200" "$HTTP"
assert_value "username=admin" "$BODY" "username" "admin"
assert_field "返回 roles" "$BODY" "roles"

# 4.1 未认证
RESP=$(curl -s -w "\n%{http_code}" "$BASE/api/auth/me")
HTTP=$(echo "$RESP" | tail -1)
assert_status "未认证 401" "401" "$HTTP"

# ==================== 5. 模型列表 ====================
log_test "5. 模型列表"
RESP=$(curl -s -w "\n%{http_code}" -b "access_token=$ACCESS_TOKEN" "$BASE/api/models")
HTTP=$(echo "$RESP" | tail -1); BODY=$(echo "$RESP" | sed '$d')
assert_status "GET /api/models" "200" "$HTTP"
MC=$(echo "$BODY" | python3 -c "import sys,json;d=json.load(sys.stdin);print(len(d) if isinstance(d,list) else 'obj')" 2>/dev/null || echo "?")
echo -e "  ${CYAN}   模型数: $MC${NC}"

FIRST_MODEL=$(echo "$BODY" | python3 -c "import sys,json;d=json.load(sys.stdin);print(d[0]['id'] if isinstance(d,list) and d else '')" 2>/dev/null || echo "deepseek-chat")
echo -e "  ${CYAN}   使用模型: $FIRST_MODEL${NC}"

# ==================== 6. 模型刷新 ====================
log_test "6. 模型刷新"
RESP=$(curl -s -w "\n%{http_code}" -b "access_token=$ACCESS_TOKEN" -X POST "$BASE/api/models/refresh")
HTTP=$(echo "$RESP" | tail -1)
assert_status "POST /api/models/refresh" "200" "$HTTP"

# ==================== 7. 阻塞式聊天 ====================
log_test "7. 阻塞式聊天"
RESP=$(curl -s -w "\n%{http_code}" --max-time 60 -b "access_token=$ACCESS_TOKEN" \
    -X POST "$BASE/api/chat" -H "Content-Type: application/json" \
    -d "{\"model\":\"$FIRST_MODEL\",\"message\":\"说一个字：好\"}")
HTTP=$(echo "$RESP" | tail -1); BODY=$(echo "$RESP" | sed '$d')
assert_status "POST /api/chat" "200" "$HTTP"
assert_field "返回 model" "$BODY" "model"
assert_field "返回 content" "$BODY" "content"
CONTENT=$(jq_get "$BODY" ".get('content','')" | head -c 100)
echo -e "  ${CYAN}   回复: $CONTENT${NC}"

# 7.1 空消息
log_test "7.1 空消息"
RESP=$(curl -s -w "\n%{http_code}" -b "access_token=$ACCESS_TOKEN" \
    -X POST "$BASE/api/chat" -H "Content-Type: application/json" \
    -d "{\"model\":\"$FIRST_MODEL\",\"message\":\"\"}")
HTTP=$(echo "$RESP" | tail -1)
[[ "$HTTP" == 4* ]] && log_pass "空消息被拒 (HTTP $HTTP)" || log_fail "空消息" "got HTTP $HTTP"

# 7.2 不存在的模型
log_test "7.2 不存在的模型"
RESP=$(curl -s -w "\n%{http_code}" --max-time 10 -b "access_token=$ACCESS_TOKEN" \
    -X POST "$BASE/api/chat" -H "Content-Type: application/json" \
    -d "{\"model\":\"nonexistent-xyz\",\"message\":\"hi\"}")
HTTP=$(echo "$RESP" | tail -1)
[[ "$HTTP" == 4* ]] && log_pass "不存在模型被拒 (HTTP $HTTP)" || log_fail "不存在模型" "got HTTP $HTTP"

# ==================== 8. 流式 GET ====================
log_test "8. 流式 GET"
RESP=$(curl -s -w "\n%{http_code}" --max-time 60 -b "access_token=$ACCESS_TOKEN" \
    -G "$BASE/api/chat/stream" --data-urlencode "model=$FIRST_MODEL" --data-urlencode "message=说一个字：好")
HTTP=$(echo "$RESP" | tail -1); BODY=$(echo "$RESP" | sed '$d')
assert_status "GET /api/chat/stream" "200" "$HTTP"
[[ "$BODY" == *"data:"* ]] && log_pass "SSE 格式验证" || log_fail "SSE 格式" "expected 'data:' prefix"

# ==================== 9. 流式 POST ====================
log_test "9. 流式 POST"
RESP=$(curl -s -w "\n%{http_code}" --max-time 60 -b "access_token=$ACCESS_TOKEN" \
    -X POST "$BASE/api/chat/stream" -H "Content-Type: application/json" \
    -d "{\"model\":\"$FIRST_MODEL\",\"message\":\"说一个字：好\"}")
HTTP=$(echo "$RESP" | tail -1)
assert_status "POST /api/chat/stream" "200" "$HTTP"

# ==================== 10. 多轮对话 ====================
log_test "10. 多轮对话"
RESP=$(curl -s -w "\n%{http_code}" --max-time 60 -b "access_token=$ACCESS_TOKEN" \
    -X POST "$BASE/api/chat" -H "Content-Type: application/json" \
    -d "{\"model\":\"$FIRST_MODEL\",\"message\":\"我叫小明\",\"conversationId\":\"test-conv-1\",\"mode\":\"MULTI_TURN\"}")
HTTP=$(echo "$RESP" | tail -1)
assert_status "多轮第1轮" "200" "$HTTP"

RESP=$(curl -s -w "\n%{http_code}" --max-time 60 -b "access_token=$ACCESS_TOKEN" \
    -X POST "$BASE/api/chat" -H "Content-Type: application/json" \
    -d "{\"model\":\"$FIRST_MODEL\",\"message\":\"我叫什么？\",\"conversationId\":\"test-conv-1\",\"mode\":\"MULTI_TURN\"}")
HTTP=$(echo "$RESP" | tail -1); BODY=$(echo "$RESP" | sed '$d')
assert_status "多轮第2轮" "200" "$HTTP"
CONTENT=$(jq_get "$BODY" ".get('content','')" | head -c 200)
[[ "$CONTENT" == *"小明"* ]] && log_pass "多轮记忆生效" || log_fail "多轮记忆" "回复: $CONTENT"

# ==================== 11. 对话管理 ====================
log_test "11. 对话管理"

RESP=$(curl -s -w "\n%{http_code}" -b "access_token=$ACCESS_TOKEN" "$BASE/api/conversations")
HTTP=$(echo "$RESP" | tail -1)
assert_status "GET /api/conversations" "200" "$HTTP"

RESP=$(curl -s -w "\n%{http_code}" -b "access_token=$ACCESS_TOKEN" "$BASE/api/conversations/test-conv-1")
HTTP=$(echo "$RESP" | tail -1)
assert_status "GET /api/conversations/{id}" "200" "$HTTP"

RESP=$(curl -s -w "\n%{http_code}" -b "access_token=$ACCESS_TOKEN" "$BASE/api/conversations/test-conv-1/export")
HTTP=$(echo "$RESP" | tail -1)
assert_status "GET /api/conversations/{id}/export" "200" "$HTTP"

RESP=$(curl -s -w "\n%{http_code}" -b "access_token=$ACCESS_TOKEN" -X DELETE "$BASE/api/conversations/test-conv-1")
HTTP=$(echo "$RESP" | tail -1)
assert_status "DELETE /api/conversations/{id}" "200" "$HTTP"

# ==================== 12. System Prompt ====================
log_test "12. System Prompt"
RESP=$(curl -s -w "\n%{http_code}" -b "access_token=$ACCESS_TOKEN" "$BASE/api/prompts")
HTTP=$(echo "$RESP" | tail -1)
assert_status "GET /api/prompts" "200" "$HTTP"

RESP=$(curl -s -w "\n%{http_code}" -b "access_token=$ACCESS_TOKEN" "$BASE/api/prompts/default")
HTTP=$(echo "$RESP" | tail -1)
assert_status "GET /api/prompts/default" "200" "$HTTP"

# ==================== 13. 模型参数 ====================
log_test "13. 模型参数"
RESP=$(curl -s -w "\n%{http_code}" -b "access_token=$ACCESS_TOKEN" "$BASE/api/models/params")
HTTP=$(echo "$RESP" | tail -1)
assert_status "GET /api/models/params" "200" "$HTTP"

# ==================== 14. 用量统计 ====================
log_test "14. 用量统计"
RESP=$(curl -s -w "\n%{http_code}" -b "access_token=$ACCESS_TOKEN" "$BASE/api/usage/records?page=1&size=10")
HTTP=$(echo "$RESP" | tail -1)
assert_status "GET /api/usage/records" "200" "$HTTP"

RESP=$(curl -s -w "\n%{http_code}" -b "access_token=$ACCESS_TOKEN" "$BASE/api/usage/stats/model")
HTTP=$(echo "$RESP" | tail -1)
assert_status "GET /api/usage/stats/model" "200" "$HTTP"

RESP=$(curl -s -w "\n%{http_code}" -b "access_token=$ACCESS_TOKEN" "$BASE/api/usage/stats/conversation")
HTTP=$(echo "$RESP" | tail -1)
assert_status "GET /api/usage/stats/conversation" "200" "$HTTP"

# ==================== 15. Token 刷新 ====================
log_test "15. Token 刷新"
if [[ -n "$REFRESH_TOKEN" ]]; then
    RESP=$(curl -s -w "\n%{http_code}" -X POST "$BASE/api/auth/refresh" \
        -H "Content-Type: application/json" -d "{\"refreshToken\":\"$REFRESH_TOKEN\"}")
    HTTP=$(echo "$RESP" | tail -1)
    assert_status "POST /api/auth/refresh" "200" "$HTTP"
else
    log_fail "Token 刷新" "无 refresh_token"
fi

# ==================== 16. 登出 ====================
log_test "16. 登出"
RESP=$(curl -s -w "\n%{http_code}" -b "access_token=$ACCESS_TOKEN" -X POST "$BASE/api/auth/logout")
HTTP=$(echo "$RESP" | tail -1)
assert_status "POST /api/auth/logout" "200" "$HTTP"

RESP=$(curl -s -w "\n%{http_code}" -b "access_token=$ACCESS_TOKEN" "$BASE/api/auth/me")
HTTP=$(echo "$RESP" | tail -1)
assert_status "登出后 401" "401" "$HTTP"

# ==================== 汇总 ====================
echo ""
echo -e "${BOLD}════════════════════════════════════════${NC}"
echo -e "${BOLD} 总计: $TOTAL | ${GREEN}通过: $PASS${NC} | ${RED}失败: $FAIL${NC} ${NC}"
echo -e "${BOLD}════════════════════════════════════════${NC}"
[[ $FAIL -eq 0 ]] && echo -e "\n${GREEN}${BOLD}🎉 全部通过！${NC}" || echo -e "\n${RED}${BOLD}⚠️  $FAIL 个失败${NC}"
exit $FAIL
