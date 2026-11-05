#!/bin/bash
set -e

ECS_HOST="8.156.95.93"
ECS_USER="root"

echo "🚨 紧急回滚到上一个版本..."
echo ""

ssh ${ECS_USER}@${ECS_HOST} << 'ENDSSH'
set -e

cd /opt/website

echo "=== 当前版本 ==="
readlink current

echo ""
echo "=== 上一版本 ==="
readlink previous

echo ""
echo "➤ 停止服务..."
systemctl stop website-api

echo "➤ 回滚到previous版本..."
rm -f current
ln -sf $(readlink previous) current

echo "➤ 验证符号链接..."
ls -lh current

echo "➤ 启动服务..."
systemctl start website-api

sleep 3

echo "➤ 检查服务状态..."
systemctl status website-api --no-pager | head -15

echo ""
echo "✓ 回滚完成！"
echo "当前版本: $(readlink current)"
ENDSSH

echo ""
echo "✅ 回滚成功！正在验证..."
sleep 5

# 健康检查
if ssh ${ECS_USER}@${ECS_HOST} "curl -sf http://localhost:8090/health > /dev/null 2>&1"; then
    echo "✅ 应用启动正常！"
else
    echo "⚠️  应用可能还在启动中，请手动检查："
    echo "   ssh ${ECS_USER}@${ECS_HOST} 'journalctl -u website-api -f'"
fi
