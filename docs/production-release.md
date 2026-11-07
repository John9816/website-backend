# Website 前后端标准上线流程

本文适用于以下两个仓库一起发布到同一台 Linux ECS：

- 后端：`website-backend`，Spring Boot + MySQL，内网端口 `8090`
- 前端：`website`，Vite 静态文件 + Node 同源代理，内网端口 `3000`
- 公网：`api.751152.xyz` 指向后端，`hi.751152.xyz` 指向前端

发布脚本使用带版本号的目录和软链接切换，不直接覆盖正在运行的 JAR 或 `dist`。新版本健康检查失败时，会自动恢复上一个版本。

## 一、上线架构

```text
用户 -> Nginx :80/:443
          |-- api.751152.xyz -> Spring Boot :8090 -> MySQL
          `-- hi.751152.xyz  -> Node :3000 -> dist
                                      `-- /api/* -> Spring Boot :8090
```

服务器目录：

```text
/opt/website/
  current -> releases/<release-id>
  previous -> releases/<previous-release-id>
  releases/<release-id>/backend/website.jar
  releases/<release-id>/frontend/{dist,server}
  backend/.env                 # 后端密钥，不进 Git
  .env.ecs                     # 前端运行配置，不进 Git
  shared/uploads/images/       # 生成图片，跨版本持久化
  shared/uploads/avatars/      # 用户头像，跨版本持久化
  updates/                     # Android 更新包等持久文件
```

MySQL 运维账号凭据单独保存在 ECS 的 `/root/.config/website/mysql.cnf`，不进入发布目录或 Git。

## 二、首次部署准备

服务器至少需要：Linux、Java 17+、Node.js 20+、MySQL 8、Nginx、`curl`、`systemd`。标准服务使用 Java 17 支持的 G1 空闲回收参数；应用源码仍保持 Java 8 编译兼容。安全组只开放 SSH、80、443；不要开放 3000、8090、3306。

当前 2 核、约 1.6 GiB 内存主机的 JVM 参数、验证和回滚方法见 [`deploy/production/jvm-tuning.md`](../deploy/production/jvm-tuning.md)。堆大小统一配置在 `website-api.service`，不要在 `JAVA_TOOL_OPTIONS` 中重复设置 `-Xms` / `-Xmx`。

### 1. 连接 ECS

1. 在云厂商控制台找到 ECS 的公网 IPv4 地址和 SSH 端口，通常是 `22`。
2. 在 ECS 安全组入方向放行该 SSH 端口，来源建议限制为当前办公网络公网 IP，例如 `203.0.113.10/32`，不要长期对 `0.0.0.0/0` 开放 SSH。
3. 下载或找到创建 ECS 时绑定的私钥，并限制本地文件权限：

   ```bash
   chmod 600 ~/.ssh/website-ecs.pem
   ```

4. 测试 SSH。阿里云 Linux/CentOS 通常使用 `root`，Ubuntu 镜像通常使用 `ubuntu`；以控制台显示的登录用户名为准：

   ```bash
   ssh -i ~/.ssh/website-ecs.pem -p 22 root@<ECS_PUBLIC_IP>
   # Ubuntu 示例：
   ssh -i ~/.ssh/website-ecs.pem -p 22 ubuntu@<ECS_PUBLIC_IP>
   ```

   首次连接会显示服务器主机指纹。应先与云控制台提供的指纹核对，再接受写入 `known_hosts`。

5. 使用非 root 用户发布时，该用户必须可以无交互执行 `sudo`，否则自动发布会在远程激活阶段停止：

   ```bash
   ssh -i ~/.ssh/website-ecs.pem ubuntu@<ECS_PUBLIC_IP> 'sudo -n true && echo sudo-ok'
   ```

连接成功后，发布参数的对应关系如下：

```bash
./deploy/production/deploy.sh \
  --host <ECS_PUBLIC_IP> \
  --user root \
  --port 22 \
  --identity ~/.ssh/website-ecs.pem \
  --frontend /Users/wangweijie/projects/vs/website
```

若使用 Ubuntu 默认账号，把 `--user root` 改为 `--user ubuntu`。脚本会通过免密 `sudo` 安装 systemd 服务并写入 `/opt/website`。

常见连接错误：

- `Connection timed out`：检查公网 IP、SSH 端口、安全组来源 IP 和服务器防火墙。
- `Connection refused`：检查 SSH 服务是否启动，以及安全组端口是否与服务器 SSH 端口一致。
- `Permission denied (publickey)`：检查登录用户名、私钥是否匹配、私钥权限是否为 `600`。
- `Host key verification failed`：不要直接删除记录；先确认 ECS 是否重装或更换过 IP，并重新核对服务器指纹。
- `sudo: a password is required`：改用 root 发布，或为专用发布用户配置受控的免密 sudo。

### 2. 初始化数据库和生产配置

先创建数据库和专用账号，账号权限按实际运维策略收紧：

