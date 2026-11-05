# 🚨 前端崩溃问题修复总结

> 历史记录，不能作为当前生产状态或发布指令。发布请以 `docs/production-release.md` 为准。

## 问题诊断

### 根本原因
前端调用的是**旧API路径**，但后端只提供**新API路径**，导致所有请求返回401未授权错误。

| 前端调用 | 后端提供 | 结果 |
|---------|---------|------|
| `/nav/categories` | ❌ 不存在 | 401错误 |
| `/music/search` | ❌ 不存在 | 401错误 |
| - | `/api/public/nav` ✅ | 前端不知道 |
| - | `/api/v1/music/search` ✅ | 前端不知道 |

---

## ✅ 已完成的修复

### 1. 创建兼容控制器
**文件：** `src/main/java/com/example/website/controller/LegacyApiController.java`

添加了14个旧路径端点，转发到实际的Service：
- 导航：`/nav`, `/nav/categories`
- 音乐：`/music/search`, `/music/play`, `/music/lyric` 等12个

### 2. 更新安全配置
**文件：** `src/main/java/com/example/website/config/SecurityConfig.java`

```java
// 允许旧路径访问
.antMatchers("/nav/**").permitAll()           // 公开
.antMatchers("/music/**").authenticated()     // 需要登录
.antMatchers("/videos/**").authenticated()    // 需要登录
.antMatchers("/books/**").authenticated()     // 需要登录
```

### 3. 编译成功
```bash
✅ target/website-0.0.1-SNAPSHOT.jar (52MB)
✅ 编译时间：2026-09-13 18:22
```

---

## 🚀 立即部署（必须执行）

### 方法1：一键部署（推荐）
```bash
cd /Users/wangweijie/projects/idea/website-backend
./quick-deploy.sh
```

### 方法2：手动部署
```bash
# 步骤1：上传jar（使用已授权的 SSH 私钥）
scp target/website-0.0.1-SNAPSHOT.jar root@8.156.95.93:/opt/website/website.jar

# 步骤2：重启服务
ssh root@8.156.95.93 "systemctl restart website"

# 步骤3：验证（等待5秒后）
curl https://api.751152.xyz/nav/categories
```

---

## 🧪 验证修复

### 当前状态（部署前）
```bash
❌ /nav/categories → HTTP 401
✅ /api/public/nav → HTTP 200
✅ 前端页面 → HTTP 200（但功能不正常）
```

### 部署后预期
```bash
✅ /nav/categories → HTTP 200
✅ /api/public/nav → HTTP 200
✅ 前端页面 → HTTP 200（功能正常）
✅ 浏览器控制台 → 无401错误
```

### 验证命令
```bash
./test-fix-locally.sh
```

---

## 📁 相关文档

| 文档 | 说明 |
|------|------|
| `DEPLOY-NOW.md` | 详细部署指令 |
| `FRONTEND-FIX-GUIDE.md` | 完整修复指南（26KB） |
| `quick-deploy.sh` | 一键部署脚本 |
| `test-fix-locally.sh` | API测试脚本 |
| `emergency-rollback.sh` | 紧急回滚脚本 |

---

## 🎯 核心代码

### LegacyApiController（核心修复）
```java
@RestController
public class LegacyApiController {
    
    // 导航API兼容
    @GetMapping("/nav/categories")
    public ApiResponse<List<CategoryView>> getNavCategories() {
        return ApiResponse.ok(categoryService.listAll(...));
    }
    
    // 音乐搜索兼容
    @GetMapping("/music/search")
    public ApiResponse<SearchResultView> musicSearch(
        @RequestParam String source,
        @RequestParam String keyword
    ) {
        return ApiResponse.ok(musicService.search(...));
    }
    
    // ... 共14个端点
}
```

### SecurityConfig（安全规则）
```java
http.authorizeRequests()
    .antMatchers("/nav/**").permitAll()        // 新增
    .antMatchers("/music/**").authenticated()   // 新增
    .antMatchers("/videos/**").authenticated()  // 新增
    .antMatchers("/books/**").authenticated()   // 新增
    // ... 其他规则
```

---

## ⚠️ 重要提醒

### 1. **必须部署才能生效**
修复的代码在本地，ECS上运行的还是旧代码。

### 2. **无需修改前端**
兼容层让前端无需任何改动就能正常工作。

### 3. **向后兼容**
新旧路径都能用：
- `/nav/categories` ✅（旧，前端用）
- `/api/public/nav` ✅（新，未来推荐）

### 4. **保持安全性**
需要登录的接口仍然需要JWT token。

---

## 🔄 如果需要回滚

```bash
# 方法1：使用回滚脚本
./emergency-rollback.sh

# 方法2：手动回滚
ssh root@8.156.95.93 "
  systemctl stop website
  cp /opt/website/backup/website.jar.backup /opt/website/website.jar
  systemctl start website
"
```

---

## 📊 影响范围

### 受益的API
- ✅ 导航分类（前端首页）
- ✅ 音乐搜索（音乐功能）
- ✅ 音乐播放（音乐播放器）
- ✅ 歌词获取（歌词显示）
- ✅ 视频接口（视频功能）
- ✅ 书籍接口（阅读功能）

### 保持不变的
- ✅ 用户认证（JWT）
- ✅ 权限检查（会员、管理员）
- ✅ 新API路径（/api/v1/*）
- ✅ 性能优化（缓存、索引）

---

## ✅ 修复清单

- [x] 创建 LegacyApiController
- [x] 更新 SecurityConfig
- [x] 编译成功
- [x] jar文件生成（52MB）
- [x] 创建部署脚本
- [x] 创建测试脚本
- [x] 创建回滚脚本
- [ ] **部署到ECS** ← 你需要做这一步
- [ ] **验证修复** ← 部署后测试

---

## 🎉 下一步

**立即执行部署：**
```bash
./quick-deploy.sh
```

**或者复制这三条命令：**
```bash
scp target/website-0.0.1-SNAPSHOT.jar root@8.156.95.93:/opt/website/website.jar
ssh root@8.156.95.93 "systemctl restart website"
curl https://api.751152.xyz/nav/categories
```

部署后前端应该立即恢复正常！🚀

---

**状态：** ✅ 代码已修复 | ⏳ 等待部署 | 预计修复时间：<5分钟
