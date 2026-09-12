#!/bin/bash
# 一键部署性能优化版本到ECS

set -euo pipefail

ECS_IP="8.156.95.93"
ECS_USER="root"
ECS_PASSWORD="wangweijie1314."

echo "=== 性能优化版本部署到ECS ==="
echo "ECS地址: $ECS_IP"
echo "时间: $(date '+%Y-%m-%d %H:%M:%S')"
echo ""

# 1. 部署应用
echo "[1/4] 部署应用到ECS..."
./deploy/production/deploy.sh \
  --host $ECS_IP \
  --user $ECS_USER

if [ $? -ne 0 ]; then
    echo "❌ 部署失败"
    exit 1
fi

echo "✓ 应用部署成功"
echo ""

# 2. 系统优化
echo "[2/4] 执行ECS系统优化..."
ssh ${ECS_USER}@${ECS_IP} << 'ENDSSH'
cd /opt/website/current/deploy/production
sudo bash optimize-ecs.sh

echo ""
echo "系统优化完成，建议重启ECS以应用所有配置"
echo "执行: sudo reboot"
ENDSSH

echo "✓ 系统优化脚本已执行"
echo ""

# 3. 数据库索引
echo "[3/4] 数据库索引优化..."
echo "请在ECS上手动执行以下命令（需要MySQL密码）："
echo ""
echo "ssh ${ECS_USER}@${ECS_IP}"
echo "mysqldump -u root -p website > /backup/website_\$(date +%Y%m%d_%H%M%S).sql"
echo "mysql -u root -p website < /opt/website/current/backend/db/migrations/0010_performance_indexes.sql"
echo ""

# 4. 验证
echo "[4/4] 验证部署..."
echo "等待5秒让服务完全启动..."
sleep 5

# 健康检查
echo -n "健康检查: "
if curl -s -f "http://${ECS_IP}:8090/actuator/health" > /dev/null; then
    echo "✓ 通过"
else
    echo "❌ 失败"
    echo "请检查服务状态: ssh ${ECS_USER}@${ECS_IP} 'journalctl -u website-api -f'"
fi

echo ""
echo "=== 部署完成 ==="
echo ""
echo "下一步操作："
echo "1. 重启ECS应用系统优化: ssh ${ECS_USER}@${ECS_IP} 'sudo reboot'"
echo "2. 执行数据库索引优化（见上方命令）"
echo "3. 验证性能: API_HOST=https://api.751152.xyz ./scripts/verify-performance.sh"
echo "4. 压力测试: API_HOST=https://api.751152.xyz ./scripts/benchmark.sh"
echo ""
echo "监控命令："
echo "  ssh ${ECS_USER}@${ECS_IP} '/opt/website/scripts/monitor-performance.sh'"
echo ""
echo "文档："
echo "  docs/performance-optimization.md - 完整优化方案"
echo "  docs/performance-quickstart.md - 快速开始"
echo "  docs/performance-release-checklist.md - 发布清单"