```sql
CREATE DATABASE website CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'website'@'127.0.0.1' IDENTIFIED BY '<strong-password>';
GRANT ALL PRIVILEGES ON website.* TO 'website'@'127.0.0.1';
FLUSH PRIVILEGES;
```

第一次执行发布脚本时，如果生产配置不存在，脚本会上传示例后停止：

```bash
./deploy/production/deploy.sh \
  --host <ECS_IP> \
  --identity ~/.ssh/id_ed25519 \
  --frontend /Users/wangweijie/projects/vs/website
```

登录服务器填写配置：

```bash
# root 用户直接执行；非 root 用户在命令前加 sudo。
cp /opt/website/config-examples/backend.env.example /opt/website/backend/.env
cp /opt/website/config-examples/frontend.env.example /opt/website/.env.ecs
openssl rand -base64 48
vi /opt/website/backend/.env
vi /opt/website/.env.ecs
chmod 600 /opt/website/backend/.env /opt/website/.env.ecs
```

必须替换数据库密码、`JWT_SECRET`、默认管理员密码和 Cloud Mail 密码。已有生产环境应沿用原 `JWT_SECRET`，否则所有现有登录态会失效。

运维命令需要连接数据库时，在 ECS 上创建仅 root 可读的 MySQL 配置文件，不在命令行传密码：

```bash
install -d -m 700 /root/.config/website
umask 077
vi /root/.config/website/mysql.cnf
chmod 600 /root/.config/website/mysql.cnf
```

文件内容为 `[client]` 段中的 `user=website`、`password=<实际数据库密码>`、`host=127.0.0.1`。`mysql` 和 `mysqldump` 调用时把 `--defaults-extra-file=/root/.config/website/mysql.cnf` 放在所有其他参数之前。若某次维护需要额外的全库权限，请单独创建受控的运维账号并更新该文件；不要把 root 或数据库密码写回脚本。

历史文档和部署脚本曾暴露 ECS 登录及数据库密码，并复用固定 JWT 密钥。正式上线前应在云控制台或服务器上轮换 SSH/root 与数据库凭据，签发独立的 JWT 密钥，并清理旧会话；旧值即使从当前文件删除，也仍可能保留在 Git 历史中。

把 `/opt/website/config-examples/nginx.conf` 合并到服务器 Nginx 配置，检查后 reload：

```bash
nginx -t
systemctl reload nginx
```

模板只含 HTTP。确认 DNS 和 HTTP 正常后，再用服务器现有的 ACME/Certbot 方式启用 HTTPS，避免覆盖已有证书配置。

## 三、每次发布前

1. 前后端相关改动已经提交，记录本次版本范围和负责人。
2. 确认生产配置没有写入 Git，数据库、邮件、上游 API 凭据仍有效。
3. 阅读后端 `src/main/resources/db/migrations` 中相对生产版本新增的 SQL。
4. 对生产 MySQL 做可恢复备份，并实际确认备份文件非空。
5. 明确回滚边界：代码可自动回滚；破坏性数据库变更必须另备回滚 SQL 或恢复方案。

数据库备份示例：

```bash
mysqldump --defaults-extra-file=/root/.config/website/mysql.cnf \
  --single-transaction --routines --triggers website \
  | gzip > "/opt/backups/website-$(date +%Y%m%d-%H%M%S).sql.gz"
```

当前项目没有接入 Flyway/Liquibase，历史迁移脚本也并非全部可重复执行。因此发布脚本不会自动跑 SQL。只执行本次新增且已审阅的迁移；不要把整个 migrations 目录重新执行一遍。长期建议接入 Flyway，并把 `ddl-auto` 从 `update` 改为 `validate`。

## 四、标准发布

在后端仓库执行：

```bash
./deploy/production/deploy.sh \
  --host <ECS_IP> \
  --identity ~/.ssh/id_ed25519 \
  --frontend /Users/wangweijie/projects/vs/website
```

脚本按顺序完成：

1. 拒绝未提交文件，读取前后端 Git SHA。
2. 执行后端 `mvn test` 和打包。
3. 前端执行 `npm ci`、类型检查和生产构建。
4. 生成带双仓库 SHA 的发布包并上传。
5. 原子切换 `/opt/website/current`。
6. 依次检查 `8090/health`、`3000/healthz` 和前端到后端的代理链路。
7. 任一检查失败时恢复旧软链接并重启旧版本。

本地只构建、不上传：

```bash
./deploy/production/deploy.sh \
  --build-only \
  --frontend /Users/wangweijie/projects/vs/website
```

只有紧急发布才使用 `--allow-dirty` 或 `--skip-tests`；发布元数据会记录脏工作区状态。`--skip-install` 可复用现有 `node_modules`，但标准发布应保留 `npm ci`。

## 五、上线后验证

