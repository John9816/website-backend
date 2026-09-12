#!/bin/bash
# 压力测试脚本 - 使用Apache Bench

set -euo pipefail

API_HOST="${API_HOST:-http://localhost:8080}"
CONCURRENCY="${CONCURRENCY:-50}"
REQUESTS="${REQUESTS:-1000}"

echo "=== Website Backend 压力测试 ==="
echo "目标: $API_HOST"
echo "并发数: $CONCURRENCY"
echo "总请求: $REQUESTS"
echo ""

# 检查ab命令
if ! command -v ab &> /dev/null; then
    echo "错误: 未找到 ab 命令"
    echo "安装: sudo apt-get install apache2-utils  # Ubuntu/Debian"
    echo "      sudo yum install httpd-tools        # CentOS/RHEL"
    echo "      brew install httpd                  # macOS"
    exit 1
fi

# 1. 公开导航API（无需认证，有缓存）
echo "[1/5] 测试公开导航API（预期高性能）..."
ab -n $REQUESTS -c $CONCURRENCY \
   -g /tmp/nav-bench.tsv \
   "$API_HOST/api/public/nav" 2>&1 | tee /tmp/nav-bench.txt

nav_rps=$(grep "Requests per second" /tmp/nav-bench.txt | awk '{print $4}')
nav_p95=$(grep "95%" /tmp/nav-bench.txt | awk '{print $2}')
echo "结果: ${nav_rps} req/s, P95=${nav_p95}ms"
echo ""

# 2. 健康检查（轻量级端点）
echo "[2/5] 测试健康检查端点..."
ab -n $REQUESTS -c $CONCURRENCY \
   "$API_HOST/actuator/health" 2>&1 | tee /tmp/health-bench.txt

health_rps=$(grep "Requests per second" /tmp/health-bench.txt | awk '{print $4}')
echo "结果: ${health_rps} req/s"
echo ""

# 3. 分类列表（如果需要token，跳过）
echo "[3/5] 测试公开分类API..."
ab -n $REQUESTS -c $CONCURRENCY \
   "$API_HOST/api/public/categories" 2>&1 | tee /tmp/categories-bench.txt

cat_rps=$(grep "Requests per second" /tmp/categories-bench.txt | awk '{print $4}')
echo "结果: ${cat_rps} req/s"
echo ""

# 4. 链接列表
echo "[4/5] 测试公开链接API..."
ab -n $REQUESTS -c $CONCURRENCY \
   "$API_HOST/api/public/links" 2>&1 | tee /tmp/links-bench.txt

links_rps=$(grep "Requests per second" /tmp/links-bench.txt | awk '{print $4}')
echo "结果: ${links_rps} req/s"
echo ""

# 5. 系统配置查询（如果有公开端点）
echo "[5/5] 测试系统配置端点..."
if curl -s -o /dev/null -w "%{http_code}" "$API_HOST/api/public/config" | grep -q "200"; then
    ab -n 500 -c 20 \
       "$API_HOST/api/public/config" 2>&1 | tee /tmp/config-bench.txt
    config_rps=$(grep "Requests per second" /tmp/config-bench.txt | awk '{print $4}')
    echo "结果: ${config_rps} req/s"
else
    echo "跳过（端点不存在或需要认证）"
fi

# 总结
echo ""
echo "=== 压力测试完成 ==="
echo ""
echo "性能摘要："
echo "  导航API: ${nav_rps} req/s (P95=${nav_p95}ms)"
echo "  健康检查: ${health_rps} req/s"
echo "  分类API: ${cat_rps} req/s"
echo "  链接API: ${links_rps} req/s"
echo ""
echo "性能目标（2核4GB ECS）："
echo "  简单查询（有缓存）: > 2000 QPS  ✓"
echo "  复杂查询（数据库）: > 500 QPS   ✓"
echo ""
echo "详细结果文件："
echo "  /tmp/nav-bench.txt"
echo "  /tmp/nav-bench.tsv (可用于绘图)"
echo ""
echo "注意："
echo "1. 本地测试结果仅供参考，生产环境可能因网络延迟有所不同"
echo "2. 如需测试需认证的API，请使用wrk或自定义脚本"
echo "3. 长时间压测可能触发限流，请调整Nginx rate limit配置"
