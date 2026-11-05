# 🚨 紧急部署指令

> 历史记录，状态与命令均可能已过期。不要直接覆盖线上 JAR；使用 `docs/production-release.md` 中的版本化发布流程。

## 当前状态
- ✅ 代码已修复并编译成功
- ✅ jar包已生成：`target/website-0.0.1-SNAPSHOT.jar`
- ❌ **ECS上还在运行旧代码**（`/nav/categories` 返回401）

## 📦 需要部署的文件
- `target/website-0.0.1-SNAPSHOT.jar` (14MB)

---

## 🚀 立即部署（请在终端执行）

### 步骤1：上传jar包到ECS
```bash
cd /Users/wangweijie/projects/idea/website-backend
scp target/website-0.0.1-SNAPSHOT.jar root@8.156.95.93:/opt/website/website.jar
```
**连接方式：** 使用已授权的 SSH 私钥，参考 `docs/production-release.md`。

### 步骤2：重启后端服务
```bash
ssh root@8.156.95.93 "systemctl restart website && sleep 3 && systemctl status website"
```

### 步骤3：验证修复
```bash
./test-fix-locally.sh
```

**预期结果：**
- `/nav/categories` 应该返回 **HTTP 200** ✅
- 前端 https://hi.751152.xyz/ 应该正常显示 ✅

---

## 📋 完整命令（一次性复制）

```bash
# 1. 上传jar
scp target/website-0.0.1-SNAPSHOT.jar root@8.156.95.93:/opt/website/website.jar

# 2. 重启服务
ssh root@8.156.95.93 "systemctl restart website"

# 3. 检查状态（等待3秒）
sleep 3
ssh root@8.156.95.93 "systemctl status website"

# 4. 测试API
curl -s https://api.751152.xyz/nav/categories | jq .

# 5. 测试前端
curl -s https://hi.751152.xyz/ | head -20
```

---

## ⚡ 如果你不想手动执行

我已经创建了 `deploy-fix.sh` 脚本，但是由于安全限制，我无法直接执行远程部署命令。

**你可以运行：**
```bash
./deploy-fix.sh
```

这个脚本会自动执行上面的所有步骤。

---

## 🔍 部署后验证清单

- [ ] `/nav/categories` 返回 HTTP 200（不是401）
- [ ] `/api/public/nav` 返回 HTTP 200（已经正常）
- [ ] 前端页面 https://hi.751152.xyz/ 正常显示
- [ ] 浏览器控制台无401错误

---

## 📞 如果部署失败

### 检查服务状态
```bash
ssh root@8.156.95.93 "journalctl -u website -n 50 --no-pager"
```

### 回滚到旧版本
```bash
ssh root@8.156.95.93 "systemctl stop website && \
  cp /opt/website/backup/website.jar.backup /opt/website/website.jar && \
  systemctl start website"
```

---

## 💡 为什么需要部署？

修复的代码在你本地的 `target/` 目录中：
- ✅ `LegacyApiController.java` - 添加了旧路径兼容
- ✅ `SecurityConfig.java` - 更新了安全规则

但ECS服务器上运行的还是旧的jar包，所以前端还在崩溃。

**部署后，旧路径就能正常工作了！**

---

**准备好了吗？请复制上面的命令到终端执行！** 🚀
