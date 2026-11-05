# ✅ jar包已上传成功！

> 历史记录，不能证明当前线上文件或服务状态。发布与重启请以 `docs/production-release.md` 为准。

## 当前状态
- ✅ 修复后的jar已上传到：`/opt/website/current/backend/website.jar`
- ✅ 文件大小：52MB
- ⏳ **需要重启服务以应用修复**

---

## 🚀 请在你的终端执行以下命令

### 方法1：重启服务（推荐）
```bash
ssh root@8.156.95.93 "systemctl restart website-api"
```

### 方法2：查看服务状态
```bash
ssh root@8.156.95.93 "systemctl status website-api"
```

### 方法3：查看启动日志
```bash
ssh root@8.156.95.93 "journalctl -u website-api -f"
```
按 `Ctrl+C` 退出日志查看

---

## 🧪 重启后验证修复

### 1. 测试旧路径（应该返回200）
```bash
curl -I https://api.751152.xyz/nav/categories
```

### 2. 查看详细响应
```bash
curl https://api.751152.xyz/nav/categories
```

### 3. 测试前端页面
```bash
curl -I https://hi.751152.xyz/
```

### 4. 或者运行测试脚本
```bash
./test-fix-locally.sh
```

---

## ✅ 预期结果

### 修复成功标志
- ✅ `/nav/categories` 返回 HTTP 200（不是401）
- ✅ 返回JSON数据包含导航分类
- ✅ 前端页面 https://hi.751152.xyz/ 正常显示
- ✅ 浏览器控制台没有401错误

### 如果看到401
可能服务还在启动中，等待10-20秒后再次测试。

---

## 📝 一键复制命令

```bash
# 完整流程（复制粘贴执行）
ssh root@8.156.95.93 "systemctl restart website-api"
sleep 10
curl -I https://api.751152.xyz/nav/categories
curl https://hi.751152.xyz/ | head -20
```

---

## 🔍 如果重启后仍有问题

### 查看服务日志
```bash
ssh root@8.156.95.93 "journalctl -u website-api -n 100 --no-pager"
```

### 检查Java进程
```bash
ssh root@8.156.95.93 "ps aux | grep java"
```

### 检查端口监听
```bash
ssh root@8.156.95.93 "netstat -tlnp | grep 8090"
```

---

**准备好了吗？请复制上面的命令到你的终端执行！** 🚀

使用已授权的 SSH 私钥连接；参见 `docs/production-release.md`。
