# 性能优化发布清单

在执行性能优化发布前，请按此清单逐项确认。

## 发布前检查

### 1. 代码检查
- [ ] 所有优化代码已提交到当前分支
- [ ] 通过单元测试：`mvn test`
- [ ] 通过编译构建：`mvn clean package -DskipTests`
- [ ] 代码审查完成

### 2. 配置文件检查
- [ ] `application.yml` 中的环境变量占位符正确
- [ ] `backend.env.example` 包含所有必需环境变量
- [ ] JVM参数已在 `website-api.service` 中配置
- [ ] Nginx配置文件语法检查：`nginx -t`

### 3. 数据库迁移
- [ ] 已备份生产数据库
- [ ] 索引SQL已审查：`src/main/resources/db/migrations/0010_performance_indexes.sql`
- [ ] 在测试环境验证索引创建成功
- [ ] 确认索引创建不会锁表过久（可使用 `ALGORITHM=INPLACE`）

### 4. 依赖检查
- [ ] `pom.xml` 中新增的依赖已同步
- [ ] Maven本地仓库已更新：`mvn dependency:resolve`

## ECS准备

### 1. 系统优化（首次执行）
```bash
# 上传优化脚本
scp -i ~/.ssh/id_ed25519 \
    deploy/production/optimize-ecs.sh \
    root@<ECS_IP>:/tmp/

# 执行系统优化
ssh root@<ECS_IP> 'sudo bash /tmp/optimize-ecs.sh'

# 检查优化结果
ssh root@<ECS_IP> 'sysctl -a | grep net.core.somaxconn'
ssh root@<ECS_IP> 'ulimit -n'

# 重启ECS应用所有配置
ssh root@<ECS_IP> 'sudo reboot'
```

### 2. 数据库索引优化
```bash
# 备份数据库
ssh root@<ECS_IP>
mysqldump -u root -p website > /backup/website_$(date +%Y%m%d_%H%M%S).sql

# 执行索引创建（低峰时段）
mysql -u root -p website < /path/to/0010_performance_indexes.sql

# 验证索引
mysql -u root -p website -e "
SELECT TABLE_NAME, INDEX_NAME, GROUP_CONCAT(COLUMN_NAME) 
FROM INFORMATION_SCHEMA.STATISTICS 
WHERE TABLE_SCHEMA='website' AND INDEX_NAME != 'PRIMARY' 
GROUP BY TABLE_NAME, INDEX_NAME;"
```

### 3. Nginx配置更新
```bash
# 备份现有配置
ssh root@<ECS_IP> 'sudo cp /etc/nginx/sites-available/website /etc/nginx/sites-available/website.bak'

# 创建缓存目录
ssh root@<ECS_IP> 'sudo mkdir -p /var/cache/nginx/website'
ssh root@<ECS_IP> 'sudo chown -R www-data:www-data /var/cache/nginx'

# 上传新配置
scp -i ~/.ssh/id_ed25519 \
    deploy/production/nginx.conf \
    root@<ECS_IP>:/tmp/nginx.conf

# 测试配置
ssh root@<ECS_IP> 'sudo nginx -t'

# 重载Nginx
ssh root@<ECS_IP> 'sudo systemctl reload nginx'
```

## 应用部署

### 1. 部署新版本
```bash
cd /Users/wangweijie/projects/idea/website-backend

# 完整部署（包含前端）
./deploy/production/deploy.sh \
  --host <ECS_IP> \
  --identity ~/.ssh/id_ed25519 \
  --frontend /Users/wangweijie/projects/vs/website

# 或仅部署后端
./deploy/production/deploy.sh \
  --host <ECS_IP> \
  --identity ~/.ssh/id_ed25519
```

### 2. 观察启动日志
```bash
ssh root@<ECS_IP>
journalctl -u website-api -f

# 等待启动成功，关键日志：
# - Started WebsiteApplication
# - Tomcat started on port(s): 8090
# - Caffeine cache initialized
```

### 3. 健康检查
```bash
# 健康状态
curl http://<ECS_IP>:8090/actuator/health

# JVM内存
curl http://<ECS_IP>:8090/actuator/metrics/jvm.memory.used

# 应用启动时间
curl http://<ECS_IP>:8090/actuator/metrics/process.uptime
```

## 发布后验证

