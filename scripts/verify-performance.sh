#!/bin/bash
# 性能验证和基准测试脚本

set -euo pipefail

echo "=== Website Backend 性能验证 ==="
echo "时间: $(date '+%Y-%m-%d %H:%M:%S')"
echo ""

# 配置
API_HOST="${API_HOST:-http://localhost:8080}"
TOKEN="${API_TOKEN:-}"
WARMUP_REQUESTS=50
TEST_REQUESTS=100

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

function print_success() {
    echo -e "${GREEN}✓${NC} $1"
}

function print_warning() {
    echo -e "${YELLOW}⚠${NC} $1"
}

function print_error() {
    echo -e "${RED}✗${NC} $1"
}

function check_dependency() {
    if ! command -v $1 &> /dev/null; then
        print_error "未找到 $1，请先安装"
        exit 1
    fi
}

# 检查依赖
check_dependency curl
check_dependency jq

# 1. 健康检查
echo "[1/7] 健康检查..."
health_response=$(curl -s -w "\n%{http_code}" "$API_HOST/actuator/health")
http_code=$(echo "$health_response" | tail -n1)
health_body=$(echo "$health_response" | head -n-1)

if [ "$http_code" = "200" ]; then
    status=$(echo "$health_body" | jq -r '.status // "UNKNOWN"')
    if [ "$status" = "UP" ]; then
        print_success "服务健康状态: UP"
    else
        print_warning "服务状态: $status"
    fi
else
    print_error "健康检查失败 (HTTP $http_code)"
    exit 1
fi

# 2. JVM指标
echo ""
echo "[2/7] JVM内存指标..."
jvm_used=$(curl -s "$API_HOST/actuator/metrics/jvm.memory.used" | jq -r '.measurements[0].value // 0')
jvm_max=$(curl -s "$API_HOST/actuator/metrics/jvm.memory.max" | jq -r '.measurements[0].value // 0')

if [ "$jvm_max" -gt 0 ]; then
    jvm_used_mb=$(echo "scale=2; $jvm_used / 1024 / 1024" | bc)
    jvm_max_mb=$(echo "scale=2; $jvm_max / 1024 / 1024" | bc)
    jvm_usage=$(echo "scale=2; $jvm_used * 100 / $jvm_max" | bc)

    echo "  堆内存使用: ${jvm_used_mb}MB / ${jvm_max_mb}MB (${jvm_usage}%)"

    if [ $(echo "$jvm_usage < 85" | bc) -eq 1 ]; then
        print_success "内存使用正常 (< 85%)"
    else
        print_warning "内存使用较高 (${jvm_usage}%)"
    fi
else
    print_warning "无法获取JVM内存指标"
fi

# 3. 数据库连接池
echo ""
echo "[3/7] 数据库连接池..."
db_active=$(curl -s "$API_HOST/actuator/metrics/hikaricp.connections.active" | jq -r '.measurements[0].value // 0' 2>/dev/null || echo "0")
db_total=$(curl -s "$API_HOST/actuator/metrics/hikaricp.connections" | jq -r '.measurements[0].value // 0' 2>/dev/null || echo "0")

if [ "$db_total" != "0" ]; then
    db_usage=$(echo "scale=2; $db_active * 100 / $db_total" | bc)
    echo "  活动连接: $db_active / $db_total (${db_usage}%)"

    if [ $(echo "$db_usage < 80" | bc) -eq 1 ]; then
        print_success "连接池使用正常 (< 80%)"
    else
        print_warning "连接池使用较高 (${db_usage}%)"
    fi
else
    print_warning "无法获取连接池指标（可能Actuator端点未完全启用）"
fi

# 4. 缓存统计
echo ""
echo "[4/7] 缓存命中率..."
cache_names=("sysConfig" "authUser" "musicPlay" "musicSearch")
total_hits=0
total_misses=0

for cache in "${cache_names[@]}"; do
    hits=$(curl -s "$API_HOST/actuator/metrics/cache.gets?tag=cache:$cache&tag=result:hit" | jq -r '.measurements[0].value // 0' 2>/dev/null || echo "0")
    misses=$(curl -s "$API_HOST/actuator/metrics/cache.gets?tag=cache:$cache&tag=result:miss" | jq -r '.measurements[0].value // 0' 2>/dev/null || echo "0")

    total_hits=$(echo "$total_hits + $hits" | bc)
    total_misses=$(echo "$total_misses + $misses" | bc)

    if [ $(echo "$hits + $misses" | bc) -gt 0 ]; then
        hit_rate=$(echo "scale=2; $hits * 100 / ($hits + $misses)" | bc)
        echo "  $cache: ${hit_rate}% (命中:$hits 未命中:$misses)"
    fi
done

if [ $(echo "$total_hits + $total_misses" | bc) -gt 0 ]; then
    overall_rate=$(echo "scale=2; $total_hits * 100 / ($total_hits + $total_misses)" | bc)
    echo ""
    if [ $(echo "$overall_rate > 70" | bc) -eq 1 ]; then
        print_success "整体缓存命中率: ${overall_rate}% (目标 > 70%)"
    else
        print_warning "整体缓存命中率: ${overall_rate}% (建议 > 70%)"
    fi
