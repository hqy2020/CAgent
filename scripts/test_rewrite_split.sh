#!/usr/bin/env bash
# ────────────────────────────────────────────────
# 问题重写与拆分 E2E 烟雾测试
# 通过 SSE 接口验证 rewrite + split 的端到端表现
# ────────────────────────────────────────────────
set -euo pipefail

BASE_URL="${RAGENT_BASE_URL:-http://localhost:8080}"
KB_ID="${RAGENT_KB_ID:-1}"
TOKEN="${RAGENT_TOKEN:-}"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

pass=0
fail=0

log_pass() { echo -e "${GREEN}[PASS]${NC} $1"; ((pass++)); }
log_fail() { echo -e "${RED}[FAIL]${NC} $1"; ((fail++)); }
log_info() { echo -e "${YELLOW}[INFO]${NC} $1"; }

# 发送 SSE 聊天请求，返回完整的 SSE 事件流
do_chat() {
    local question="$1"
    local conversation_id="${2:-}"

    local url="${BASE_URL}/rag/v3/chat?knowledgeBaseId=${KB_ID}&question=$(python3 -c "import urllib.parse; print(urllib.parse.quote('$question'))")"

    if [ -n "$conversation_id" ]; then
        url="${url}&conversationId=${conversation_id}"
    fi

    local headers=()
    if [ -n "$TOKEN" ]; then
        headers+=(-H "Authorization: ${TOKEN}")
    fi

    curl -sS -N --max-time 30 "${headers[@]}" "$url" 2>/dev/null || true
}

# 从 SSE 事件流中提取 meta 事件的 conversationId
extract_conversation_id() {
    local sse_output="$1"
    echo "$sse_output" | grep "^data:" | head -1 | python3 -c "
import sys, json
for line in sys.stdin:
    line = line.strip()
    if line.startswith('data:'):
        try:
            obj = json.loads(line[5:])
            if 'conversationId' in obj:
                print(obj['conversationId'])
                break
        except: pass
" 2>/dev/null || echo ""
}

echo "========================================"
echo "  问题重写与拆分 E2E 烟雾测试"
echo "  Base URL: ${BASE_URL}"
echo "========================================"
echo ""

# ────────────────────────────────────────
# 场景 A：首轮提问 — 单一明确问题
# ────────────────────────────────────────
log_info "场景 A：首轮提问 — '12306的订单流程是什么？'"
SSE_A=$(do_chat "12306的订单流程是什么？")

if echo "$SSE_A" | grep -q "event: message"; then
    log_pass "场景 A：收到 message 事件"
else
    log_fail "场景 A：未收到 message 事件"
fi

CONV_ID=$(extract_conversation_id "$SSE_A")
if [ -n "$CONV_ID" ]; then
    log_pass "场景 A：获取到 conversationId=${CONV_ID}"
else
    log_fail "场景 A：未获取到 conversationId"
fi

echo ""

# ────────────────────────────────────────
# 场景 B：二轮指代 — 上下文消解
# ────────────────────────────────────────
if [ -n "$CONV_ID" ]; then
    log_info "场景 B：二轮指代 — '它的支付环节怎么处理？' (conversationId=${CONV_ID})"
    SSE_B=$(do_chat "它的支付环节怎么处理？" "$CONV_ID")

    if echo "$SSE_B" | grep -q "event: message"; then
        log_pass "场景 B：收到 message 事件"
    else
        log_fail "场景 B：未收到 message 事件"
    fi

    # 检查是否有 finish 或 done 事件
    if echo "$SSE_B" | grep -q "event: done\|event: finish"; then
        log_pass "场景 B：会话正常结束"
    else
        log_fail "场景 B：未检测到结束事件"
    fi
else
    log_info "场景 B：跳过（无 conversationId）"
fi

echo ""

# ────────────────────────────────────────
# 场景 C：复合问题 — 多问句拆分
# ────────────────────────────────────────
log_info "场景 C：复合问题 — 'OA审批流程怎么走？Redis地址是多少？'"
SSE_C=$(do_chat "OA审批流程怎么走？Redis地址是多少？")

if echo "$SSE_C" | grep -q "event: message"; then
    log_pass "场景 C：收到 message 事件"
else
    log_fail "场景 C：未收到 message 事件"
fi

if echo "$SSE_C" | grep -q "event: done\|event: finish"; then
    log_pass "场景 C：会话正常结束"
else
    log_fail "场景 C：未检测到结束事件"
fi

echo ""

# ────────────────────────────────────────
# 总结
# ────────────────────────────────────────
echo "========================================"
echo -e "  结果: ${GREEN}${pass} passed${NC}, ${RED}${fail} failed${NC}"
echo "========================================"
echo ""
echo "提示：请检查后端日志中 'RAG用户问题查询改写+拆分' 条目，验证："
echo "  1. 场景 B 的改写结果中应包含 '12306'（指代消解）"
echo "  2. 场景 C 应出现 2 个子问题（多问句拆分）"

exit $fail
