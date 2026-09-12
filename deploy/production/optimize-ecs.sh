#!/bin/bash
set -euo pipefail

# ECS性能调优脚本
# 适用于Ubuntu 20.04/22.04，需要root权限运行

echo "=== ECS性能优化脚本 ==="
echo "此脚本将优化系统参数以提升Web服务性能"
echo ""

# 检查root权限
if [[ $EUID -ne 0 ]]; then
   echo "错误：此脚本需要root权限运行"
   echo "请使用: sudo $0"
   exit 1
fi

# 备份原配置
backup_dir="/root/ecs-optimization-backup-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$backup_dir"
echo "配置文件备份目录: $backup_dir"

# 1. 系统内核参数优化
echo ""
echo "[1/6] 优化系统内核参数..."
if [ -f /etc/sysctl.conf ]; then
    cp /etc/sysctl.conf "$backup_dir/sysctl.conf.bak"
fi

cat >> /etc/sysctl.conf << 'EOF'

# Website Backend Performance Tuning
# 应用于: $(date +%Y-%m-%d)

# 网络优化
net.core.somaxconn = 4096
net.core.netdev_max_backlog = 5000
net.ipv4.tcp_max_syn_backlog = 8192
net.ipv4.tcp_syncookies = 1
net.ipv4.tcp_tw_reuse = 1
net.ipv4.tcp_fin_timeout = 30
net.ipv4.tcp_keepalive_time = 600
net.ipv4.tcp_keepalive_intvl = 30
net.ipv4.tcp_keepalive_probes = 3
net.ipv4.ip_local_port_range = 10000 65535

# TCP性能优化
net.ipv4.tcp_fastopen = 3
net.ipv4.tcp_slow_start_after_idle = 0
net.ipv4.tcp_mtu_probing = 1

# 连接跟踪优化
net.netfilter.nf_conntrack_max = 262144
net.netfilter.nf_conntrack_tcp_timeout_established = 1200

# 文件句柄
fs.file-max = 2097152
fs.inotify.max_user_watches = 524288

# 虚拟内存
vm.swappiness = 10
vm.dirty_ratio = 15
vm.dirty_background_ratio = 5
vm.overcommit_memory = 1

EOF

sysctl -p
echo "✓ 内核参数已优化"

# 2. 文件句柄限制
echo ""
echo "[2/6] 优化文件句柄限制..."
if [ -f /etc/security/limits.conf ]; then
    cp /etc/security/limits.conf "$backup_dir/limits.conf.bak"
fi

cat >> /etc/security/limits.conf << 'EOF'

# Website Backend File Limits
* soft nofile 65535
* hard nofile 65535
* soft nproc 32768
* hard nproc 32768
root soft nofile 65535
root hard nofile 65535

EOF

echo "✓ 文件句柄限制已优化"

# 3. MySQL调优（如果MySQL在同一台机器）
if systemctl is-active --quiet mysql || systemctl is-active --quiet mariadb; then
    echo ""
    echo "[3/6] 检测到MySQL服务，生成优化建议..."

    mysql_cnf="/etc/mysql/mysql.conf.d/mysqld.cnf"
    if [ -f "$mysql_cnf" ]; then
        cp "$mysql_cnf" "$backup_dir/mysqld.cnf.bak"

        cat > /tmp/mysql-tuning.cnf << 'EOF'
# MySQL Performance Tuning (2核4GB配置)
# 将以下参数添加到 [mysqld] 区段

[mysqld]
# 连接优化
max_connections = 300
max_connect_errors = 100000
wait_timeout = 600
interactive_timeout = 600

# 缓存优化
innodb_buffer_pool_size = 1G
innodb_log_file_size = 256M
innodb_log_buffer_size = 16M
innodb_flush_log_at_trx_commit = 2
innodb_flush_method = O_DIRECT

# 性能优化
innodb_io_capacity = 1000
innodb_io_capacity_max = 2000
innodb_read_io_threads = 4
innodb_write_io_threads = 4
innodb_purge_threads = 2
innodb_thread_concurrency = 0

# 查询缓存（MySQL 5.7及以下）
# query_cache_type = 1
# query_cache_size = 64M

# 临时表
tmp_table_size = 64M
max_heap_table_size = 64M

# 日志优化
slow_query_log = 1
slow_query_log_file = /var/log/mysql/slow-query.log
long_query_time = 2

EOF
        echo "✓ MySQL优化建议已生成: /tmp/mysql-tuning.cnf"
        echo "  请手动审核并合并到 $mysql_cnf"
    fi
else
    echo ""
    echo "[3/6] 未检测到本地MySQL服务，跳过"
fi

# 4. Nginx优化
if systemctl is-active --quiet nginx; then
    echo ""
    echo "[4/6] 优化Nginx配置..."

    nginx_conf="/etc/nginx/nginx.conf"
    if [ -f "$nginx_conf" ]; then
        cp "$nginx_conf" "$backup_dir/nginx.conf.bak"

        # 检查是否已包含优化配置
        if ! grep -q "worker_rlimit_nofile" "$nginx_conf"; then
            sed -i '/worker_processes/a worker_rlimit_nofile 65535;' "$nginx_conf"
        fi

        # 创建优化配置片段
        cat > /etc/nginx/conf.d/performance.conf << 'EOF'
