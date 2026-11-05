#!/bin/bash

ECS_HOST="8.156.95.93"
ECS_USER="root"

echo "=== 诊断前后端状态 ==="
echo ""

ssh ${ECS_USER}@${ECS_HOST} << 'ENDSSH'
echo "1. 后端API服务状态"
systemctl status website-api --no-pager | head -10

echo ""
echo "2. 前端服务状态"
systemctl status website-ecs --no-pager | head -10

echo ""
echo "3. Nginx状态"
systemctl status nginx --no-pager | head -5

echo ""
echo "4. Java进程"
ps aux | grep java | grep website | grep -v grep

echo ""
echo "5. 端口监听"
netstat -tlnp | grep -E "8090|80|443"

echo ""
echo "6. 最近的错误日志 (website-api)"
journalctl -u website-api -n 20 --no-pager

echo ""
echo "7. 最近的错误日志 (website-ecs)"
journalctl -u website-ecs -n 20 --no-pager

echo ""
echo "8. Nginx错误日志"
tail -20 /var/log/nginx/error.log

echo ""
echo "9. 检查符号链接"
ls -lh /opt/website/current
ls -lh /opt/website/dist

echo ""
echo "10. 本地健康检查"
curl -v http://localhost:8090/health 2>&1 | head -20
ENDSSH

echo ""
echo "11. 外部访问测试"
echo "后端API:"
curl -I https://api.751152.xyz/health 2>&1 | head -10

echo ""
echo "前端页面:"
curl -I https://hi.751152.xyz/ 2>&1 | head -10
