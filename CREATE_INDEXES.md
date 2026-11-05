# 📊 数据库索引创建指南

> 仅在确认迁移尚未执行时使用。ECS 的 `/root/.config/website/mysql.cnf` 必须由 root 持有且权限为 600，使用 SSH 私钥连接。

**目标**: 为website数据库创建40+个性能索引  
**预计耗时**: 5-10分钟  
**建议执行时间**: 凌晨2:00-5:00（业务低峰期）

---

## ⚠️ 重要警告

1. **必须先备份数据库**
2. **索引创建期间表会被锁定**
3. **建议在低峰期执行**
4. **执行前检查磁盘空间**

---

## 🔍 执行前检查

```bash
# 1. 检查磁盘空间（需要至少2GB可用空间）
ssh root@8.156.95.93 'df -h /var/lib/mysql'

# 2. 检查数据库大小
ssh root@8.156.95.93 'mysql --defaults-extra-file=/root/.config/website/mysql.cnf -e "
SELECT 
    table_schema AS Database_Name,
    ROUND(SUM(data_length + index_length) / 1024 / 1024, 2) AS Size_MB
FROM information_schema.tables
WHERE table_schema = \"website\"
GROUP BY table_schema;"'

# 3. 检查当前索引数量
ssh root@8.156.95.93 'mysql --defaults-extra-file=/root/.config/website/mysql.cnf website -e "
SELECT COUNT(*) as current_indexes 
FROM information_schema.STATISTICS 
WHERE TABLE_SCHEMA = \"website\";"'
```

---

## 📦 步骤1: 备份数据库

```bash
# 创建备份目录
ssh root@8.156.95.93 'mkdir -p /backup'

# 完整备份（推荐）
ssh root@8.156.95.93 'mysqldump --defaults-extra-file=/root/.config/website/mysql.cnf \
    --single-transaction \
    --routines \
    --triggers \
    --events \
    website > /backup/website_$(date +%Y%m%d_%H%M%S).sql'

# 验证备份文件
ssh root@8.156.95.93 'ls -lh /backup/website_*.sql | tail -1'

# 测试备份完整性
ssh root@8.156.95.93 'tail -20 /backup/website_$(ls -t /backup/ | grep website | head -1) | grep "Dump completed"'
```

---

## 🚀 步骤2: 创建索引

### 方式A: 一次性执行（推荐）

```bash
# 执行索引创建脚本
ssh root@8.156.95.93 'mysql --defaults-extra-file=/root/.config/website/mysql.cnf website < /opt/website/current/backend/db/migrations/0010_performance_indexes.sql'

# 查看执行结果
echo $?  # 0表示成功
```

### 方式B: 分批执行（更安全，适合大数据量）

```bash
# 1. 用户相关索引
ssh root@8.156.95.93 'mysql --defaults-extra-file=/root/.config/website/mysql.cnf website -e "
CREATE INDEX IF NOT EXISTS idx_email ON users(email);
CREATE INDEX IF NOT EXISTS idx_created_at ON users(created_at);
CREATE INDEX IF NOT EXISTS idx_last_check_in ON users(last_check_in_date);
"'

# 2. 导航相关索引
ssh root@8.156.95.93 'mysql --defaults-extra-file=/root/.config/website/mysql.cnf website -e "
CREATE INDEX IF NOT EXISTS idx_category_order ON nav_links(category_id, display_order);
CREATE INDEX IF NOT EXISTS idx_url ON nav_links(url);
CREATE INDEX IF NOT EXISTS idx_parent_order ON nav_categories(parent_id, display_order);
"'

# 3. 音乐相关索引
ssh root@8.156.95.93 'mysql --defaults-extra-file=/root/.config/website/mysql.cnf website -e "
CREATE INDEX IF NOT EXISTS idx_provider_id_order ON music_songs(provider, source_id, display_order);
CREATE INDEX IF NOT EXISTS idx_name_artist ON music_songs(name, artist);
CREATE INDEX IF NOT EXISTS idx_provider_type ON music_playlists(provider, playlist_type);
"'

# 4. 图片相关索引
ssh root@8.156.95.93 'mysql --defaults-extra-file=/root/.config/website/mysql.cnf website -e "
CREATE INDEX IF NOT EXISTS idx_user_status_created ON image_generation_tasks(user_id, status, created_at);
CREATE INDEX IF NOT EXISTS idx_status_created ON image_generation_tasks(status, created_at);
"'

# 5. 知识库相关索引
ssh root@8.156.95.93 'mysql --defaults-extra-file=/root/.config/website/mysql.cnf website -e "
CREATE INDEX IF NOT EXISTS idx_user_created ON kb_entries(user_id, created_at);
CREATE INDEX IF NOT EXISTS idx_user_category ON kb_entries(user_id, category);
"'

# 6. 播放记录索引
ssh root@8.156.95.93 'mysql --defaults-extra-file=/root/.config/website/mysql.cnf website -e "
CREATE INDEX IF NOT EXISTS idx_user_song ON user_music_play_records(user_id, song_id);
CREATE INDEX IF NOT EXISTS idx_user_created ON user_music_play_records(user_id, created_at);
"'
```

