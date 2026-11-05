#!/bin/bash
#
# 检查ECS上的后端服务配置
#

echo "🔍 检查ECS后端服务配置..."
echo ""

echo "1️⃣ 检查systemd服务:"
ssh root@8.156.95.93 "systemctl list-units --type=service | grep -i 'website\|java\|spring' || echo '未找到相关服务'"

echo ""
echo "2️⃣ 检查 /opt/website 目录:"
ssh root@8.156.95.93 "ls -la /opt/website/ 2>/dev/null || echo '目录不存在'"

echo ""
echo "3️⃣ 检查 /opt 下的所有目录:"
ssh root@8.156.95.93 "ls -la /opt/ 2>/dev/null || echo '无法访问'"

echo ""
echo "4️⃣ 检查运行中的Java进程:"
ssh root@8.156.95.93 "ps aux | grep java | grep -v grep || echo '未找到Java进程'"

echo ""
echo "5️⃣ 检查 /etc/systemd/system 下的服务文件:"
ssh root@8.156.95.93 "ls -la /etc/systemd/system/*.service | grep -i 'website\|api\|backend' || echo '未找到相关服务文件'"

echo ""
echo "6️⃣ 检查所有自定义服务:"
ssh root@8.156.95.93 "ls -la /etc/systemd/system/*.service"
