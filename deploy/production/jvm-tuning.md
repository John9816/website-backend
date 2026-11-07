# 生产 JVM 资源配置

当前主机为 2 核、1,676 MiB 内存，网站后端与 MariaDB、Node 前端及其他业务共用服务器。配置来源为 [website-api.service](website-api.service)，生产运行时要求 Java 17+。

## 堆和回收

- `-Xms128m -Xmx768m`：降低启动和空闲时的堆下限，保留原有峰值上限。JVM 总内存还包括元空间、线程栈和堆外分配，不能用 Xmx 代替进程内存预算。
- `-XX:+UseG1GC -XX:MaxGCPauseMillis=250`：沿用 G1 和原有暂停目标；该值不是暂停时长保证。
- `-XX:G1PeriodicGCInterval=300000`：持续空闲时约每 5 分钟检查周期回收，让 G1 有机会缩小已提交堆。正常 GC 会重置该间隔，周期回收不保证每 5 分钟一定发生。
- `-XX:G1PeriodicGCSystemLoadThreshold=1.0`：系统一分钟负载超过 1.0 时跳过周期回收。Java 17 默认使用并发周期回收。
- `-XX:MetaspaceSize=96m -XX:MaxMetaspaceSize=256m -Xss512k`：保留原有元空间和线程栈设置。
- `-XX:+ExitOnOutOfMemoryError` 与 systemd 自动重启：沿用现有异常恢复策略，不能替代内存监测。

堆大小只在 systemd 的 ExecStart 中设置。清理环境文件中重复的 `JAVA_TOOL_OPTIONS=-Xms... -Xmx...` 时，只移除堆参数，保留其他用途的 JVM 参数和密钥。

Tomcat 最大线程数 96、最小空闲线程数 8；Hikari 最大连接数 10、最小空闲连接数 2。没有连接池等待或请求饱和的证据时，先保持这些配置。

生产 Spring Security 日志级别为 INFO，避免为每个请求写入 DEBUG 跟踪。临时排查鉴权问题时可以通过 `LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_SECURITY=DEBUG` 开启，结束后恢复 INFO。

## 修改、验证和回滚

1. 记录 `free -m`、`vmstat 1 5`、服务 RSS/cgroup 内存、JVM 已用/已提交堆、GC 次数及 NRestarts，并备份服务文件和环境文件。备份含密钥时目录权限用 700、文件权限用 600。
2. 使用服务器实际 Java 版本验证新 JVM 参数，再用 `systemd-analyze verify` 检查服务文件。
3. 安装配置后执行 `systemctl daemon-reload` 和 `systemctl restart website-api`。检查 8090/health、3000/healthz、前端代理和公开 HTTPS 入口。
4. 用相同的少量并发只读请求检查导航、会员和音乐历史接口，记录错误和延迟；避免调用收费或耗时的外部生成接口。
5. 至少观察一个空闲回收间隔，再比较内存、GC、swap 读写和重启次数。重启后的低内存值不能单独证明长期收益；低负载验证不能证明峰值容量。
6. 启动或健康检查失败时恢复备份的服务文件和环境文件，重新加载 systemd 并重启后端；再次验证健康状态。

安装 JDK 诊断工具的机器可使用 `jcmd <pid> GC.heap_info`、`jstat -gc <pid>`。仅安装 JRE 时可用已授权的 Actuator 指标或 HotSpot perfdata，避免为单次诊断额外启动常驻服务。

## 主机资源

停用 Docker/containerd 前，确认容器、镜像、卷和任务为空，且没有业务服务依赖它们。还需停止和禁用 docker.socket，否则 socket 可能再次启动 Docker。保留软件包，恢复时按修改前记录的启用和运行状态恢复。

已有 swap 使用量不等于正在交换内存。以 vmstat 的 si / so 和可用内存判断压力；不要为了清空数字执行 swapoff。本机已设置 vm.swappiness=10。