# Nginx性能优化配置
worker_connections 4096;
multi_accept on;
use epoll;

# 连接保持
keepalive_timeout 65;
keepalive_requests 1000;

# 缓冲优化
client_body_buffer_size 256k;
client_header_buffer_size 4k;
large_client_header_buffers 4 8k;

# 超时设置
client_body_timeout 30s;
client_header_timeout 30s;
send_timeout 30s;

# TCP优化
tcp_nopush on;
tcp_nodelay on;

# 文件缓存
open_file_cache max=10000 inactive=60s;
open_file_cache_valid 30s;
open_file_cache_min_uses 2;
open_file_cache_errors on;

EOF
        nginx -t && systemctl reload nginx
        echo "✓ Nginx配置已优化并重载"
    fi
else
    echo ""
    echo "[4/6] 未检测到Nginx服务"
fi

# 5. 创建性能监控脚本
echo ""
echo "[5/6] 创建性能监控脚本..."

cat > /opt/website/scripts/monitor-performance.sh << 'EOF'
#!/bin/bash
# 实时性能监控脚本

echo "=== Website Backend 性能监控 ==="
echo "时间: $(date '+%Y-%m-%d %H:%M:%S')"
echo ""

# Java进程
echo "--- Java进程 ---"
ps aux | grep 'website.jar' | grep -v grep | awk '{printf "PID: %s  CPU: %s%%  MEM: %s%%  VSZ: %sMB\n", $2, $3, $4, $5/1024}'

# JVM内存
echo ""
echo "--- JVM堆内存 ---"
jcmd $(pgrep -f website.jar) GC.heap_info 2>/dev/null | head -20

# 系统负载
echo ""
echo "--- 系统负载 ---"
uptime

# 内存使用
echo ""
echo "--- 内存使用 ---"
free -h

# 磁盘IO
echo ""
echo "--- 磁盘IO ---"
iostat -x 1 2 | tail -n +4

# 网络连接
echo ""
echo "--- 网络连接统计 ---"
ss -s

# TCP连接状态
echo ""
echo "--- TCP连接状态 ---"
ss -tan | awk '{print $1}' | sort | uniq -c | sort -rn

# Nginx访问统计（最近1分钟）
echo ""
echo "--- Nginx访问统计（最近1分钟）---"
if [ -f /var/log/nginx/access.log ]; then
    awk -v d1="$(date --date='-1 min' '+%d/%b/%Y:%H:%M')" '$4 > "["d1' /var/log/nginx/access.log | \
    awk '{print $9}' | sort | uniq -c | sort -rn | head -10
fi

EOF

chmod +x /opt/website/scripts/monitor-performance.sh
echo "✓ 监控脚本已创建: /opt/website/scripts/monitor-performance.sh"

# 6. 创建定期清理脚本
echo ""
echo "[6/6] 创建日志清理cron任务..."

cat > /opt/website/scripts/cleanup-logs.sh << 'EOF'
#!/bin/bash
# 定期清理日志文件，避免磁盘占满

LOG_DIR="/opt/website/logs"
RETENTION_DAYS=7

if [ -d "$LOG_DIR" ]; then
    find "$LOG_DIR" -name "*.log.*" -type f -mtime +$RETENTION_DAYS -delete
    find "$LOG_DIR" -name "gc.log.*" -type f -mtime +$RETENTION_DAYS -delete
fi

# 清理Nginx日志（保留30天）
if [ -d /var/log/nginx ]; then
    find /var/log/nginx -name "*.log.*" -type f -mtime +30 -delete
fi

# 清理MySQL慢查询日志（保留14天）
if [ -f /var/log/mysql/slow-query.log ]; then
    find /var/log/mysql -name "slow-query.log.*" -type f -mtime +14 -delete
fi

echo "$(date): Log cleanup completed" >> /var/log/website-cleanup.log

EOF

chmod +x /opt/website/scripts/cleanup-logs.sh

# 添加到crontab
(crontab -l 2>/dev/null; echo "0 2 * * * /opt/website/scripts/cleanup-logs.sh") | crontab -
echo "✓ 日志清理任务已添加到crontab（每天凌晨2点执行）"

# 完成
echo ""
echo "=== 优化完成 ==="
echo ""
echo "重要提示："
echo "1. 备份文件保存在: $backup_dir"
echo "2. 部分配置需要重启系统才能完全生效"
echo "3. MySQL优化建议请查看: /tmp/mysql-tuning.cnf"
echo "4. 运行性能监控: /opt/website/scripts/monitor-performance.sh"
echo ""
echo "建议操作："
echo "  sudo reboot  # 重启系统以应用所有配置"
echo ""
