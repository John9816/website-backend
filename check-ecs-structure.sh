#!/bin/bash

ECS_HOST="8.156.95.93"
ECS_USER="root"

echo "检查ECS服务器结构..."
echo ""

ssh ${ECS_USER}@${ECS_HOST} << 'ENDSSH'
echo "=== /opt 目录 ==="
ls -la /opt/

echo ""
echo "=== /opt/website 目录 ==="
ls -la /opt/website/ 2>/dev/null || echo "不存在"

echo ""
echo "=== 查找website相关目录 ==="
find /opt -maxdepth 3 -name "*website*" -type d 2>/dev/null || echo "未找到"

echo ""
echo "=== 查找git仓库 ==="
find /opt -maxdepth 3 -name ".git" -type d 2>/dev/null || echo "未找到git仓库"

echo ""
echo "=== 查找Java进程 ==="
ps aux | grep java | grep -v grep || echo "未找到Java进程"

echo ""
echo "=== systemd服务 ==="
systemctl list-units --type=service --all | grep website || echo "未找到website服务"

echo ""
echo "=== 查找jar包 ==="
find /opt -name "*.jar" 2>/dev/null | head -10 || echo "未找到jar包"

ENDSSH
