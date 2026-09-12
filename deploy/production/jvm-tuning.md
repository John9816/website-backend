# JVM调优说明

## 当前配置（针对2核4GB ECS）

### 堆内存配置
- `-Xms1536m -Xmx2048m`: 初始堆1.5GB，最大堆2GB
- 为操作系统和其他进程预留约2GB内存
- 避免堆动态扩容带来的性能抖动

### Metaspace配置
- `-XX:MetaspaceSize=256m -XX:MaxMetaspaceSize=512m`
- 类元数据空间，Spring Boot应用通常需要200-400MB

### G1 GC配置
- `-XX:+UseG1GC`: 使用G1垃圾回收器，适合堆内存>2GB的应用
- `-XX:MaxGCPauseMillis=200`: 目标最大暂停时间200ms
- `-XX:G1HeapRegionSize=8m`: Region大小8MB
- `-XX:InitiatingHeapOccupancyPercent=45`: 堆占用45%时触发并发标记
- `-XX:G1NewSizePercent=30`: 新生代最小占比30%
- `-XX:G1MaxNewSizePercent=40`: 新生代最大占比40%

### 性能优化参数
- `-XX:+ParallelRefProcEnabled`: 并行处理Reference对象
- `-XX:+UseStringDeduplication`: 字符串去重，节省内存
- `-XX:+ExplicitGCInvokesConcurrent`: System.gc()触发并发GC
- `-XX:+AlwaysPreTouch`: 启动时预分配物理内存，减少运行时缺页

### 调试和诊断
- `-XX:-OmitStackTraceInFastThrow`: 保留完整异常堆栈

## 不同规格ECS建议

### 1核2GB（入门级）
```bash
-Xms768m -Xmx1024m
-XX:MetaspaceSize=128m -XX:MaxMetaspaceSize=256m
-XX:+UseSerialGC  # 小堆使用Serial GC更高效
```

### 2核4GB（当前配置）
参见systemd service文件

### 4核8GB（推荐生产）
```bash
-Xms4g -Xmx6g
-XX:MetaspaceSize=256m -XX:MaxMetaspaceSize=512m
-XX:+UseG1GC
-XX:MaxGCPauseMillis=100
-XX:G1HeapRegionSize=16m
-XX:ParallelGCThreads=4
-XX:ConcGCThreads=2
```

### 8核16GB（高负载）
```bash
-Xms8g -Xmx12g
-XX:MetaspaceSize=512m -XX:MaxMetaspaceSize=1g
-XX:+UseG1GC
-XX:MaxGCPauseMillis=100
-XX:G1HeapRegionSize=32m
-XX:ParallelGCThreads=8
-XX:ConcGCThreads=4
```

## 监控命令

### 查看GC日志（需要添加GC日志参数）
```bash
-Xlog:gc*:file=/opt/website/logs/gc.log:time,uptime,level,tags:filecount=5,filesize=50m
```

### 实时监控
```bash
# JVM内存使用
jstat -gcutil <pid> 1000

# 线程状态
jstack <pid> | grep "java.lang.Thread.State" | sort | uniq -c

# 堆内存分析
jmap -heap <pid>

# 查看G1 GC统计
jcmd <pid> GC.heap_info
```

## 性能基准

### 启动时间
- 冷启动：15-25秒
- 热启动（缓存有效）：10-15秒

### 内存占用
- 空闲状态：1.2-1.5GB
- 中等负载：1.6-1.8GB
- 高负载峰值：<2.0GB

### GC表现（目标）
- Minor GC频率：<10次/小时
- Minor GC耗时：<50ms
- Mixed GC耗时：<100ms
- Full GC：避免发生

## 故障排查

### OOM问题
1. 检查堆转储：`-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/opt/website/logs/`
2. 分析MAT或VisualVM
3. 检查是否有内存泄漏（MusicCache等）

### GC暂停过长
1. 降低`MaxGCPauseMillis`目标值
2. 增加堆内存
3. 检查大对象分配（图片上传等）

### CPU占用过高
1. 检查是否频繁Full GC
2. jstack查看线程状态
3. 检查是否有死循环或热点方法
