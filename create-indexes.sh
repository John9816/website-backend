#!/bin/bash
# 数据库索引创建脚本
# 用途: 为website数据库创建性能优化索引
# 建议执行时间: 凌晨2:00-5:00

set -e  # 遇到错误立即退出

ECS_HOST="${ECS_HOST:?Set ECS_HOST to the server hostname or IP}"
ECS_USER="${ECS_USER:-root}"
ECS_SSH_KEY="${ECS_SSH_KEY:-}"
MYSQL_DEFAULTS_FILE=/root/.config/website/mysql.cnf
SSH_ARGS=(-o BatchMode=yes -o ConnectTimeout=10)
if [ -n "$ECS_SSH_KEY" ]; then
  SSH_ARGS+=(-i "$ECS_SSH_KEY")
fi
ssh_ecs() { ssh "${SSH_ARGS[@]}" "${ECS_USER}@${ECS_HOST}" "$@"; }

[ "$ECS_USER" = root ] || { echo 'This script requires a root SSH key to read the protected MySQL option file.' >&2; exit 1; }
ssh_ecs "test -f '$MYSQL_DEFAULTS_FILE' && test -r '$MYSQL_DEFAULTS_FILE' && test \"\$(stat -c %u '$MYSQL_DEFAULTS_FILE')\" = 0 && test \"\$(stat -c %a '$MYSQL_DEFAULTS_FILE')\" = 600" || {
  echo "Create $MYSQL_DEFAULTS_FILE on ECS as root with mode 600 before running this script." >&2
  exit 1
}

echo "╔════════════════════════════════════════════════════════════╗"
echo "║          数据库索引创建脚本 v1.0                           ║"
echo "║          ECS: $ECS_HOST                                  ║"
echo "╚════════════════════════════════════════════════════════════╝"
echo ""

# 步骤1: 检查磁盘空间
echo "📊 [1/6] 检查磁盘空间..."
DISK_AVAIL=$(ssh_ecs 'df -h /var/lib/mysql | tail -1 | awk "{print \$4}"')
echo "  ✓ MySQL目录可用空间: $DISK_AVAIL"
echo ""

# 步骤2: 检查数据库大小
echo "📦 [2/6] 检查数据库大小..."
DB_SIZE=$(ssh_ecs "mysql --defaults-extra-file=$MYSQL_DEFAULTS_FILE -se \"
SELECT ROUND(SUM(data_length + index_length) / 1024 / 1024, 2) AS Size_MB
FROM information_schema.tables
WHERE table_schema = 'website';\" 2>/dev/null")
echo "  ✓ 数据库大小: ${DB_SIZE} MB"
echo ""

# 步骤3: 备份数据库
echo "💾 [3/6] 备份数据库..."
BACKUP_FILE="website_$(date +%Y%m%d_%H%M%S).sql"
ssh_ecs "umask 077; mkdir -p /backup && \
mysqldump --defaults-extra-file=$MYSQL_DEFAULTS_FILE \
  --single-transaction \
  --routines \
  --triggers \
  --events \
  website > /backup/${BACKUP_FILE}.partial && mv /backup/${BACKUP_FILE}.partial /backup/${BACKUP_FILE}"
BACKUP_SIZE=$(ssh_ecs "ls -lh /backup/${BACKUP_FILE} | awk '{print \$5}'")
echo "  ✓ 备份文件: /backup/${BACKUP_FILE} (${BACKUP_SIZE})"
echo ""

# 步骤4: 验证备份完整性
echo "✅ [4/6] 验证备份完整性..."
if ssh_ecs "tail -20 /backup/${BACKUP_FILE} | grep -q 'Dump completed'"; then
  echo "  ✓ 备份文件完整"
else
  echo "  ✗ 备份文件可能损坏，退出"
  exit 1
fi
echo ""

# 步骤5: 创建索引
echo "🚀 [5/6] 创建性能索引..."
echo "  ⏳ 预计耗时: 5-10分钟，请耐心等待..."
START_TIME=$(date +%s)

ssh_ecs "mysql --defaults-extra-file=$MYSQL_DEFAULTS_FILE website < /opt/website/current/backend/db/migrations/0010_performance_indexes.sql"

END_TIME=$(date +%s)
DURATION=$((END_TIME - START_TIME))
echo "  ✓ 索引创建完成，耗时: ${DURATION}秒"
echo ""

# 步骤6: 验证索引
echo "🔍 [6/6] 验证索引创建..."
INDEX_COUNT=$(ssh_ecs "mysql --defaults-extra-file=$MYSQL_DEFAULTS_FILE website -se \"
SELECT COUNT(*)
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = 'website' AND INDEX_NAME LIKE 'idx_%';\" 2>/dev/null")
echo "  ✓ 新增性能索引数量: ${INDEX_COUNT}"
echo ""

# 索引详情
echo "📋 索引详情:"
ssh_ecs "mysql --defaults-extra-file=$MYSQL_DEFAULTS_FILE website -se \"
SELECT
    TABLE_NAME as '表名',
    COUNT(*) as '索引数量'
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = 'website' AND INDEX_NAME LIKE 'idx_%'
GROUP BY TABLE_NAME
ORDER BY TABLE_NAME;\" 2>/dev/null" | column -t
echo ""

# 性能测试
echo "🎯 API性能测试:"
for i in {1..3}; do
  TIME=$(curl -s -w "%{time_total}" -o /dev/null "https://api.751152.xyz/api/nav/links")
  echo "  第${i}次: ${TIME}s"
done
echo ""

echo "╔════════════════════════════════════════════════════════════╗"
echo "║                    ✅ 所有步骤完成！                        ║"
echo "╚════════════════════════════════════════════════════════════╝"
echo ""
echo "📊 结果摘要:"
echo "  • 备份文件: /backup/${BACKUP_FILE}"
echo "  • 新增索引: ${INDEX_COUNT} 个"
echo "  • 创建耗时: ${DURATION} 秒"
echo ""
echo "📚 后续操作:"
echo "  • 监控性能: API_HOST=https://api.751152.xyz ./scripts/verify-performance.sh"
echo "  • 查看备份: ssh ${ECS_USER}@${ECS_HOST} 'ls -lh /backup/'"
echo "  • 回滚索引: review db/migrations/rollback_0010.sql, then run mysql --defaults-extra-file=$MYSQL_DEFAULTS_FILE website < /path/to/rollback_0010.sql on ECS"
echo ""
