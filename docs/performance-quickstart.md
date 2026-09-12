# 快速开始：性能优化版本

本指南帮助你快速将性能优化部署到生产环境。

## TL;DR（最快路径）

```bash
# 1. 构建并部署应用
./deploy/production/deploy.sh \
  --host <ECS_IP> \
  --identity ~/.ssh/id_ed25519

# 2. 在ECS上执行系统优化
ssh root@<ECS_IP> 'bash /opt/website/current/deploy/production/optimize-ecs.sh && sudo reboot'

# 3. 等待重启后，执行数据库索引优化
ssh root@<ECS_IP>
mysqldump -u root -p website > /backup/website_$(date +%Y%m%d).sql
mysql -u root -p website < /opt/website/current/backend/db/migrations/0010_performance_indexes.sql

# 4. 验证性能
/opt/website/scripts/monitor-performance.sh
```

## 详细步骤

### 前置条件

- [x] 已配置SSH密钥访问ECS
- [x] ECS安装了Java 8+、MySQL 8+、Nginx
- [x] 已有正常运行的旧版本应用

### 步骤1: 代码检查与构建

```bash
cd /Users/wangweijie/projects/idea/website-backend

# 确认代码最新
git status
git pull

# 本地测试
mvn test

# 本地构建验证
mvn clean package -DskipTests
```

### 步骤2: 部署新版本

```bash
# 完整部署（前后端）
./deploy/production/deploy.sh \
  --host <ECS_IP> \
  --identity ~/.ssh/id_ed25519 \
  --frontend /Users/wangweijie/projects/vs/website

# 部署脚本会自动：
# ✓ 构建JAR包
# ✓ 上传到ECS
# ✓ 健康检查
# ✓ 原子切换版本
# ✓ 失败自动回滚
```

### 步骤3: ECS系统优化（首次必须）

```bash
# 登录ECS
ssh root@<ECS_IP>

# 执行优化脚本
cd /opt/website/current/deploy/production
sudo bash optimize-ecs.sh

# 脚本会优化：
# ✓ 内核网络参数
# ✓ 文件句柄限制
# ✓ Nginx配置
# ✓ 创建监控脚本
# ✓ 设置日志清理

# 重启以应用所有配置
sudo reboot
```

### 步骤4: 数据库索引优化

```bash
# 等待ECS重启完成后连接
ssh root@<ECS_IP>

# 备份数据库（重要！）
mysqldump -u root -p website > /backup/website_$(date +%Y%m%d_%H%M%S).sql

# 执行索引创建（建议在低峰时段）
mysql -u root -p website < /opt/website/current/backend/db/migrations/0010_performance_indexes.sql

# 验证索引创建
mysql -u root -p website -e "SHOW INDEX FROM user;"
mysql -u root -p website -e "SHOW INDEX FROM music_play_history;"
```

### 步骤5: Nginx配置更新

```bash
# 备份现有配置
sudo cp /etc/nginx/sites-available/website /etc/nginx/sites-available/website.bak.$(date +%Y%m%d)

# 新配置已在 optimize-ecs.sh 中创建，检查是否生效
sudo nginx -t

# 如果配置有问题，手动更新
sudo cp /opt/website/current/deploy/production/nginx.conf /etc/nginx/sites-available/website
sudo nginx -t
sudo systemctl reload nginx

# 创建Nginx缓存目录
sudo mkdir -p /var/cache/nginx/website
sudo chown -R www-data:www-data /var/cache/nginx
```

### 步骤6: 验证部署

```bash
# 健康检查
curl http://localhost:8090/actuator/health

# 应该返回: {"status":"UP"}

# 检查JVM内存
curl http://localhost:8090/actuator/metrics/jvm.memory.used

# 运行性能监控
/opt/website/scripts/monitor-performance.sh
```

### 步骤7: 预热缓存

```bash
# 从本地或ECS上执行
# 访问高频接口，让缓存预热

curl https://api.751152.xyz/api/public/nav
curl https://api.751152.xyz/api/public/categories
curl https://api.751152.xyz/api/public/links

# 等待5-10分钟后检查缓存命中率
curl https://api.751152.xyz/actuator/metrics/cache.gets
```

### 步骤8: 性能验证

```bash
# 在本地运行验证脚本
API_HOST=https://api.751152.xyz ./scripts/verify-performance.sh

# 预期结果：
# ✓ 服务健康状态: UP
# ✓ 内存使用正常 (< 85%)
# ✓ 连接池使用正常 (< 80%)
# ✓ 整体缓存命中率 > 70%
# ✓ 导航API响应时间 < 100ms
# ✓ GC暂停时间正常 (< 200ms)
```

## 性能对比

### 优化前
- 导航API：P95 ~300ms
- 音乐播放：无缓存，每次 1-2s
- JVM GC：300-500ms 暂停
- 吞吐量：~300 QPS

### 优化后（预期）
- 导航API：P95 < 100ms（缓存命中 < 10ms）
- 音乐播放：缓存命中 < 20ms
- JVM GC：< 200ms 暂停
- 吞吐量：> 500 QPS

## 常见问题

### Q1: 部署失败如何回滚？
```bash
ssh root@<ECS_IP> 'bash /opt/website/current/ops/remote-rollback.sh previous'
```

### Q2: 索引创建太慢怎么办？
```bash
# 使用ALGORITHM=INPLACE避免锁表
ALTER TABLE music_play_history 
ADD INDEX idx_user_played (user_id, played_at DESC) 
ALGORITHM=INPLACE, LOCK=NONE;
```

### Q3: 内存占用过高？
```bash
# 调整JVM堆内存（在 website-api.service 中）
-Xms1024m -Xmx1536m  # 降低到1.5GB

# 重启服务
sudo systemctl restart website-api
```

### Q4: 缓存命中率低？
```bash
# 检查缓存配置
curl http://localhost:8090/actuator/metrics/cache.size

# 增加缓存大小（修改 CacheConfig.java）
# 重新部署
```

### Q5: Nginx限流过于严格？
```bash
# 编辑 /etc/nginx/sites-available/website
# 调整 rate 参数
limit_req_zone $binary_remote_addr zone=api_limit:10m rate=100r/s;

# 重载配置
sudo nginx -t && sudo systemctl reload nginx
```

## 监控清单（前24小时）

- [ ] 应用正常启动，无ERROR日志
- [ ] 接口响应时间正常
- [ ] JVM内存稳定在70-80%
- [ ] GC暂停时间 < 200ms
- [ ] 数据库连接池正常
- [ ] 缓存命中率逐步提升
- [ ] CPU负载正常（< 70%）
- [ ] 磁盘IO正常
- [ ] 无业务错误反馈

## 下一步

- 接入Prometheus + Grafana进行可视化监控
- 配置告警规则（内存、GC、响应时间）
- 定期分析慢查询日志
- 根据实际负载调整JVM参数

## 帮助

详细文档：
- [完整优化方案](performance-optimization.md)
- [发布检查清单](performance-release-checklist.md)
- [JVM调优指南](../deploy/production/jvm-tuning.md)
