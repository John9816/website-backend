#!/bin/bash
# 快速检查ECS服务状态

ECS_HOST="8.156.95.93"
ECS_USER="root"

echo "=========================================="
echo "  生产环境状态检查"
echo "  时间: $(date '+%Y-%m-%d %H:%M:%S')"
echo "=========================================="
echo ""

echo "1️⃣  服务运行状态"
echo "---"
ssh ${ECS_USER}@${ECS_HOST} << 'EOF'
systemctl is-active website-api &>/dev/null && echo "✅ website-api: 运行中" || echo "❌ website-api: 已停止"
systemctl is-active nginx &>/dev/null && echo "✅ nginx: 运行中" || echo "❌ nginx: 已停止"
systemctl is-active mysql &>/dev/null && echo "✅ mysql: 运行中" || echo "❌ mysql: 已停止"
EOF

echo ""
echo "2️⃣  API响应测试"
echo "---"
# 导航API
nav_result=$(curl -s -w "%{http_code}|%{time_total}" -o /dev/null https://api.751152.xyz/api/public/nav)
nav_code=$(echo $nav_result | cut -d'|' -f1)
nav_time=$(echo $nav_result | cut -d'|' -f2)
[ "$nav_code" = "200" ] && echo "✅ 导航API: ${nav_time}s" || echo "❌ 导航API: HTTP $nav_code"

# 健康检查
health=$(curl -s https://api.751152.xyz/actuator/health | grep -o '"status":"[^"]*"' | cut -d'"' -f4)
[ "$health" = "UP" ] && echo "✅ 健康检查: UP" || echo "❌ 健康检查: $health"

# 前端
frontend_result=$(curl -s -w "%{http_code}" -o /dev/null https://hi.751152.xyz/)
[ "$frontend_result" = "200" ] && echo "✅ 前端页面: 正常" || echo "❌ 前端页面: HTTP $frontend_result"

echo ""
echo "3️⃣  系统资源"
echo "---"
ssh ${ECS_USER}@${ECS_HOST} << 'EOF'
# 内存
mem_total=$(free -h | awk '/^Mem:/ {print $2}')
mem_used=$(free -h | awk '/^Mem:/ {print $3}')
mem_percent=$(free | awk '/^Mem:/ {printf "%.1f", $3/$2*100}')
echo "💾 内存: ${mem_used} / ${mem_total} (${mem_percent}%)"

# 磁盘
disk_info=$(df -h / | awk 'NR==2 {printf "%s / %s (%s)", $3, $2, $5}')
echo "💿 磁盘: ${disk_info}"

# CPU负载
load=$(uptime | awk -F'load average:' '{print $2}' | sed 's/^[ \t]*//')
echo "⚡ 负载: ${load}"

# Java进程
java_mem=$(ps aux | grep 'website.jar' | grep -v grep | awk '{print $6}')
if [ -n "$java_mem" ]; then
  java_mem_mb=$((java_mem / 1024))
  echo "☕ Java进程: ${java_mem_mb}MB"
fi
EOF

echo ""
echo "4️⃣  近期日志"
echo "---"
ssh ${ECS_USER}@${ECS_HOST} << 'EOF'
journalctl -u website-api --since "5 minutes ago" --no-pager | grep -E "ERROR|WARN" | tail -5 | while read line; do
  echo "  $line"
done || echo "  ✅ 无错误或警告"
EOF

echo ""
echo "=========================================="
echo "✅ 检查完成"
echo ""
echo "💡 更多命令:"
echo "  详细监控: ssh root@${ECS_HOST} '/opt/website/scripts/monitor-performance.sh'"
echo "  查看日志: ssh root@${ECS_HOST} 'journalctl -u website-api -f'"
echo "  重启服务: ssh root@${ECS_HOST} 'systemctl restart website-api'"
echo "=========================================="