### 1. 功能验证
```bash
# 在ECS上运行性能验证脚本
ssh root@<ECS_IP> '/opt/website/scripts/monitor-performance.sh'

# 本地运行完整验证
API_HOST=https://api.751152.xyz ./scripts/verify-performance.sh
```

### 2. 关键指标监控（前30分钟）
- [ ] JVM堆内存使用 < 85%
- [ ] GC暂停时间 < 200ms
- [ ] 接口响应时间 P95 < 500ms
- [ ] 数据库连接池使用 < 80%
- [ ] 错误率 < 0.1%
- [ ] CPU负载 < 核心数 × 0.7

```bash
# 持续监控
ssh root@<ECS_IP>
watch -n 10 '/opt/website/scripts/monitor-performance.sh'

# 查看GC日志（如果启用）
tail -f /opt/website/logs/gc.log
```

### 3. 缓存验证
```bash
# 缓存命中率
curl https://api.751152.xyz/actuator/metrics/cache.gets?tag=result:hit
curl https://api.751152.xyz/actuator/metrics/cache.gets?tag=result:miss

# 单个缓存命中率
curl "https://api.751152.xyz/actuator/metrics/cache.gets?tag=cache:musicPlay&tag=result:hit"
```

### 4. 性能基准测试
```bash
# 简单压测
API_HOST=https://api.751152.xyz \
CONCURRENCY=50 \
REQUESTS=1000 \
./scripts/benchmark.sh

# 预期结果（2核4GB）：
# - 导航API: > 500 QPS
# - 健康检查: > 2000 QPS
```

### 5. 业务功能测试
- [ ] 用户登录正常
- [ ] 导航数据查询正常
- [ ] 音乐搜索和播放正常
- [ ] 图片生成任务正常
- [ ] AI对话正常
- [ ] 知识库查询正常

## 回滚方案

### 发现严重问题时立即回滚
```bash
# 方式1: 使用回滚脚本（推荐）
ssh root@<ECS_IP> 'bash /opt/website/current/ops/remote-rollback.sh previous'

# 方式2: 手动切换版本
ssh root@<ECS_IP>
cd /opt/website
ls -lt releases/  # 查看可用版本
ln -sfn releases/<previous-version> current
systemctl restart website-api
systemctl reload nginx
```

### 回滚数据库索引（仅必要时）
```bash
# 删除新增索引
mysql -u root -p website << 'EOF'
ALTER TABLE user DROP INDEX idx_username;
ALTER TABLE user DROP INDEX idx_email;
-- ... 其他索引
EOF
```

### 回滚Nginx配置
```bash
ssh root@<ECS_IP>
sudo cp /etc/nginx/sites-available/website.bak /etc/nginx/sites-available/website
sudo nginx -t
sudo systemctl reload nginx
```

## 性能对比

### 优化前基准（记录用）
```bash
# 发布前记录当前性能
curl https://api.751152.xyz/actuator/metrics/http.server.requests > metrics_before.json

# P95响应时间
# 缓存命中率
# 数据库查询时间
# GC暂停时间
```

### 优化后验证
```bash
# 发布后24小时记录
curl https://api.751152.xyz/actuator/metrics/http.server.requests > metrics_after.json

# 对比改进
diff metrics_before.json metrics_after.json
```

## 监控持续观察（7天）

### 每日检查
- [ ] 应用日志无ERROR级别异常
- [ ] 慢查询日志 < 10条/天
- [ ] 内存占用稳定
- [ ] GC频率正常（< 5次/小时）
- [ ] 缓存命中率 > 70%

### 告警阈值设置（可选）
```bash
# 设置告警监控
# - JVM堆内存 > 90%
# - GC暂停 > 500ms
# - 接口P99 > 2s
# - 错误率 > 1%
# - CPU持续 > 80%
```

## 完成标志

- [ ] 所有验证项通过
- [ ] 性能指标达到预期
- [ ] 监控面板正常显示
- [ ] 业务团队确认功能正常
- [ ] 更新文档和运维手册

## 紧急联系

- 后端负责人：[姓名] [联系方式]
- DBA：[姓名] [联系方式]
- 运维：[姓名] [联系方式]

## 备注

- 建议在业务低峰期（凌晨2-5点）执行首次优化发布
- 数据库索引创建可能需要5-30分钟（取决于数据量）
- Nginx缓存目录需要定期清理，避免磁盘占满
- JVM参数需根据实际负载调整，非一成不变
