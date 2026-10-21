# 自动化测试与压测

本目录是苍穹外卖（daynight）新增功能的测试资产：**缓存三件套** + **秒杀并发** + **全局唯一ID**。

```
test/
├── jmeter/
│   ├── 01-baseline.jmx         基线（Redis读，无鉴权无DB）
│   ├── 02-dish-list.jmx        菜品列表（缓存效果）
│   ├── 03-seckill.jmx          秒杀并发（验证不超卖）
│   └── 04-cache-breakdown.jmx  缓存击穿（删key后同时打）
├── reset-testdata.ps1          重置压测数据
├── verify.ps1                  压测结果校验（PASS/FAIL）
├── run-jmeter.ps1              一键：重置 → 压测 → 校验 → 报告
└── README.md
```

---

## 一、自动化测试（JUnit 5）

### 运行

```bash
# 在项目根目录
mvn test

# 只跑某个类
mvn test -Dtest=SeckillServiceTest

# 看报告
# sky-server/target/surefire-reports/
```

### 测试类

| 类 | 覆盖点 |
|---|---|
| `RedisIdWorkerTest` | 单线程生成 1 万个 ID 不重复且递增；8 线程并发 2000 个不重复 |
| `DishCacheServiceTest` | 回源写缓存、二次命中、空值缓存（防穿透）、随机 TTL（防雪崩）、清缓存、多分类 TTL 散开 |
| `SeckillServiceTest` | 30 并发抢 10 库存不超卖、一人一单、售罄、活动不存在、跳过支付金额正确 |
| `SeckillApiTest` | MockMvc 走完整 MVC 链路：无 token 401、带 token 查列表、抢购、轮询结果 |

**当前结果：17 个用例全部通过。**

### 数据隔离

测试**不碰**开发库，用的是：

| | 开发环境 | 测试环境 |
|---|---|---|
| 数据库 | `daynight` | `daynight_test` |
| Redis | db 1 | db 15 |

配置在 `sky-server/src/test/resources/application-test.yml`，通过 `@ActiveProfiles("test")` 激活。

> 首次使用需要建测试库：
> ```bash
> mysql -uroot -p -e "create database daynight_test charset utf8mb4"
> mysqldump -uroot -p --no-data daynight | mysql -uroot -p daynight_test
> mysql -uroot -p -e "insert into daynight_test.category select * from daynight.category;
>                      insert into daynight_test.dish select * from daynight.dish;
>                      insert into daynight_test.dish_flavor select * from daynight.dish_flavor;
>                      insert into daynight_test.seckill_dish select * from daynight.seckill_dish;"
> ```

---

## 二、JMeter 压测

### 前置条件

1. **后端必须已启动**（IDEA 里跑 `SkyApplication`，默认 8080）
2. MySQL、Redis 在跑
3. JMeter 路径：`D:\develop\apache-jmeter-5.6.3`
4. `JAVA_HOME` 已设置（脚本会自动设成 `D:\develop`）

### 一键运行（推荐）

```powershell
cd C:\Users\86180\Desktop\daynight\test

# 秒杀：30 并发抢 10 库存，跑完自动校验
.\run-jmeter.ps1 -Plan 03-seckill

# 100 并发抢 10 库存
.\run-jmeter.ps1 -Plan 03-seckill -Threads 100

# 缓存击穿：60 并发同时打同一分类
.\run-jmeter.ps1 -Plan 04-cache-breakdown

# 基线 / 菜品列表
.\run-jmeter.ps1 -Plan 01-baseline
.\run-jmeter.ps1 -Plan 02-dish-list

# 指定端口（比如临时起在 8081 上验证）
.\run-jmeter.ps1 -Plan 03-seckill -Port 8081
```

脚本会依次做：**重置数据 → 非GUI压测 → 生成HTML报告 → 校验结果**，最后打印报告路径。

### 单独使用脚本

```powershell
# 只重置数据
.\reset-testdata.ps1
.\reset-testdata.ps1 -SeckillId 2 -Stock 50
.\reset-testdata.ps1 -ClearDishCache     # 同时清菜品缓存，制造"冷缓存"

# 只校验（跑完JMeter手动核对时用）
.\verify.ps1 -SeckillId 1 -ExpectedStock 0 -ExpectedOrders 10
# 退出码 0 = PASS，1 = FAIL
```

