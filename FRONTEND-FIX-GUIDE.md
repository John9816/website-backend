# 前端崩溃修复指南

## 🔍 问题诊断

### 根本原因
前端调用的API路径与后端不匹配：

| 前端调用 | 后端实际路径 | 状态 |
|---------|------------|------|
| `/nav/categories` | `/api/public/nav` | ❌ 401错误 |
| `/music/search` | `/api/v1/music/search` | ❌ 401错误 |
| `/music/play` | `/api/v1/music/play` | ❌ 401错误 |

### 症状
- 前端页面白屏或无法加载
- 浏览器控制台显示401错误
- 所有API请求失败

---

## ✅ 已实施的修复

### 1. 创建兼容层 `LegacyApiController`
添加了旧API路径支持，无需修改前端代码：

**导航API兼容：**
- `/nav` → 转发到 CategoryService
- `/nav/categories` → 转发到 CategoryService

**音乐API兼容：**
- `/music/search` → 转发到 MusicService
- `/music/play` → 转发到 MusicService
- `/music/lyric` → 转发到 MusicService
- `/music/toplist` 等所有音乐端点

### 2. 更新安全配置 `SecurityConfig`
```java
// 公开访问
.antMatchers("/nav/**").permitAll()

// 需要认证
.antMatchers("/music/**").authenticated()
.antMatchers("/videos/**").authenticated()
.antMatchers("/books/**").authenticated()
```

---

## 🚀 部署步骤

### 方法1：使用部署脚本（推荐）
```bash
cd /Users/wangweijie/projects/idea/website-backend
chmod +x deploy-fix.sh
./deploy-fix.sh
```

### 方法2：手动部署
```bash
# 1. 上传jar包
scp target/website-0.0.1-SNAPSHOT.jar root@8.156.95.93:/opt/website/website.jar

# 2. 重启服务
ssh root@8.156.95.93 "systemctl restart website"

# 3. 检查状态
ssh root@8.156.95.93 "systemctl status website"
```

---

## 🧪 验证修复

### 测试旧API路径
```bash
# 1. 测试导航API（应该返回200）
curl -i https://api.751152.xyz/nav/categories

# 2. 测试音乐搜索（应该返回200或需要认证）
curl -i "https://api.751152.xyz/music/search?source=netease&keyword=test"

# 3. 访问前端（应该正常显示）
curl -i https://hi.751152.xyz/
```

### 预期结果
- `/nav/categories` → HTTP 200，返回导航数据
- `/music/search` → HTTP 401（未登录）或 HTTP 200（已登录）
- 前端页面正常加载，不再白屏

---

## 📋 文件清单

### 修改的文件
1. `src/main/java/com/example/website/config/SecurityConfig.java`
   - 添加 `/nav/**` 公开访问
   - 添加 `/music/**` 等认证访问

2. `src/main/java/com/example/website/controller/LegacyApiController.java` ✨ **新文件**
   - 导航API兼容（2个端点）
   - 音乐API兼容（12个端点）

### 新增的脚本
- `deploy-fix.sh` - 一键部署修复
- `diagnose-frontend.sh` - 诊断前端问题
- `emergency-rollback.sh` - 紧急回滚

---

## 🔄 回滚方案

如果修复后仍有问题：

```bash
# 方法1：回滚到上一个版本
ssh root@8.156.95.93 'bash /opt/website/current/ops/remote-rollback.sh previous'

# 方法2：重新部署之前的jar包
ssh root@8.156.95.93 << 'EOF'
cd /opt/website/releases
ls -lt | head -5  # 查看历史版本
cp releases/20260912_123456/website.jar /opt/website/website.jar
systemctl restart website
EOF
```

---

## 🛠️ 技术细节

### LegacyApiController 实现原理
```java
@RestController
public class LegacyApiController {
    
    @GetMapping("/nav/categories")  // 前端调用的旧路径
    public ApiResponse<List<CategoryView>> getNavCategories() {
        // 直接调用 CategoryService，无需HTTP转发
        return ApiResponse.ok(categoryService.listAll(...));
    }
    
    @GetMapping("/music/search")  // 前端调用的旧路径
    public ApiResponse<SearchResultView> musicSearch(...) {
        // 直接调用 MusicService，无需HTTP转发
        return ApiResponse.ok(musicService.search(...));
    }
}
```

### 为什么不用 HTTP 重定向？
- **性能更好**：直接调用Service，避免二次HTTP请求
- **更简单**：不需要配置Nginx rewrite规则
- **更安全**：保持Spring Security的认证逻辑
- **可维护**：所有路由逻辑在Java代码中，易于追踪

---

## 📊 影响范围

### ✅ 已兼容的API
- 导航：`/nav`, `/nav/categories`
- 音乐：`/music/*` 全部12个端点
- 视频：`/videos/*` 全部端点（通过SecurityConfig）
- 书籍：`/books/*` 全部端点（通过SecurityConfig）

### 🔒 保持的安全性
- 公开API（导航）：无需认证
- 受保护API（音乐/视频/书籍）：需要JWT认证
- 会员API（音乐播放）：需要会员资格检查

---

## 💡 长期优化建议

### 前端优化（可选）
未来可以逐步将前端API调用改为新路径：
```javascript
// 旧路径（当前兼容）
fetch('/nav/categories')
fetch('/music/search?source=netease&keyword=test')

// 新路径（推荐）
fetch('/api/public/nav')
fetch('/api/v1/music/search?source=netease&keyword=test')
```

### 监控建议
添加旧路径的访问日志，了解使用情况：
```java
@GetMapping("/nav/categories")
public ApiResponse<List<CategoryView>> getNavCategories() {
    log.info("Legacy API called: /nav/categories"); // 监控点
    return ApiResponse.ok(categoryService.listAll(...));
}
```

---

## 📞 故障排查

### 问题：部署后仍然401
```bash
# 检查SecurityConfig是否生效
ssh root@8.156.95.93
grep -A 5 "antMatchers.*nav" /opt/website/current/src/main/java/com/example/website/config/SecurityConfig.java

# 检查LegacyApiController是否部署
jar tf /opt/website/website.jar | grep LegacyApiController
```

### 问题：前端仍然白屏
```bash
# 查看浏览器控制台
# 检查是否还有其他失败的API调用

# 测试所有可能的前端API
curl -i https://api.751152.xyz/nav
curl -i https://api.751152.xyz/nav/categories
curl -i "https://api.751152.xyz/music/search?source=netease&keyword=test"
```

### 问题：服务无法启动
```bash
# 查看启动日志
ssh root@8.156.95.93 "journalctl -u website -n 100 --no-pager"

# 检查端口占用
ssh root@8.156.95.93 "netstat -tlnp | grep 8080"
```

---

## ✅ 成功标志

修复成功后，你应该看到：

1. **后端日志**：无SecurityConfig错误
2. **API测试**：`/nav/categories` 返回 HTTP 200
3. **前端页面**：https://hi.751152.xyz/ 正常显示
4. **浏览器控制台**：无401错误

---

## 📝 修改记录

- **2026-09-13 18:30** - 创建LegacyApiController
- **2026-09-13 18:30** - 更新SecurityConfig
- **2026-09-13 18:30** - 编译成功，待部署

---

**当前状态：✅ 代码已修复，编译成功，等待部署**
