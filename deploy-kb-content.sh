#!/bin/bash

# =======================================================
# 知识库和内容工厂完整部署脚本
# 版本: 1.0
# 创建时间: 2026-09-13
# =======================================================

set -e

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# ECS配置
ECS_HOST="8.156.95.93"
ECS_USER="root"
ECS_PASSWORD="wangweijie1314."

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}知识库和内容工厂部署脚本${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""

# 步骤1: 编译后端项目
echo -e "${YELLOW}[1/6] 编译后端项目...${NC}"
cd /Users/wangweijie/projects/idea/website-backend
mvn clean package -DskipTests
if [ $? -ne 0 ]; then
    echo -e "${RED}❌ 编译失败${NC}"
    exit 1
fi
echo -e "${GREEN}✅ 编译成功${NC}"
echo ""

# 步骤2: 备份ECS数据库
echo -e "${YELLOW}[2/6] 备份ECS数据库...${NC}"
sshpass -p "$ECS_PASSWORD" ssh ${ECS_USER}@${ECS_HOST} << 'EOF'
    BACKUP_DIR="/backup"
    mkdir -p $BACKUP_DIR
    BACKUP_FILE="$BACKUP_DIR/website_before_kb_content_$(date +%Y%m%d_%H%M%S).sql"
    mysqldump -u root -p'wangweijie1314.' website > $BACKUP_FILE
    echo "数据库已备份到: $BACKUP_FILE"
    ls -lh $BACKUP_FILE
EOF
if [ $? -ne 0 ]; then
    echo -e "${RED}❌ 数据库备份失败${NC}"
    exit 1
fi
echo -e "${GREEN}✅ 数据库备份成功${NC}"
echo ""

# 步骤3: 上传文件到ECS
echo -e "${YELLOW}[3/6] 上传文件到ECS...${NC}"
# 上传JAR包
sshpass -p "$ECS_PASSWORD" scp target/website-*.jar ${ECS_USER}@${ECS_HOST}:/opt/website/backend/website.jar
# 上传数据库迁移脚本
sshpass -p "$ECS_PASSWORD" scp db/migrations/0011_kb_content_enhancements.sql ${ECS_USER}@${ECS_HOST}:/tmp/
if [ $? -ne 0 ]; then
    echo -e "${RED}❌ 文件上传失败${NC}"
    exit 1
fi
echo -e "${GREEN}✅ 文件上传成功${NC}"
echo ""

# 步骤4: 执行数据库迁移
echo -e "${YELLOW}[4/6] 执行数据库迁移...${NC}"
sshpass -p "$ECS_PASSWORD" ssh ${ECS_USER}@${ECS_HOST} << 'EOF'
    echo "执行数据库迁移脚本..."
    mysql -u root -p'wangweijie1314.' website < /tmp/0011_kb_content_enhancements.sql
    if [ $? -eq 0 ]; then
        echo "✅ 数据库迁移成功"
    else
        echo "❌ 数据库迁移失败"
        exit 1
    fi
EOF
if [ $? -ne 0 ]; then
    echo -e "${RED}❌ 数据库迁移失败${NC}"
    exit 1
fi
echo -e "${GREEN}✅ 数据库迁移成功${NC}"
echo ""

# 步骤5: 重启后端服务
echo -e "${YELLOW}[5/6] 重启后端服务...${NC}"
sshpass -p "$ECS_PASSWORD" ssh ${ECS_USER}@${ECS_HOST} << 'EOF'
    echo "重启website-api服务..."
    systemctl restart website-api
    sleep 5

    # 检查服务状态
    if systemctl is-active --quiet website-api; then
        echo "✅ 服务启动成功"
    else
        echo "❌ 服务启动失败"
        journalctl -u website-api -n 50 --no-pager
        exit 1
    fi
EOF
if [ $? -ne 0 ]; then
    echo -e "${RED}❌ 服务重启失败${NC}"
    exit 1
fi
echo -e "${GREEN}✅ 服务重启成功${NC}"
echo ""

# 步骤6: 验证部署
echo -e "${YELLOW}[6/6] 验证部署...${NC}"
sleep 3

# 检查健康端点
echo "检查健康端点..."
HEALTH_CHECK=$(curl -s https://api.751152.xyz/actuator/health)
if echo "$HEALTH_CHECK" | grep -q '"status":"UP"'; then
    echo -e "${GREEN}✅ 健康检查通过${NC}"
else
    echo -e "${RED}❌ 健康检查失败${NC}"
    echo "$HEALTH_CHECK"
    exit 1
fi

# 检查知识库API
echo "检查知识库API..."
KB_CHECK=$(curl -s -o /dev/null -w "%{http_code}" -H "Authorization: Bearer test" https://api.751152.xyz/api/kb/spaces 2>/dev/null || echo "000")
if [ "$KB_CHECK" == "200" ] || [ "$KB_CHECK" == "401" ]; then
    echo -e "${GREEN}✅ 知识库API可访问${NC}"
else
    echo -e "${YELLOW}⚠️  知识库API返回: $KB_CHECK${NC}"
fi

# 检查内容模板API
echo "检查内容模板API..."
TEMPLATE_CHECK=$(curl -s -o /dev/null -w "%{http_code}" -H "Authorization: Bearer test" https://api.751152.xyz/api/content/templates 2>/dev/null || echo "000")
if [ "$TEMPLATE_CHECK" == "200" ] || [ "$TEMPLATE_CHECK" == "401" ]; then
    echo -e "${GREEN}✅ 内容模板API可访问${NC}"
else
    echo -e "${YELLOW}⚠️  内容模板API返回: $TEMPLATE_CHECK${NC}"
fi

echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}🎉 部署完成！${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo -e "${GREEN}新功能:${NC}"
echo "1. ✅ 知识库文档管理"
echo "2. ✅ 内容模板系统"
echo "3. ✅ 内容自动化任务"
echo "4. ✅ 文档分享功能"
echo ""
echo -e "${GREEN}API端点:${NC}"
echo "  知识库空间:     https://api.751152.xyz/api/kb/spaces"
echo "  知识库文档:     https://api.751152.xyz/api/kb/documents"
echo "  内容模板:       https://api.751152.xyz/api/content/templates"
echo "  自动化任务:     https://api.751152.xyz/api/content/automation"
echo ""
echo -e "${GREEN}下一步:${NC}"
echo "  1. 测试API接口"
echo "  2. 更新前端代码"
echo "  3. 配置自动化任务"
echo ""
