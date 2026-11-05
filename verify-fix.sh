#!/bin/bash
#
# 验证前端修复是否成功
#

echo ""
echo "🧪 验证前端修复状态..."
echo "========================================"
echo ""

# 测试1: 旧路径 /nav/categories
echo "1️⃣  测试 /nav/categories (旧路径):"
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" https://api.751152.xyz/nav/categories)
if [ "$HTTP_CODE" = "200" ]; then
    echo "   ✅ HTTP $HTTP_CODE - 修复成功！"
    FIXED=1
elif [ "$HTTP_CODE" = "401" ]; then
    echo "   ❌ HTTP $HTTP_CODE - 仍然返回401，服务可能还没重启"
    FIXED=0
else
    echo "   ⚠️  HTTP $HTTP_CODE - 异常状态"
    FIXED=0
fi

echo ""

# 测试2: 新路径 /api/public/nav
echo "2️⃣  测试 /api/public/nav (新路径):"
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" https://api.751152.xyz/api/public/nav)
if [ "$HTTP_CODE" = "200" ]; then
    echo "   ✅ HTTP $HTTP_CODE - 新路径正常"
else
    echo "   ❌ HTTP $HTTP_CODE - 新路径异常"
fi

echo ""

# 测试3: 前端页面
echo "3️⃣  测试前端页面:"
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" https://hi.751152.xyz/)
if [ "$HTTP_CODE" = "200" ]; then
    echo "   ✅ HTTP $HTTP_CODE - 前端页面可访问"
else
    echo "   ❌ HTTP $HTTP_CODE - 前端页面异常"
fi

echo ""
echo "========================================"

if [ "$FIXED" = "1" ]; then
    echo "✅ 修复成功！前端应该已经恢复正常"
    echo ""
    echo "请访问: https://hi.751152.xyz/"
    echo "浏览器控制台应该没有401错误"
else
    echo "⚠️  修复尚未生效"
    echo ""
    echo "可能的原因："
    echo "1. 服务还没重启"
    echo "2. 服务正在启动中（需要10-20秒）"
    echo ""
    echo "请执行: ssh root@8.156.95.93 'systemctl restart website-api'"
    echo "然后等待10秒后再次运行此脚本"
fi

echo "========================================"
echo ""
