#!/usr/bin/env bash
# test_references_sse.sh - 测试 RAG 对话 references SSE 事件
set -euo pipefail

BASE_URL="http://localhost:8080/api/ragent"
QUESTION="Spring Bean 的生命周期是什么"

echo "=== RAG References SSE 测试 ==="
echo ""

# Step 1: 登录获取 token
echo "[1/4] 登录..."
LOGIN_RESP=$(curl -s "$BASE_URL/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin"}')

TOKEN=$(echo "$LOGIN_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['token'])")
echo "  Token: ${TOKEN:0:16}..."

# Step 2: 发送 SSE 请求，捕获完整的事件流
echo ""
echo "[2/4] 发送问题: \"$QUESTION\""
echo "  等待 SSE 事件流..."
echo ""

ENCODED_Q=$(python3 -c "import urllib.parse; print(urllib.parse.quote('$QUESTION'))")

SSE_OUTPUT=$(curl -s -N --max-time 60 \
  "$BASE_URL/rag/v3/chat?question=$ENCODED_Q" \
  -H "Authorization: $TOKEN" \
  -H "Accept: text/event-stream" 2>&1 || true)

# Step 3: 解析事件
echo "[3/4] 收到的 SSE 事件类型:"
echo "$SSE_OUTPUT" | grep "^event:" | sort | uniq -c | sort -rn
echo ""

# Step 4: 检查 references 事件
echo "[4/4] 检查 references 事件:"
HAS_REF=$(echo "$SSE_OUTPUT" | grep -c "^event:.*references" || true)

if [ "$HAS_REF" -gt 0 ]; then
  echo "  ✅ 收到 references 事件!"
  echo ""
  echo "  references 数据:"
  # 提取 references 事件后面的 data 行
  echo "$SSE_OUTPUT" | awk '/^event:.*references/{found=1; next} found && /^data:/{print; found=0}'
  echo ""

  # 解析并格式化
  REF_DATA=$(echo "$SSE_OUTPUT" | awk '/^event:.*references/{found=1; next} found && /^data:/{sub(/^data: ?/,""); print; found=0}')
  echo "  格式化引用:"
  echo "$REF_DATA" | python3 -c "
import sys, json
try:
    data = json.loads(sys.stdin.read())
    for i, ref in enumerate(data, 1):
        print(f'    [{i}] 文档: {ref.get(\"documentName\", \"N/A\")}')
        print(f'        知识库: {ref.get(\"knowledgeBaseName\", \"N/A\")}')
        score = ref.get('score')
        if score is not None:
            print(f'        匹配度: {score*100:.0f}%')
        preview = ref.get('textPreview', '')
        if preview:
            print(f'        预览: {preview[:60]}...')
        print()
except Exception as e:
    print(f'    解析失败: {e}')
" 2>&1
else
  echo "  ❌ 未收到 references 事件"
  echo ""
  echo "  完整输出 (前 30 行):"
  echo "$SSE_OUTPUT" | head -30
fi

echo ""
echo "=== 测试完成 ==="
