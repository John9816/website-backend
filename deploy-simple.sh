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

echo -e "${BLUE}════════════════════════════════════════════════════════════════════════════════${NC}"
echo -e "${BLUE}简化部署脚本 - 使用SSH密钥认证${NC}"
echo -e "${BLUE}════════════════════════════════════════════════════════════════════════════════${NC}"
echo ""

# 检查是否已配置SSH密钥
if ! ssh -o BatchMode=yes -o ConnectTimeout=5 ${ECS_USER}@${ECS_HOST} echo "SSH密钥已配置" 2>/dev/null; then
    echo -e "${YELLOW}⚠️  SSH密钥未配置，请先执行：${NC}"
    echo -e "${YELLOW}   ssh-copy-id ${ECS_USER}@${ECS_HOST}${NC}"
    echo -e "${YELLOW}   密码: wangweijie1314.${NC}"
    exit 1
fi

echo -e "${GREEN}✓ SSH连接正常${NC}"
echo ""

# 步骤1: 本地提交
echo -e "${BLUE}════════════════════════════════════════════════════════════════════════════════${NC}"
echo -e "${BLUE}步骤 1/5: 本地Git提交${NC}"
echo -e "${BLUE}════════════════════════════════════════════════════════════════════════════════${NC}"

if [[ -n $(git status -s) ]]; then
    echo -e "${YELLOW}➤ 发现未提交的更改，正在提交...${NC}"
    git add -A
    git commit -m "部署: $(date '+%Y-%m-%d %H:%M:%S')" || true
    echo -e "${GREEN}✓ 本地提交完成${NC}"
else
    echo -e "${GREEN}✓ 工作区干净，无需提交${NC}"
fi
echo ""

# 步骤2: 推送到远程
echo -e "${BLUE}════════════════════════════════════════════════════════════════════════════════${NC}"
echo -e "${BLUE}步骤 2/5: 推送到Git远程仓库${NC}"
echo -e "${BLUE}════════════════════════════════════════════════════════════════════════════════${NC}"

CURRENT_BRANCH=$(git branch --show-current)
echo -e "${YELLOW}➤ 推送分支: ${CURRENT_BRANCH}${NC}"
git push origin ${CURRENT_BRANCH}
echo -e "${GREEN}✓ 推送完成${NC}"
echo ""

# 步骤3: ECS拉取代码
echo -e "${BLUE}════════════════════════════════════════════════════════════════════════════════${NC}"
echo -e "${BLUE}步骤 3/5: ECS拉取最新代码${NC}"
echo -e "${BLUE}════════════════════════════════════════════════════════════════════════════════${NC}"

ssh ${ECS_USER}@${ECS_HOST} << ENDSSH
set -e
cd /opt/website
echo "➤ 拉取分支: ${CURRENT_BRANCH}"
git fetch origin
git checkout ${CURRENT_BRANCH}
git pull origin ${CURRENT_BRANCH}
echo "✓ 代码更新完成"
ENDSSH

echo -e "${GREEN}✓ ECS代码更新完成${NC}"
echo ""

# 步骤4: ECS编译
echo -e "${BLUE}════════════════════════════════════════════════════════════════════════════════${NC}"
echo -e "${BLUE}步骤 4/5: ECS编译项目${NC}"
echo -e "${BLUE}════════════════════════════════════════════════════════════════════════════════${NC}"

ssh ${ECS_USER}@${ECS_HOST} << 'ENDSSH'
set -e
cd /opt/website
echo "➤ 开始编译..."
mvn clean package -DskipTests -q
echo "✓ 编译完成"
ENDSSH

echo -e "${GREEN}✓ 编译完成${NC}"
echo ""

# 步骤5: 重启服务
echo -e "${BLUE}════════════════════════════════════════════════════════════════════════════════${NC}"
echo -e "${BLUE}步骤 5/5: 重启服务${NC}"
echo -e "${BLUE}════════════════════════════════════════════════════════════════════════════════${NC}"

ssh ${ECS_USER}@${ECS_HOST} << 'ENDSSH'
set -e
echo "➤ 停止旧服务..."
systemctl stop website || true

echo "➤ 启动新服务..."
systemctl start website

echo "➤ 检查服务状态..."
sleep 3
systemctl status website --no-pager -l

echo "✓ 服务重启完成"
ENDSSH

echo -e "${GREEN}✓ 服务重启完成${NC}"
echo ""

echo -e "${GREEN}════════════════════════════════════════════════════════════════════════════════${NC}"
echo -e "${GREEN}🎉 部署成功！${NC}"
echo -e "${GREEN}════════════════════════════════════════════════════════════════════════════════${NC}"
echo ""
echo -e "${YELLOW}验证命令：${NC}"
echo -e "  curl https://api.751152.xyz/health"
echo -e "  ssh ${ECS_USER}@${ECS_HOST} 'journalctl -u website -f'"
echo ""