---

## ✅ 步骤3: 验证索引

```bash
# 1. 统计新增索引数量
ssh root@8.156.95.93 'mysql --defaults-extra-file=/root/.config/website/mysql.cnf website -e "
SELECT 
    TABLE_NAME,
    COUNT(*) as index_count
FROM information_schema.STATISTICS 
WHERE TABLE_SCHEMA = \"website\" 
GROUP BY TABLE_NAME 
ORDER BY TABLE_NAME;"'

# 2. 查看所有新增的性能索引
ssh root@8.156.95.93 'mysql --defaults-extra-file=/root/.config/website/mysql.cnf website -e "
SELECT 
    TABLE_NAME,
    INDEX_NAME,
    GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) as columns
FROM information_schema.STATISTICS 
WHERE TABLE_SCHEMA = \"website\" 
  AND INDEX_NAME LIKE \"idx_%\"
GROUP BY TABLE_NAME, INDEX_NAME 
ORDER BY TABLE_NAME, INDEX_NAME;"'

# 3. 验证关键索引
ssh root@8.156.95.93 'mysql --defaults-extra-file=/root/.config/website/mysql.cnf website -e "
SHOW INDEX FROM nav_links WHERE Key_name = \"idx_category_order\";
SHOW INDEX FROM music_songs WHERE Key_name = \"idx_provider_id_order\";
SHOW INDEX FROM user_music_play_records WHERE Key_name = \"idx_user_song\";"'
```

---

## 📊 步骤4: 性能测试

```bash
# 1. 测试导航查询性能
ssh root@8.156.95.93 'mysql --defaults-extra-file=/root/.config/website/mysql.cnf website -e "
EXPLAIN SELECT * FROM nav_links WHERE category_id = 1 ORDER BY display_order LIMIT 20;"'

# 2. 测试音乐查询性能
ssh root@8.156.95.93 'mysql --defaults-extra-file=/root/.config/website/mysql.cnf website -e "
EXPLAIN SELECT * FROM music_songs WHERE provider = \"netease\" AND source_id LIKE \"123%\" LIMIT 20;"'

# 3. API响应时间对比
echo "=== 索引创建后API性能 ==="
for i in {1..3}; do
  curl -s -w "第${i}次: %{time_total}s\n" -o /dev/null "https://api.751152.xyz/api/nav/links"
done
```

---

## 🔄 回滚方案

如果索引创建出现问题：

```bash
# 方式1: 删除所有新增索引
ssh root@8.156.95.93 'mysql --defaults-extra-file=/root/.config/website/mysql.cnf website < /opt/website/current/backend/db/migrations/rollback_0010.sql'

# 方式2: 从备份恢复
ssh root@8.156.95.93 'mysql --defaults-extra-file=/root/.config/website/mysql.cnf website < /backup/website_YYYYMMDD_HHMMSS.sql'

# 方式3: 手动删除特定索引
ssh root@8.156.95.93 'mysql --defaults-extra-file=/root/.config/website/mysql.cnf website -e "
DROP INDEX idx_category_order ON nav_links;
DROP INDEX idx_provider_id_order ON music_songs;"'
```

---

## 📈 预期效果