### 直接用 JMeter 命令行

```powershell
$env:JAVA_HOME = 'D:\develop'
D:\develop\apache-jmeter-5.6.3\bin\jmeter.bat -n `
  -t test\jmeter\03-seckill.jmx `
  -l test\reports\result.jtl `
  -e -o test\reports\html `
  -Jport=8080 -Jthreads=30 -Jloops=1
```

参数（都可以用 `-J` 从命令行传）：

| 参数 | 默认 | 说明 |
|---|---|---|
| `port` | 8080 | 后端端口 |
| `threads` | 按计划 | 并发线程数 |
| `loops` | 按计划 | 每线程循环次数 |
| `seckillId` | 1 | 秒杀活动id |
| `categoryId` | 11 | 菜品分类id |

### 四个计划干什么

| 计划 | 默认并发 | 目的 | 判定标准 |
|---|---|---|---|
| `01-baseline` | 30×20 | 最轻接口的 TPS 基线 | 错误率 0% |
| `02-dish-list` | 50×20 | 缓存读的性能 | 错误率 0%，返回菜品数量正确 |
| `03-seckill` | 30×1 | **不超卖** | `verify.ps1` 五项全过 |
| `04-cache-breakdown` | 60×1 | **互斥锁防击穿** | 服务端日志"回源写缓存"只出现 **1 次** |

### 为什么秒杀要自己签 JWT

秒杀接口要求 `authentication` 头，并且**一人一单**。如果 30 个线程共用同一个 token，只有 1 个能成功，测试就失去意义了。

所以计划里用 JSR223 预处理器（Groovy）**给每个线程签一个不同 userId 的 token**：

```groovy
long userId = 9000L + ctx.getThreadNum()
// 用 HS256 + application.yml 里的 user-secret-key 签名
```

不需要预生成 token 文件，改线程数也不用重新准备。

---

## 三、结果怎么读

### 秒杀的正确性判定

JMeter 的 `Err: 0 (0.00%)` **不代表没超卖**——售罄/重复下单都是业务拒绝，我特意让它们不算失败。真正的判定在 `verify.ps1`：

```
1. 数据库库存 : 0（期望 0）
2. 秒杀订单数 : 10（期望 10）
3. 重复订单号 : 0（期望 0）
4. Redis库存  : 0（期望 0）
5. 已购用户数 : 10（期望 10）
结果：PASS
```

只要出现 `库存 < 0` 或 `订单数 > 初始库存`，就是超卖。

### 关于延迟数字

单机压测（JMeter 和服务器同一台机器）时，30 线程的**首轮请求**会包含建连开销（Windows 下约 100~200ms/请求），所以 `03-seckill` 这种"每人只请求一次"的场景，平均 RT 看着偏高（200ms 左右）是正常的。用 curl 直接测同一接口约 9ms。

要看真实吞吐，用 `01/02` 这类多循环的计划——连接复用后：

```
01-baseline : 600 请求, 195 TPS, 平均 51ms
02-dish-list: 1000 请求, 206 TPS, 平均 107ms
```

### 击穿验证怎么确认

跑完 `04-cache-breakdown` 后，在后端日志里搜：

```powershell
Select-String -Path <后端日志> -Pattern '回源写缓存.*dish_'
```

60 个线程同时打同一个分类，**只应该有 1 条**——说明互斥锁把并发回源收敛成了 1 次数据库查询，其余请求都在等锁或命中缓存。

---

## 四、接口限流与幂等（新增功能）

### 4.1 接口限流（Redis + Lua 滑动窗口）

**用法**：在 Controller 方法上加注解即可，由 `RateLimitAspect` 统一拦截。

```java
// 每个用户每秒最多5次
@RateLimit(limit = 5, windowSeconds = 1, type = RateLimitType.USER)
public Result<String> seckill(@PathVariable Long seckillId) { ... }

// 全局限流，保护下游
@RateLimit(limit = 100, windowSeconds = 1, type = RateLimitType.GLOBAL)
```

