#!/bin/bash
set -e

echo "======================================"
echo "修复前端崩溃问题 - 快速部署"
echo "======================================"

ECS_HOST="8.156.95.93"
ECS_USER="root"
JAR_FILE="target/website-0.0.1-SNAPSHOT.jar"

echo ""
echo "1. 上传jar包到ECS..."
scp $JAR_FILE $ECS_USER@$ECS_HOST:/opt/website/website.jar

echo ""
echo "2. 重启后端服务..."
ssh $ECS_USER@$ECS_HOST << 'ENDSSH'
systemctl restart website
sleep 3
systemctl status website --no-pager
ENDSSH

echo ""
echo "3. 测试修复后的API..."
echo ""

# 测试导航API（旧路径）
echo "测试 /nav/categories ..."
curl -s -o /dev/null -w "HTTP %{http_code}\n" https://api.751152.xyz/nav/categories

# 测试音乐搜索（旧路径）
echo "测试 /music/search ..."
curl -s -o /dev/null -w "HTTP %{http_code}\n" "https://api.751152.xyz/music/search?source=netease&keyword=test"

echo ""
echo "======================================"
echo "✅ 部署完成！"
echo "======================================"
echo ""
echo "前端应该已经恢复正常。"
echo "访问: https://hi.751152.xyz/"
echo ""
