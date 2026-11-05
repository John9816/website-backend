#!/bin/bash

echo "======================================"
echo "本地测试API修复"
echo "======================================"
echo ""

API_BASE="https://api.751152.xyz"

echo "测试1: /nav/categories (旧路径)"
echo "----------------------------------------"
RESPONSE=$(curl -s -w "\nHTTP_CODE:%{http_code}" "$API_BASE/nav/categories")
HTTP_CODE=$(echo "$RESPONSE" | grep "HTTP_CODE:" | cut -d: -f2)
BODY=$(echo "$RESPONSE" | sed '/HTTP_CODE:/d')

if [ "$HTTP_CODE" = "200" ]; then
    echo "✅ 成功! HTTP $HTTP_CODE"
    echo "$BODY" | jq -r '.data[0].name' 2>/dev/null || echo "$BODY" | head -c 100
else
    echo "❌ 失败! HTTP $HTTP_CODE"
    echo "$BODY" | head -c 200
fi

echo ""
echo "测试2: /api/public/nav (新路径)"
echo "----------------------------------------"
RESPONSE=$(curl -s -w "\nHTTP_CODE:%{http_code}" "$API_BASE/api/public/nav")
HTTP_CODE=$(echo "$RESPONSE" | grep "HTTP_CODE:" | cut -d: -f2)
BODY=$(echo "$RESPONSE" | sed '/HTTP_CODE:/d')

if [ "$HTTP_CODE" = "200" ]; then
    echo "✅ 成功! HTTP $HTTP_CODE"
    echo "$BODY" | jq -r '.data[0].name' 2>/dev/null || echo "$BODY" | head -c 100
else
    echo "❌ 失败! HTTP $HTTP_CODE"
    echo "$BODY" | head -c 200
fi

echo ""
echo "测试3: /music/search (旧路径，需要认证)"
echo "----------------------------------------"
RESPONSE=$(curl -s -w "\nHTTP_CODE:%{http_code}" "$API_BASE/music/search?source=netease&keyword=test")
HTTP_CODE=$(echo "$RESPONSE" | grep "HTTP_CODE:" | cut -d: -f2)

# 401是预期的（未认证），200也是可以的（如果已认证）
if [ "$HTTP_CODE" = "401" ] || [ "$HTTP_CODE" = "200" ]; then
    echo "✅ 端点存在! HTTP $HTTP_CODE (预期401或200)"
else
    echo "❌ 失败! HTTP $HTTP_CODE"
fi

echo ""
echo "测试4: 前端页面"
echo "----------------------------------------"
RESPONSE=$(curl -s -w "\nHTTP_CODE:%{http_code}" "https://hi.751152.xyz/")
HTTP_CODE=$(echo "$RESPONSE" | grep "HTTP_CODE:" | cut -d: -f2)

if [ "$HTTP_CODE" = "200" ]; then
    echo "✅ 前端正常! HTTP $HTTP_CODE"
else
    echo "❌ 前端异常! HTTP $HTTP_CODE"
fi

echo ""
echo "======================================"
echo "测试完成"
echo "======================================"
