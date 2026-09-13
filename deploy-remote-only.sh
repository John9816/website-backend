#!/bin/bash
# 仅在ECS上执行的部署脚本（跳过本地编译）

set -e

echo "========================================"
echo "🚀 远程ECS部署脚本"
echo "========================================"

ECS_HOST="8.156.95.93"
ECS_USER="root"
DB_PASS="wangweijie1314."

echo ""
echo "步骤 1: 连接到ECS并拉取代码..."
ssh ${ECS_USER}@${ECS_HOST} << 'ENDSSH'
set -e
cd /opt/website
git fetch origin
git checkout codex/tvbox-provider
git pull origin codex/tvbox-provider || echo "拉取完成"
echo "✓ 代码更新完成"
ENDSSH

echo ""
echo "步骤 2: 在ECS上编译..."
ssh ${ECS_USER}@${ECS_HOST} << 'ENDSSH'
set -e
cd /opt/website/current/backend
mvn clean package -DskipTests
ls -lh target/website-*.jar
echo "✓ 编译完成"
ENDSSH

echo ""
echo "步骤 3: 备份并执行数据库迁移..."
ssh ${ECS_USER}@${ECS_HOST} << 'ENDSSH'
set -e
BACKUP_FILE="/backup/website_$(date +%Y%m%d_%H%M%S).sql"
mkdir -p /backup
mysqldump -u root -pwangweijie1314. website > ${BACKUP_FILE}
echo "✓ 数据库已备份: ${BACKUP_FILE}"

mysql -u root -pwangweijie1314. website < /opt/website/current/backend/db/migrations/0011_kb_content_enhancements.sql
echo "✓ 数据库迁移完成"

mysql -u root -pwangweijie1314. website -e "SELECT COUNT(*) as tables FROM information_schema.tables WHERE table_schema='website' AND table_name IN ('kb_document', 'content_template', 'content_automation_task');"
ENDSSH

echo ""
echo "步骤 4: 重启服务..."
ssh ${ECS_USER}@${ECS_HOST} << 'ENDSSH'
set -e
systemctl restart website-api
sleep 5
systemctl status website-api --no-pager | head -10
echo "✓ 服务重启完成"
ENDSSH

echo ""
echo "步骤 5: 验证部署..."
sleep 10

echo "健康检查..."
curl -s https://api.751152.xyz/actuator/health | grep -o '"status":"[^"]*"'

echo "API端点检查..."
curl -s -o /dev/null -w "知识库API: %{http_code}\n" https://api.751152.xyz/api/kb/spaces
curl -s -o /dev/null -w "内容模板API: %{http_code}\n" https://api.751152.xyz/api/content/templates
curl -s -o /dev/null -w "自动化任务API: %{http_code}\n" https://api.751152.xyz/api/content/automation

echo ""
echo "========================================"
echo "✅ 部署完成！"
echo "========================================"
echo "查看日志: ssh root@8.156.95.93 'journalctl -u website-api -f'"
