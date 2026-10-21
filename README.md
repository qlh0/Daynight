# 朝夕食驿（Daynight）—— 餐饮外卖平台

基于 **Spring Boot** 的外卖系统后端，包含**商家管理端**与**用户端小程序**两套接口，实现了员工/菜品/套餐/购物车/订单等核心业务，并在此基础上做了 Redis 缓存优化、秒杀并发、接口限流与幂等控制。

> 项目类型：个人学习/实战项目　|　后端端口：`8080`　|　接口文档：`/doc.html`

---

## 目录

- [技术栈](#技术栈)
- [功能清单](#功能清单)
- [技术亮点](#技术亮点)
- [项目结构](#项目结构)
- [快速开始](#快速开始)
- [接口文档](#接口文档)
- [测试与压测](#测试与压测)
- [部署](#部署)
- [已知限制与后续计划](#已知限制与后续计划)

---

## 技术栈

| 分类 | 选型 |
|---|---|
| 基础框架 | Spring Boot 2.7.3、Spring MVC |
| 持久层 | MyBatis 2.2.0、Druid 连接池 1.2.1、PageHelper 1.3.0 |
| 数据库 | MySQL 8.0 |
| 缓存 | Redis（Lettuce 客户端） |
| 认证 | JWT（jjwt 0.9.1）+ 自定义拦截器 |
| 接口文档 | Knife4j 3.0.2（Swagger 增强） |
| 实时通信 | WebSocket |
| 文件存储 | 阿里云 OSS |
| 报表导出 | Apache POI 3.16 |
| 其他 | Lombok、AOP、Spring Task、微信小程序登录 |

---

## 功能清单

### 商家管理端 `/admin/**`

| 模块 | 功能 |
|---|---|
| 员工管理 | 员工登录（JWT）、新增员工、分页查询、启用/禁用账号、编辑信息 |
| 分类管理 | 新增、分页查询、修改、删除、启用/禁用、按类型查询 |
| 菜品管理 | 新增（含口味）、分页查询、批量删除、修改、按分类查询、图片上传（OSS） |
| 套餐管理 | 新增、分页查询、批量删除、修改、起售/停售（校验套餐内菜品状态） |
| 店铺状态 | 营业/打烊切换（存 Redis） |
| 数据统计 | 工作台今日数据、订单管理数据、菜品/套餐总览、营业额统计、运营数据导出（Excel） |
| 来单提醒 | WebSocket 实时推送新订单 |

### 用户端小程序 `/user/**`

| 模块 | 功能 |
|---|---|
| 登录 | 微信登录（本地开发支持兜底模式，无需真实 appid） |
| 浏览 | 分类列表、按分类查菜品（**Redis 缓存**）、套餐列表及套餐内菜品 |
| 购物车 | 添加、查看、清空 |
| 地址簿 | 新增、查询、修改、删除、设置默认地址 |
| 下单 | 提交订单（**幂等 token** 防重复提交） |
| 秒杀 | 秒杀列表、参与秒杀（**Lua + 乐观锁 + 阻塞队列**）、异步结果查询 |
| 流量防护 | 秒杀接口 **Redis + Lua 滑动窗口限流** |

---

## 技术亮点

### 1. 认证与用户上下文

JWT + 自定义拦截器 `JwtTokenUserInterceptor` / `JwtTokenAdminInterceptor`，校验通过后把用户 id 放入 `ThreadLocal`（`BaseContext`），业务层无需再关心 token。

```
请求 → 拦截器校验 token → BaseContext.setCurrentId(userId) → Controller/Service 取用
```

### 2. Redis 缓存三件套

针对 `GET /user/dish/list?categoryId=` 这一个高频接口，分别处理了三个经典问题：

| 问题 | 方案 | 实现 |
|---|---|---|
| **缓存穿透** | 缓存空值 | 查不到数据时写入 `"NULL"` 标记，TTL 2 分钟（比正常数据短） |
| **缓存雪崩** | 随机 TTL | TTL = 30 分钟 + `random(0,10)` 分钟，避免同时失效 |
| **缓存击穿** | 互斥锁 | `setIfAbsent` 抢锁 + 双重检查 + UUID 校验释放，抢不到锁重试 3 次后降级直查 |

管理端增删改菜品时会同步清理缓存（按 `categoryId` 精确清理，批量操作用 `SCAN` 而非 `KEYS`）。

> 实测：**60 并发同时请求同一热点 key，数据库只回源 1 次**。

### 3. 秒杀链路（Redis + Lua + 乐观锁 + 阻塞队列）

```
用户请求
  ↓
① Redis + Lua 原子判断：库存是否充足、是否重复购买、扣减库存、记录用户
  ↓
② Redis 全局唯一 ID 生成订单号
  ↓
③ 消息入阻塞队列，立即返回"排队中"
  ↓
④ 后台消费者线程取任务（4 个线程）
  ↓
⑤ 数据库乐观锁扣库存：UPDATE ... WHERE id=? AND stock>0
  ↓
⑥ 写订单 + 订单明细（跳过支付，直接置为已支付/待接单）
  ↓
⑦ 结果写回 Redis，前端轮询查询
```

关键设计：

- **Lua 脚本保证原子性**：判断和扣减不能分开写，否则并发下会超卖
- **数据库乐观锁兜底**：`where stock > 0` 靠 MySQL 行锁保证，影响行数为 0 即库存不足
- **阻塞队列解耦**：接口只做 Redis 操作 + 入队（毫秒级），写库由后台线程异步完成
- **补偿机制**：队列满或建单失败时，回滚 Redis 中的库存和购买记录

> 实测：**100 并发抢 10 件库存，库存精确归零、订单恰好 10 条、订单号无重复、零超卖**。

### 4. 接口限流（Redis + Lua 滑动窗口）

以注解方式接入，支持按用户 / IP / 全局三种维度：

```java
@RateLimit(limit = 5, windowSeconds = 1, type = RateLimitType.USER)
public Result<String> seckill(@PathVariable Long seckillId) { ... }
```

Lua 脚本内一次完成"清理过期记录 → 统计 → 判断 → 记录"：

```lua
ZREMRANGEBYSCORE key 0 now-window
if redis.call('zcard', key) >= limit then return 等待毫秒数 end
redis.call('zadd', key, now, member)
```

之所以不用固定窗口（`INCR + EXPIRE`），是因为固定窗口在窗口交界处可能瞬间放过 2 倍流量。

> 实测：**同一用户并发 100 次请求，精确放行 5 次、拦截 95 次**。

### 5. 接口幂等（一次性 Token）

```
① GET /user/idempotent/token  → 获取一次性 token（Redis 存 5 分钟，绑定 userId）
② POST /user/order/submit     → 请求头带 idempotent-token
③ 同一 token 再次提交         → 被拒绝："订单正在处理中，请勿重复提交"
```

核心利用 **Redis `DEL` 的原子性**：删掉了才是第一次提交，并发下只有一个线程能删成功。

业务异常时会**把 token 还回去**，让"购物车为空"这类可恢复错误可以用同一个 token 重试。

> 实测：**3 次提交只产生 1 笔订单**。

### 6. 全局唯一 ID

`RedisIdWorker` 生成订单号：**32 位时间戳（秒）+ 32 位 Redis 自增值**，按天分 key。

相比 `System.currentTimeMillis()`，解决了同一毫秒内并发重复的问题；相比 UUID，保持了整体递增（对 MySQL 索引友好）。

### 7. 其他工程实践

| 能力 | 实现 |
|---|---|
| 公共字段自动填充 | `@AutoFill` 注解 + AOP 切面，统一填充 `create_time/update_time/create_user/update_user` |
| 超时订单处理 | Spring Task 定时扫描待付款订单，超时自动取消 |
| 统一异常处理 | `@RestControllerAdvice` 捕获业务异常，返回统一 `Result` 结构 |
| 统一返回/分页 | `Result<T>`、`PageResult<T>` |
| 统一消息转换 | 自定义 `JacksonObjectMapper` 处理 `LocalDateTime` 序列化 |
| 前后端时间格式 | `@JsonFormat` 统一 `yyyy-MM-dd HH:mm:ss` |

---

## 项目结构

```
daynight/
├── pom.xml                     Maven 父工程（统一依赖版本）
├── sql/
│   └── init.sql                数据库初始化脚本（建表 + 种子数据）
├── sky-common/                 公共模块
│   └── src/main/java/com/sky/
│       ├── constant/           常量（消息、状态、Redis key）
│       ├── context/            BaseContext（ThreadLocal 用户上下文）
│       ├── enumeration/        枚举（操作类型、限流维度）
│       ├── exception/          业务异常体系
│       ├── properties/         配置属性（JWT、OSS、微信）
│       ├── result/             统一返回结果
│       └── utils/              工具类（JWT、HTTP、OSS、微信支付）
├── sky-pojo/                   实体模块
│   └── src/main/java/com/sky/
│       ├── dto/                入参对象
│       ├── entity/             数据库实体
│       └── vo/                 返回视图对象
├── sky-server/                 业务模块
│   ├── src/main/java/com/sky/
│   │   ├── annotation/         自定义注解（AutoFill、RateLimit、Idempotent）
│   │   ├── aspect/             切面（字段填充、限流、幂等）
│   │   ├── component/          RedisIdWorker
│   │   ├── config/             WebMvc、Redis、OSS 配置
│   │   ├── controller/
│   │   │   ├── admin/          管理端接口
│   │   │   └── user/           用户端接口
│   │   ├── handler/            全局异常处理器
│   │   ├── interceptor/        JWT 拦截器
│   │   ├── mapper/             MyBatis Mapper
│   │   ├── service/            业务逻辑
│   │   ├── task/               定时任务
│   │   └── websocket/          WebSocket 服务
│   ├── src/main/resources/
│   │   ├── application.yml     主配置（端口、数据源、MyBatis、JWT）
│   │   ├── application-dev.yml 开发环境配置
│   │   ├── lua/                Lua 脚本（秒杀预减库存、限流）
│   │   └── mapper/             MyBatis XML
│   └── src/test/               自动化测试
└── test/                       JMeter 压测与校验脚本
    ├── jmeter/                 4 个压测计划
    ├── reset-testdata.ps1      重置压测数据
    ├── verify.ps1              压测结果断言
    └── run-jmeter.ps1          一键压测
```

---

## 快速开始

### 1. 环境要求

| 软件 | 版本 |
|---|---|
| JDK | 8 及以上（实测 JDK 17 可正常运行） |
| Maven | 3.6+ |
| MySQL | 8.0+ |
| Redis | 6.0+ |

### 2. 初始化数据库

```bash
mysql -uroot -p < sql/init.sql
```

脚本会创建 `daynight` 库、12 张表，并写入基础数据（10 个分类、24 个菜品、2 个员工账号、3 个秒杀商品）。

> ⚠️ 脚本包含 `DROP TABLE`，会在已有库上重建表结构，请先备份。

### 3. 修改配置

编辑 `sky-server/src/main/resources/application-dev.yml`：

```yaml
sky:
  datasource:
    host: localhost
    port: 3306
    database: daynight
    username: root
    password: 你的数据库密码      # ← 改这里
```

OSS 密钥通过环境变量注入（未配置也不影响启动，只是图片上传不可用）：

```bash
# Windows PowerShell
$env:ALIYUN_ACCESS_KEY_ID="你的AccessKeyId"
$env:ALIYUN_ACCESS_KEY_SECRET="你的AccessKeySecret"
```

确认 Redis 已启动（默认 `127.0.0.1:6379`，使用 `db 1`）。

### 4. 启动后端

```bash
mvn clean package -DskipTests
java -jar sky-server/target/sky-server-1.0-SNAPSHOT.jar
```

或在 IDEA 中直接运行 `com.sky.SkyApplication`。

启动成功后：

- 服务地址：http://localhost:8080
- 接口文档：http://localhost:8080/doc.html

### 5. 管理端前端（可选）

管理端是独立的 Vue 静态站点，用 Nginx 托管并反向代理到后端：

```nginx
server {
    listen 80;
    server_name localhost;

    location / {
        root   html/sky;          # 管理端打包产物
        index  index.html;
    }

    # 管理端接口
    location /api/ {
        proxy_pass http://localhost:8080/admin/;
    }

    # 用户端接口
    location /user/ {
        proxy_pass http://localhost:8080/user/;
    }

    # WebSocket
    location /ws/ {
        proxy_pass http://localhost:8080/ws/;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
    }
}
```

访问 http://localhost ，默认账号 **admin / 123456**。

### 6. 用户端小程序（可选）

1. 用微信开发者工具打开小程序工程
2. 把请求地址 `baseUrl` 改成 `http://localhost:8080`
3. 「详情 → 本地设置」勾选 **不校验合法域名**
4. 编译运行

> 本地开发时 `application-dev.yml` 中的 `sky.wechat.dev-mode: true` 会让微信登录在换取 openid 失败时自动兜底，方便没有 appid 权限的本地调试。**部署到生产请改为 `false`。**

---

## 接口文档

启动后访问 **http://localhost:8080/doc.html**（Knife4j），分「管理端接口」与「用户端接口」两个分组，可直接在线调试。

认证方式：登录接口返回 token，后续请求放入请求头：

| 端 | 请求头 |
|---|---|
| 管理端 | `token: <JWT>` |
| 用户端 | `authentication: <JWT>` |

---

## 测试与压测

### 自动化测试（JUnit 5 + MockMvc）

```bash
mvn test
```

| 测试类 | 覆盖内容 |
|---|---|
| `RedisIdWorkerTest` | 1 万个 ID 不重复且递增；8 线程并发 2000 个不重复 |
| `DishCacheServiceTest` | 回源写缓存、命中、空值缓存、随机 TTL、清缓存 |
| `SeckillServiceTest` | 30 并发抢 10 库存不超卖、一人一单、售罄、跳过支付 |
| `SeckillApiTest` | 完整 MVC 链路：401 拦截、列表、抢购、结果查询 |
| `RateLimitTest` | 同用户超阈值被限流、不同用户额度隔离 |
| `IdempotentTest` | 同 token 只能提交一次、缺 token 被拒、失败后可重试 |

**共 22 个用例，全部通过。** 测试使用独立数据库 `daynight_test` + Redis `db 15`，不污染开发数据。

### JMeter 压测

```powershell
cd test
.\run-jmeter.ps1 -Plan 03-seckill              # 30并发抢10库存
.\run-jmeter.ps1 -Plan 03-seckill -Threads 100 # 100并发
.\run-jmeter.ps1 -Plan 04-cache-breakdown      # 缓存击穿验证
```

脚本自动完成：**重置数据 → 非GUI压测 → 生成HTML报告 → 数据库断言**。

### 压测结果

| 场景 | 并发 | 结果 |
|---|---|---|
| 秒杀不超卖 | 100 线程抢 10 件 | 库存归零、订单 10 条、单号无重复，**零超卖** |
| 缓存击穿防护 | 60 线程同时请求同一热点 key | 数据库**仅回源 1 次**（98%+ 请求被拦截） |
| 接口限流 | 同一用户并发 100 次 | **放行 5 次、拦截 95 次**，与阈值一致 |
| 接口幂等 | 同一 token 提交 3 次 | 仅生成 **1 笔订单** |
| 缓存接口吞吐 | 50 线程 × 50 循环 | 2500 请求，**346 TPS，平均响应 90ms** |
| 基线吞吐（对照） | 50 线程 × 50 循环 | 2500 请求，469 TPS，平均响应 47ms |

> 压测环境为单机（JMeter、应用、MySQL、Redis 同机），绝对吞吐受压力机限制，重点看**相对指标**与**正确性断言**。

---

## 部署

```bash
# 1. 打包
mvn clean package -DskipTests

# 2. 上传并运行
nohup java -jar sky-server-1.0-SNAPSHOT.jar --spring.profiles.active=prod > app.log 2>&1 &
```

生产环境建议：

- 用 Nginx 托管前端静态资源并反向代理后端（同时配置 HTTPS）
- 数据库密码、OSS 密钥、微信密钥全部改用环境变量注入
- `sky.wechat.dev-mode` 置为 `false`
- Redis 开启密码认证，不要暴露公网端口

---

## 已知限制与后续计划

当前实现的边界（如实说明）：

- **秒杀队列是内存队列**（`LinkedBlockingQueue`），应用重启会丢失排队中的任务。生产环境应替换为 RabbitMQ / RocketMQ，或改用 Redis List 持久化。
- **互斥锁是单机锁**（Redis `setIfAbsent`），多实例部署时需升级为 Redisson 分布式锁。
- **支付未接入**：秒杀订单直接跳过支付；普通订单的微信支付仅保留工具类，未打通。

后续计划：

- [ ] 补齐管理端订单管理（接单/拒单/派送/完成/搜索）
- [ ] 用户端历史订单、订单详情、催单
- [ ] 秒杀结果由轮询改为 WebSocket 推送
- [ ] 消息队列替换内存队列，保证消息不丢
- [ ] 接入 Prometheus + Grafana 监控缓存命中率与秒杀成功率

---

## License

本项目仅用于学习与技术演示。