else
    print_warning "缓存数据不足，请先运行业务请求预热"
fi

# 5. 响应时间基准测试
echo ""
echo "[5/7] 接口响应时间测试..."

function benchmark_endpoint() {
    local name=$1
    local url=$2
    local headers=${3:-}

    echo -n "  测试 $name ... "

    # 预热
    for i in $(seq 1 $WARMUP_REQUESTS); do
        curl -s -o /dev/null $headers "$url" || true
    done

    # 测试
    local total_time=0
    local success_count=0

    for i in $(seq 1 $TEST_REQUESTS); do
        response_time=$(curl -s -o /dev/null -w "%{time_total}" $headers "$url" 2>/dev/null || echo "0")
        if [ "$response_time" != "0" ]; then
            total_time=$(echo "$total_time + $response_time" | bc)
            success_count=$((success_count + 1))
        fi
    done

    if [ $success_count -gt 0 ]; then
        avg_time=$(echo "scale=3; $total_time / $success_count" | bc)
        avg_ms=$(echo "scale=0; $avg_time * 1000 / 1" | bc)
        echo "${avg_ms}ms"

        # 返回平均时间用于判断
        echo "$avg_ms"
    else
        echo "失败"
        echo "9999"
    fi
}

# 公开接口测试
nav_time=$(benchmark_endpoint "导航数据" "$API_HOST/api/public/nav")
if [ "$nav_time" -lt 100 ]; then
    print_success "导航API响应时间: ${nav_time}ms (< 100ms)"
elif [ "$nav_time" -lt 200 ]; then
    print_warning "导航API响应时间: ${nav_time}ms (建议 < 100ms)"
else
    print_error "导航API响应时间: ${nav_time}ms (超过阈值)"
fi

# 健康检查响应
health_time=$(benchmark_endpoint "健康检查" "$API_HOST/actuator/health")
if [ "$health_time" -lt 50 ]; then
    print_success "健康检查响应时间: ${health_time}ms (< 50ms)"
else
    print_warning "健康检查响应时间: ${health_time}ms"
fi

# 6. GC统计
echo ""
echo "[6/7] GC统计..."
gc_count=$(curl -s "$API_HOST/actuator/metrics/jvm.gc.pause?tag=action:end.of.minor.GC" | jq -r '.measurements[] | select(.statistic=="COUNT") | .value // 0' 2>/dev/null || echo "0")
gc_time=$(curl -s "$API_HOST/actuator/metrics/jvm.gc.pause?tag=action:end.of.minor.GC" | jq -r '.measurements[] | select(.statistic=="TOTAL_TIME") | .value // 0' 2>/dev/null || echo "0")

if [ "$gc_count" != "0" ] && [ $(echo "$gc_count > 0" | bc) -eq 1 ]; then
    avg_gc_time=$(echo "scale=2; $gc_time / $gc_count * 1000" | bc)
    echo "  Minor GC次数: $gc_count"
    echo "  平均暂停时间: ${avg_gc_time}ms"

    if [ $(echo "$avg_gc_time < 200" | bc) -eq 1 ]; then
        print_success "GC暂停时间正常 (< 200ms)"
    else
        print_warning "GC暂停时间较长 (${avg_gc_time}ms)"
    fi
else
    print_warning "GC数据不足或未获取到"
fi

# 7. 系统资源（如果在Linux上）
echo ""
echo "[7/7] 系统资源..."
if [ -f /proc/loadavg ]; then
    load=$(cat /proc/loadavg | awk '{print $1}')
    cpu_count=$(nproc)
    load_percent=$(echo "scale=2; $load * 100 / $cpu_count" | bc)

    echo "  CPU负载: $load / $cpu_count (${load_percent}%)"

    if [ $(echo "$load_percent < 70" | bc) -eq 1 ]; then
        print_success "CPU负载正常 (< 70%)"
    else
        print_warning "CPU负载较高 (${load_percent}%)"
    fi

    # 内存使用
    mem_info=$(free | grep Mem)
    mem_total=$(echo $mem_info | awk '{print $2}')
    mem_used=$(echo $mem_info | awk '{print $3}')
    mem_percent=$(echo "scale=2; $mem_used * 100 / $mem_total" | bc)

    echo "  内存使用: ${mem_percent}%"

    if [ $(echo "$mem_percent < 85" | bc) -eq 1 ]; then
        print_success "内存使用正常 (< 85%)"
    else
        print_warning "内存使用较高 (${mem_percent}%)"
    fi
else
    echo "  (非Linux系统，跳过系统资源检查)"
fi

# 总结
echo ""
echo "=== 性能验证完成 ==="
echo ""
echo "建议："
echo "1. 定期运行此脚本监控性能指标"
echo "2. 如果缓存命中率低，检查缓存配置和业务逻辑"
echo "3. 如果GC暂停时间长，考虑调整JVM参数或增加堆内存"
echo "4. 使用生产环境流量测试真实性能表现"
echo ""
echo "详细文档: docs/performance-optimization.md"