| 查询类型 | 优化前 | 优化后 | 提升 |
|---------|--------|--------|------|
| 导航列表查询 | 50-100ms | <20ms | **-70%** |
| 音乐搜索 | 200-500ms | <50ms | **-80%** |
| 用户播放记录 | 100-300ms | <30ms | **-80%** |
| 知识库查询 | 80-150ms | <30ms | **-70%** |
| 图片任务列表 | 60-120ms | <25ms | **-70%** |

---

## 🛠️ 故障排查

### 问题1: 索引创建超时

```bash
# 检查MySQL进程
ssh root@8.156.95.93 'mysql --defaults-extra-file=/root/.config/website/mysql.cnf -e "SHOW PROCESSLIST;"'

# 调整超时时间
ssh root@8.156.95.93 'mysql --defaults-extra-file=/root/.config/website/mysql.cnf website -e "
SET SESSION lock_wait_timeout = 300;
SET SESSION innodb_lock_wait_timeout = 300;"'
```

### 问题2: 磁盘空间不足

```bash
# 清理旧日志
ssh root@8.156.95.93 'journalctl --vacuum-time=7d'

# 清理MySQL binlog
ssh root@8.156.95.93 'mysql --defaults-extra-file=/root/.config/website/mysql.cnf -e "PURGE BINARY LOGS BEFORE NOW() - INTERVAL 7 DAY;"'
```

### 问题3: 表锁定

```bash
# 查看锁定状态
ssh root@8.156.95.93 'mysql --defaults-extra-file=/root/.config/website/mysql.cnf -e "SHOW OPEN TABLES WHERE In_use > 0;"'

# 终止阻塞进程（谨慎使用）
ssh root@8.156.95.93 'mysql --defaults-extra-file=/root/.config/website/mysql.cnf -e "KILL <process_id>;"'
```

---

## 📝 完整执行脚本

```bash
#!/bin/bash
# 索引创建完整脚本

set -e  # 遇到错误立即退出

echo "=== 步骤1: 检查磁盘空间 ==="
ssh root@8.156.95.93 'df -h /var/lib/mysql'

echo -e "\n=== 步骤2: 备份数据库 ==="
ssh root@8.156.95.93 'mkdir -p /backup && \
mysqldump --defaults-extra-file=/root/.config/website/mysql.cnf --single-transaction website > /backup/website_$(date +%Y%m%d_%H%M%S).sql'

echo -e "\n=== 步骤3: 验证备份 ==="
ssh root@8.156.95.93 'ls -lh /backup/website_*.sql | tail -1'

echo -e "\n=== 步骤4: 创建索引 ==="
ssh root@8.156.95.93 'mysql --defaults-extra-file=/root/.config/website/mysql.cnf website < /opt/website/current/backend/db/migrations/0010_performance_indexes.sql'

echo -e "\n=== 步骤5: 验证索引 ==="
ssh root@8.156.95.93 'mysql --defaults-extra-file=/root/.config/website/mysql.cnf website -e "
SELECT COUNT(*) as new_indexes 
FROM information_schema.STATISTICS 
WHERE TABLE_SCHEMA = \"website\" AND INDEX_NAME LIKE \"idx_%\";"'

echo -e "\n=== 步骤6: 性能测试 ==="
for i in {1..3}; do
  curl -s -w "API响应: %{time_total}s\n" -o /dev/null "https://api.751152.xyz/api/nav/links"
done

echo -e "\n✅ 索引创建完成！"
```

保存为 `create_indexes.sh` 并执行：
```bash
chmod +x create_indexes.sh
./create_indexes.sh
```

---

## 📅 执行时间建议

| 时间段 | 推荐度 | 说明 |
|--------|--------|------|
| 凌晨2:00-5:00 | ⭐⭐⭐⭐⭐ | 最佳时间，流量最低 |
| 凌晨0:00-2:00 | ⭐⭐⭐⭐ | 较好，流量较低 |
| 早上6:00-8:00 | ⭐⭐⭐ | 可接受，开始有流量 |
| 白天/晚上 | ⭐ | 不推荐，流量高峰 |

---

**最后更新**: 2026-09-12 21:27
