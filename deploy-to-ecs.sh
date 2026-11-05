#!/bin/bash
set -e

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

ECS_HOST="8.156.95.93"
ECS_USER="root"
DEPLOY_BASE="/opt/website"
CURRENT_BRANCH=$(git branch --show-current)
TIMESTAMP=$(date +"%Y%m%dT%H%M%SZ")
COMMIT_SHORT=$(git rev-parse --short HEAD)
RELEASE_NAME="${TIMESTAMP}-${COMMIT_SHORT}"

echo -e "${BLUE}════════════════════════════════════════════════════════════════════════════════${NC}"
echo -e "${BLUE}ECS部署脚本 v2.0 - 优化架构版本${NC}"
echo -e "${BLUE}════════════════════════════════════════════════════════════════════════════════${NC}"
echo ""
echo -e "${YELLOW}分支: ${CURRENT_BRANCH}${NC}"
echo -e "${YELLOW}发布版本: ${RELEASE_NAME}${NC}"
echo ""

# 检查SSH连接
if ! ssh -o BatchMode=yes -o ConnectTimeout=5 ${ECS_USER}@${ECS_HOST} echo "SSH连接正常" 2>/dev/null; then
    echo -e "${RED}✗ SSH连接失败！请先配置SSH密钥：${NC}"
    echo -e "${YELLOW}  ssh-copy-id ${ECS_USER}@${ECS_HOST}${NC}"
    exit 1
fi
echo -e "${GREEN}✓ SSH连接正常${NC}"
echo ""

# 步骤1: 本地编译
echo -e "${BLUE}════════════════════════════════════════════════════════════════════════════════${NC}"
echo -e "${BLUE}步骤 1/6: 本地编译${NC}"
echo -e "${BLUE}════════════════════════════════════════════════════════════════════════════════${NC}"

echo -e "${YELLOW}➤ 清理并编译...${NC}"
mvn clean package -DskipTests -q