**原理**：Lua 脚本内一次性完成"清理过期记录 → 统计 → 判断 → 记录"，Redis 执行脚本是原子的，所以并发下不会出现"都判断通过然后一起放行"。

```
ZREMRANGEBYSCORE key 0 now-window   -- 把窗口外的请求记录删掉（"滑动"）
ZCARD key                            -- 统计窗口内请求数
if count >= limit then 返回等待毫秒数
ZADD key now member                  -- 记录本次请求
PEXPIRE key window                   -- 冷用户自动清理
```

**实测**：同一用户并发 100 次请求 → **放行 5 次、拦截 95 次**，与阈值完全一致。

### 4.2 接口幂等（Token 机制）

**用法**：

```java
// 1. 下单接口加注解
@Idempotent(message = "订单正在处理中，请勿重复提交")
public Result<OrderSubmitVO> submit(@RequestBody OrdersSubmitDTO dto) { ... }
```

**调用流程**：

```
① GET /user/idempotent/token                     → 拿到一次性token
② POST /user/order/submit
   Header: idempotent-token: <token>            → 正常下单
③ 用同一个token再提交一次                        → "订单正在处理中，请勿重复提交"
```

**原理**：Redis 的 `DEL` 本身是原子命令，同一 token 并发提交时只有一个线程能删成功（返回 true），其余拿到 false 判定为重复提交。token 与 userId 绑定，不能跨用户使用。

**细节**：业务抛异常时会把 token 还回去（`IdempotentAspect` 的 catch 分支），这样"购物车为空"这类可恢复的错误，前端可以用同一个 token 重试，不用重新取。

### 4.3 手工验证命令

```bash
TOKEN=<登录拿到的token>

# --- 限流：连续打6次，第6次被拦 ---
for i in 1 2 3 4 5 6; do
  curl -s -X POST http://localhost:8080/user/seckill/1 -H "authentication: $TOKEN"
done
# 预期：前5次返回业务结果，第6次 {"code":0,"msg":"请求过于频繁，请 0.6 秒后重试"}

# --- 幂等：取token → 提交两次 ---
IDEM=$(curl -s http://localhost:8080/user/idempotent/token -H "authentication: $TOKEN" | jq -r .data)

curl -s -X POST http://localhost:8080/user/order/submit \
  -H "authentication: $TOKEN" -H "idempotent-token: $IDEM" \
  -H "Content-Type: application/json" \
  -d '{"addressBookId":1,"payMethod":1,"amount":9.90,"deliveryStatus":1,"tablewareNumber":1,"tablewareStatus":1,"packAmount":0}'
# 预期：code=1

curl -s -X POST http://localhost:8080/user/order/submit \
  -H "authentication: $TOKEN" -H "idempotent-token: $IDEM" ... 同上
# 预期：{"code":0,"msg":"订单正在处理中，请勿重复提交"}
```

### 4.4 相关自动化用例

| 测试类 | 用例 |
|---|---|
| `RateLimitTest` | 同一用户1秒内超过5次被限流；不同用户额度互不影响 |
| `IdempotentTest` | 同一token只能提交一次；不带token被拒；业务失败后token可复用 |

---

## 五、常见问题

| 现象 | 原因 | 解决 |
|---|---|---|
| `Connection refused` | 后端没启动，或 `-Jport` 传错 | 先确认 8080 在监听 |
| 全部返回"已售罄" | 没重置数据，库存是 0 | 跑 `reset-testdata.ps1` |
| 全部返回"活动不存在" | Redis 里库存 key 被删了，但应用没重启 | 重置脚本会重新写入库存 key |
| 脚本报"参数 Db 与 Debug 别名冲突" | 旧版本脚本问题 | 已改名为 `-DatabaseName` |
| 脚本中文乱码/语法错误 | .ps1 存成了无 BOM 的 UTF-8，PS 5.1 按 GBK 读 | 用 UTF-8 **带 BOM** 保存 |
| JMeter 报 `JAVA_HOME not set` | 环境变量没配 | 脚本里已自动设置 |