注册账号目前默认享有永久会员（`APP_MEMBERSHIP_FREE_LIFETIME_ENABLED=true`）：会员查询直接返回有效的 `LIFETIME` 权益，新注册及已有用户无需兑换即可播放，也不会消耗兑换码。历史会员与兑换记录不变；登录校验、账号权限及各功能的独立用量限制仍生效。恢复原有会员规则时设置后端 `APP_MEMBERSHIP_FREE_LIFETIME_ENABLED=false`；恢复网页会员入口时设置前端构建变量 `VITE_MEMBERSHIP_ENABLED=true`，重新发布对应服务。

免费会员模式下验收普通账号和已过期账号的 `/api/v1/music/play`、旧版 `/music/play` 及预加载；应取得播放地址，实际播放写入历史，预加载不写入历史。网页个人菜单不展示会员状态或兑换入口，后台不展示兑换码菜单，直接打开 `/admin/redeem-codes` 应返回后台首页。

```bash
curl -fsS https://api.751152.xyz/health
curl -fsSI https://hi.751152.xyz/
curl -fsS https://hi.751152.xyz/api/public/nav >/dev/null
```

再人工验证登录、注册邮件验证码、首页导航、音乐搜索/播放、管理后台，以及本次变更直接涉及的路径。观察至少 15 分钟：

```bash
journalctl -u website-api -u website-ecs -f
systemctl status website-api website-ecs --no-pager
```

重点检查 HTTP 5xx、数据库连接池、上游超时、JVM 内存、磁盘空间和邮件发送错误。

## 六、回滚

代码回滚到上一版本：

```bash
ssh root@<ECS_IP> 'bash /opt/website/current/ops/remote-rollback.sh previous'
```

回滚到指定版本：

```bash
ls -1 /opt/website/releases
bash /opt/website/current/ops/remote-rollback.sh <release-id>
```

回滚脚本切换后会重新检查前后端健康状态；目标版本无法启动时会恢复回滚前版本。

数据库回滚不能依赖代码脚本自动完成。若本次 SQL 只做了向后兼容的加表、加可空字段，通常先回滚代码并保留字段；若有删列、改类型或数据重写，按发布前准备的回滚 SQL/备份恢复，并在维护窗口执行。

## 七、常见故障

- 发布提示配置缺失：按 `/opt/website/config-examples` 创建两个环境文件后重跑。
- 后端健康检查失败：查看 `journalctl -u website-api -n 200 --no-pager`，优先检查数据库和环境变量。
- 前端健康检查失败：查看 `journalctl -u website-ecs -n 200 --no-pager`，确认服务器 Node.js 版本和 `BACKEND_URL`。
- 域名正常、代理接口 502：先直查 `curl http://127.0.0.1:8090/health`，再查 Node 的 `/api/public/nav`。
- SSE 中断：确认 Nginx 的 `proxy_buffering off` 和较长的 `proxy_read_timeout` 仍在生效。
- 新版本需要数据库变更：先停止发布，完成备份和迁移审阅，不要依赖 Hibernate 猜测破坏性变更。

## 八、影视上游上线检查

影视目录由 ECS 后端抓取，发布前在 ECS 的后端环境文件中确认：

```bash
APP_VIDEO_NCAT21_BASE_URL=https://www.ncat21.com
APP_VIDEO_CACHE_MINUTES=5
```

上线验证需要使用已登录账号调用目录接口，并使用有效会员调用播放接口：

```bash
curl -fsS https://api.751152.xyz/api/v1/videos/categories \
  -H "Authorization: Bearer <token>"
curl -fsS "https://api.751152.xyz/api/v1/videos/search?keyword=%E6%B6%88%E5%A4%B1%E7%9A%84%E4%BA%BA&page=1&pageSize=20" \
  -H "Authorization: Bearer <token>"
curl -fsS "https://api.751152.xyz/api/v1/videos/333692/play?episode=41&line=3537870" \
  -H "Authorization: Bearer <member-token>"
```

验收重点：分类和搜索在上游暂时不可用时仍返回可用的空态/默认分类；详情、搜索和播放上游失败返回 `502`；播放响应中的直链只能是 HTTPS 的受信任 ncat21 子域名；没有直链时客户端能打开 `upstreamUrl`。在 ECS 侧观察上游超时、502 比例和请求耗时，确认没有把媒体字节代理进 ECS。

投屏验收按平台分别执行：iOS 选择 AirPlay 设备后等待原生路由回调；macOS 确认 AirPlay 接管后本地视频暂停；Android/Windows 应显示未配置或不支持，并能打开 ncat21 播放页回退。目标站点反自动化、DNS、证书或授权状态异常时，应回滚应用版本或暂时关闭影视入口，而不是绕过站点限制。

## 九、电视直播上线检查

电视直播由 DuckMusic 客户端直接下载 M3U 并连接上游媒体地址，Spring Boot 不再提供直播目录、线路探测、播放令牌或媒体中继。后端发布不需要任何直播环境变量或 Nginx location。

DuckMusic 发布验收应确认当前 M3U 能加载出非空分组和频道，HTTP 与 HTTPS 线路均可交给 `media_kit`，首选线路失败后只依次尝试每条剩余线路一次。至少在本次可用的目标平台打开一个真实频道并记录播放结果。