# 查找生成的jar文件
JAR_FILE=$(find target -name "website*.jar" -not -name "*-sources.jar" -not -name "*-javadoc.jar" | head -1)
if [ -z "$JAR_FILE" ]; then
    echo -e "${RED}✗ 编译失败，未找到jar文件${NC}"
    echo "target目录内容:"
    ls -lh target/*.jar 2>/dev/null || echo "没有jar文件"
    exit 1
fi
echo -e "${GREEN}✓ 编译完成: $JAR_FILE ($(ls -lh $JAR_FILE | awk '{print $5}'))${NC}"
echo ""

# 步骤2: 上传JAR
echo -e "${BLUE}════════════════════════════════════════════════════════════════════════════════${NC}"
echo -e "${BLUE}步骤 2/6: 上传JAR到ECS${NC}"
echo -e "${BLUE}════════════════════════════════════════════════════════════════════════════════${NC}"

echo -e "${YELLOW}➤ 创建发布目录...${NC}"
ssh ${ECS_USER}@${ECS_HOST} "mkdir -p ${DEPLOY_BASE}/releases/${RELEASE_NAME}/backend"

echo -e "${YELLOW}➤ 上传JAR包...${NC}"
scp -q "$JAR_FILE" ${ECS_USER}@${ECS_HOST}:${DEPLOY_BASE}/releases/${RELEASE_NAME}/backend/website.jar
echo -e "${GREEN}✓ 上传完成${NC}"
echo ""

# 步骤3: 备份当前版本
echo -e "${BLUE}════════════════════════════════════════════════════════════════════════════════${NC}"
echo -e "${BLUE}步骤 3/6: 备份当前版本${NC}"
echo -e "${BLUE}════════════════════════════════════════════════════════════════════════════════${NC}"

ssh ${ECS_USER}@${ECS_HOST} << 'ENDSSH'
set -e
cd /opt/website

if [ -L "current" ]; then
    CURRENT_TARGET=$(readlink current)
    echo "➤ 当前版本: ${CURRENT_TARGET}"

    # 更新previous符号链接
    rm -f previous
    ln -sf "${CURRENT_TARGET}" previous
    echo "✓ 已备份为 previous"
else
    echo "⚠️  未找到current符号链接，跳过备份"
fi
ENDSSH

echo -e "${GREEN}✓ 备份完成${NC}"
echo ""

# 步骤4: 切换到新版本
echo -e "${BLUE}════════════════════════════════════════════════════════════════════════════════${NC}"
echo -e "${BLUE}步骤 4/6: 切换到新版本${NC}"
echo -e "${BLUE}════════════════════════════════════════════════════════════════════════════════${NC}"

ssh ${ECS_USER}@${ECS_HOST} << ENDSSH
set -e
cd ${DEPLOY_BASE}

echo "➤ 更新current符号链接..."
rm -f current
ln -sf releases/${RELEASE_NAME} current

echo "➤ 验证符号链接..."
ls -lh current
ls -lh current/backend/website.jar

echo "✓ 符号链接已更新"
ENDSSH

echo -e "${GREEN}✓ 版本切换完成${NC}"
echo ""

# 步骤5: 重启服务
echo -e "${BLUE}════════════════════════════════════════════════════════════════════════════════${NC}"
echo -e "${BLUE}步骤 5/6: 重启服务${NC}"
echo -e "${BLUE}════════════════════════════════════════════════════════════════════════════════${NC}"

echo -e "${YELLOW}➤ 停止旧服务...${NC}"
ssh ${ECS_USER}@${ECS_HOST} "systemctl stop website-api || true"
sleep 2

echo -e "${YELLOW}➤ 启动新服务...${NC}"
ssh ${ECS_USER}@${ECS_HOST} "systemctl start website-api"
sleep 5

echo -e "${YELLOW}➤ 检查服务状态...${NC}"
ssh ${ECS_USER}@${ECS_HOST} "systemctl status website-api --no-pager -l | head -20"

echo -e "${GREEN}✓ 服务重启完成${NC}"
echo ""

# 步骤6: 健康检查
echo -e "${BLUE}════════════════════════════════════════════════════════════════════════════════${NC}"
echo -e "${BLUE}步骤 6/6: 健康检查${NC}"
echo -e "${BLUE}════════════════════════════════════════════════════════════════════════════════${NC}"

echo -e "${YELLOW}➤ 等待应用启动...${NC}"
for i in {1..30}; do
    if curl -sf http://localhost:8090/health > /dev/null 2>&1 || \
       ssh ${ECS_USER}@${ECS_HOST} "curl -sf http://localhost:8090/health > /dev/null 2>&1"; then
        echo -e "${GREEN}✓ 应用启动成功！${NC}"
        break
    fi
    if [ $i -eq 30 ]; then
        echo -e "${RED}✗ 应用启动超时！${NC}"
        echo -e "${YELLOW}查看日志: ssh ${ECS_USER}@${ECS_HOST} 'journalctl -u website-api -n 50'${NC}"
        exit 1
    fi
    echo -n "."
    sleep 2
done
echo ""

# 显示部署信息
ssh ${ECS_USER}@${ECS_HOST} << 'ENDSSH'
echo ""
echo "=== 部署信息 ==="
echo "当前版本: $(readlink /opt/website/current)"
echo "上一版本: $(readlink /opt/website/previous 2>/dev/null || echo '无')"
echo ""
echo "=== 最近5个版本 ==="
ls -lt /opt/website/releases/ | head -6
echo ""
echo "=== Java进程 ==="
ps aux | grep website.jar | grep -v grep | awk '{print "PID: "$2", 内存: "$6/1024"MB, 启动时间: "$9}'
ENDSSH

echo ""
echo -e "${GREEN}════════════════════════════════════════════════════════════════════════════════${NC}"
echo -e "${GREEN}🎉 部署成功！${NC}"
echo -e "${GREEN}════════════════════════════════════════════════════════════════════════════════${NC}"
echo ""
echo -e "${YELLOW}验证命令：${NC}"
echo -e "  curl https://api.751152.xyz/health"
echo -e "  curl https://api.751152.xyz/actuator/health"
echo ""
echo -e "${YELLOW}查看日志：${NC}"
echo -e "  ssh ${ECS_USER}@${ECS_HOST} 'journalctl -u website-api -f'"
echo ""
echo -e "${YELLOW}如需回滚：${NC}"
echo -e "  ssh ${ECS_USER}@${ECS_HOST}"
echo -e "  cd /opt/website && rm -f current && ln -sf previous current"
echo -e "  systemctl restart website-api"
echo ""
