# 🔧 修复API认证问题

## 问题描述

当前所有API返回401 Unauthorized，包括应该公开的导航API、Actuator监控等。

**根本原因**: `SecurityConfig.java:82` 配置了 `anyRequest().denyAll()`，导致所有未明确配置的endpoint都被拒绝。

## 影响范围

以下API当前无法访问：
- `/api/nav/**` - 导航API（应该公开）
- `/actuator/**` - Spring Boot监控（应该受限访问）
- `/api/v1/image/**` - 图片管理（部分应该认证）
- `/api/v1/kb/**` - 知识库（部分应该认证）

## 解决方案

### 方案1: 开放导航和监控API（推荐）

修改 `SecurityConfig.java`，在第82行之前添加：

```java
// 在 .antMatchers("/api/user/**").authenticated() 后添加：
.antMatchers(HttpMethod.GET, "/api/nav/**").permitAll()  // 导航API公开访问
.antMatchers("/actuator/health", "/actuator/info").permitAll()  // 健康检查公开
.antMatchers("/actuator/**").hasRole(ROLE_ADMIN)  // 其他监控需要管理员权限
.antMatchers(HttpMethod.GET, "/api/v1/image/list").authenticated()  // 图片列表需认证
.antMatchers(HttpMethod.GET, "/api/v1/kb/**").authenticated()  // 知识库需认证
.anyRequest().denyAll()  // 其他全部拒绝
```

完整修改后的配置：

```java
.authorizeRequests()
    .antMatchers(HttpMethod.OPTIONS, "/**").permitAll()
    .antMatchers(HttpMethod.GET, "/health").permitAll()
    .antMatchers(HttpMethod.GET, "/updates/**").permitAll()
    .antMatchers("/api/auth/**").permitAll()
    .antMatchers(HttpMethod.GET, "/api/v1/image/file/**").permitAll()
    .antMatchers(HttpMethod.GET, "/api/v1/user/avatar/**").permitAll()
    .antMatchers(HttpMethod.GET, "/api/v1/kb/assets/**").permitAll()
    .antMatchers(HttpMethod.GET, "/api/v1/content/assets/**").permitAll()
    .antMatchers(HttpMethod.GET, "/api/public/**").permitAll()
    
    // 新增：公开API
    .antMatchers(HttpMethod.GET, "/api/nav/**").permitAll()
    .antMatchers("/actuator/health", "/actuator/info").permitAll()
    
    // 认证API
    .antMatchers("/api/v1/music/**").authenticated()
    .antMatchers("/api/v1/videos/**").authenticated()
    .antMatchers("/api/v1/books/**").authenticated()
    .antMatchers(HttpMethod.GET, "/api/v1/image/list").authenticated()
    .antMatchers(HttpMethod.GET, "/api/v1/kb/**").authenticated()
    
    // 管理员API
    .antMatchers("/api/admin/**").hasRole(ROLE_ADMIN)
    .antMatchers("/actuator/**").hasRole(ROLE_ADMIN)
    
    // 用户API
    .antMatchers("/api/user/**").authenticated()
    
    // 其他全部拒绝
    .anyRequest().denyAll()
```

### 方案2: 临时开放所有API（仅用于测试）

如果只是为了测试性能优化效果，可以临时改为：

```java
.anyRequest().permitAll()  // 临时开放，测试后改回denyAll
```

**警告**: 方案2不安全，仅用于测试环境，测试完毕必须改回去。

## 快速部署

### 1. 修改代码
```bash
# 编辑SecurityConfig.java
vim src/main/java/com/example/website/config/SecurityConfig.java

# 按方案1修改第67-82行
```

### 2. 编译并部署
```bash
# 本地编译
./gradlew clean bootJar

# 部署到ECS
scp build/libs/website-backend-1.0.0.jar root@8.156.95.93:/opt/website/backend.jar

# 重启服务
ssh root@8.156.95.93 'systemctl restart website-api'
```

### 3. 验证
```bash
# 测试导航API（应该返回数据）
curl https://api.751152.xyz/api/nav/categories

# 测试健康检查（应该返回{"status":"UP"}）
curl https://api.751152.xyz/actuator/health

# 测试缓存监控（需要管理员权限或token）
curl https://api.751152.xyz/actuator/metrics/cache.gets
```

## 安全建议

### 生产环境配置原则

1. **最小权限原则**
   - 只开放必须公开的endpoint
   - 其他全部要求认证

2. **分级访问控制**
   - Public: 导航、健康检查、静态资源
   - Authenticated: 音乐、图片、知识库
   - Admin: 管理接口、监控指标

3. **监控保护**
   - `/actuator/health` 可公开（负载均衡需要）
   - `/actuator/info` 可公开（版本信息）
   - 其他监控指标仅管理员可访问

4. **API密钥或JWT**
   - 考虑为公开API添加rate limiting
   - 重要操作使用JWT认证
   - 管理接口必须强认证

## 测试验证清单

修改后需要验证：

- [ ] 导航API可以公开访问
- [ ] 健康检查可以公开访问
- [ ] 音乐API需要登录
- [ ] 图片上传需要登录
- [ ] 管理接口需要管理员权限
- [ ] 监控指标需要管理员权限
- [ ] 缓存命中率可以查看

## 相关文件

- `SecurityConfig.java` - Spring Security配置
- `JwtAuthFilter.java` - JWT认证过滤器
- `AppProperties.java` - CORS等配置

## 参考

- [Spring Security文档](https://docs.spring.io/spring-security/reference/)
- [JWT最佳实践](https://tools.ietf.org/html/rfc8725)
