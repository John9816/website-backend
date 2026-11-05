#!/bin/bash
#
# 一键修复前端崩溃 - 请直接运行这个脚本
# ./quick-deploy.sh
#

set -e

echo ""
echo "🚀 正在修复前端崩溃问题..."
echo ""

# 检查jar文件是否存在
if [ ! -f "target/website-0.0.1-SNAPSHOT.jar" ]; then
    echo "❌ 错误：找不到 target/website-0.0.1-SNAPSHOT.jar"
    echo "请先运行：mvn clean package -DskipTests"
    exit 1
fi

echo "✓ jar文件已准备好"
echo ""

# 上传jar
echo "📤 正在上传jar到ECS..."
scp target/website-0.0.1-SNAPSHOT.jar root@8.156.95.93:/opt/website/current/backend/website.jar
echo "✓ 上传完成"
echo ""

# 重启服务
echo "🔄 正在重启后端服务..."
ssh root@8.156.95.93 "systemctl restart website-api"
echo "✓ 服务已重启"
echo ""

# 等待启动
echo "⏳ 等待服务启动（5秒）..."
sleep 5

# 检查状态
echo "🔍 检查服务状态..."
ssh root@8.156.95.93 "systemctl is-active website-api" && echo "✓ 服务运行中" || echo "❌ 服务未运行"
echo ""

# 测试API
echo "🧪 测试修复后的API..."
echo ""

echo "1️⃣  测试 /nav/categories (旧路径):"
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" https://api.751152.xyz/nav/categories)
if [ "$HTTP_CODE" = "200" ]; then
    echo "   ✅ HTTP $HTTP_CODE - 修复成功！"
else
    echo "   ❌ HTTP $HTTP_CODE - 仍有问题"
fi

echo ""
echo "2️⃣  测试前端页面:"
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" https://hi.751152.xyz/)
if [ "$HTTP_CODE" = "200" ]; then
    echo "   ✅ HTTP $HTTP_CODE - 前端正常！"
else
    echo "   ❌ HTTP $HTTP_CODE - 前端异常"
fi

echo ""
echo "======================================"
if [ "$HTTP_CODE" = "200" ]; then
    echo "✅ 修复完成！前端应该已经恢复正常"
    echo ""
    echo "请访问: https://hi.751152.xyz/"
else
    echo "⚠️  部署完成，但可能需要进一步检查"
    echo ""
    echo "查看日志: ssh root@8.156.95.93 'journalctl -u website -n 50'"
fi
echo "======================================"
echo ""
