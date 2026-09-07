# 压测与证据归档

这份文档把 JMeter 的接口采样结果和 Redis、RabbitMQ、MySQL 的异步订单最终结果放在同一轮测试中核验。`.jmx` 只描述测试配置，不能单独证明吞吐量、延迟分位数或错误率；公开报告必须同时保留 `.jtl`、HTML 报告和 SQL 核验结果。

## 本轮库存边界测试

### 测试条件

| 项目 | 配置 |
| --- | --- |
| 测试场景 | 优惠券秒杀库存边界 |
| 测试优惠券 | `voucher_id=22` |
| 初始库存 | 100 |
| 测试用户 | 1000 个独立用户，准备记录范围为 1-1004 |
| 请求模型 | 每个用户请求 1 次，共 1000 次 |
| 升压策略 | 30 秒升压窗口 |
| 应用形态 | 本机单实例，HTTP 端口 `8081` |
| 中间件 | MySQL `3306`、Redis `6379`、RabbitMQ `5672` |
| JMeter | 5.6.3，非 GUI 模式 |

测试使用专用 Token 和优惠券数据。Token 文件只存在于本机，未提交到 GitHub；JMeter 脚本通过 `tokens_file` 参数读取它。

### 执行命令

下面的命令将新结果写入未提交的 `artifacts` 目录，不会覆盖仓库中已经归档的报告：

```powershell
$jmeter = 'D:\Java_dp\apache-jmeter-5.6.3\bin\jmeter.bat'
$script = 'docs\load-test\reports\seckill-v22-1000-boundary\seckill-v22-1000.jmx'
$tokens = 'D:\Java_dp\dianping\local\tokens-v22-1000.txt'
$result = 'artifacts\load-test\local-seckill-v22-1000\seckill.jtl'
$report = 'artifacts\load-test\local-seckill-v22-1000\html-report'

New-Item -ItemType Directory -Force (Split-Path $result) | Out-Null
& $jmeter -n -t $script -Jtokens_file=$tokens -l $result -e -o $report
```

`-o` 指向的 HTML 输出目录必须是新目录或空目录。执行前应确认 MySQL、Redis、RabbitMQ 和 Spring Boot 服务均已启动，并记录测试优惠券、初始库存和 Token 有效用户数。

### JTL 结果

| 指标 | 结果 |
| --- | ---: |
| JMeter 总采样数 | 1000 |
| HTTP 200 | 1000 |
| 业务成功 | 100 |
| 预期库存不足 | 900 |
| JMeter 断言失败 | 900 |
| 全部请求平均耗时 | 598.76 ms |
| 全部请求 P95 | 4291.95 ms |
| 全部请求 P99 | 5238.78 ms |
| 全部请求最大耗时 | 5363 ms |
| 全部请求吞吐量 | 34.7379 req/s |
| 成功请求平均耗时 | 4162.52 ms |
| 成功请求 P95 | 5330 ms |
| 库存不足请求平均耗时 | 202.78 ms |

JMeter 断言将业务响应中的库存不足标记为失败，因此 900 次断言失败是预期业务结果，不是 HTTP 连接失败或服务崩溃。统计延迟时同时保留全部请求、成功请求和库存不足请求三种口径。

### 异步结果核验

JMeter 完成后不能立即把接口返回当作最终落库结果。需要等待 RabbitMQ 主队列和未确认消息清空，再执行 [`verify-seckill.sql`](verify-seckill.sql)，并补充 Redis 与 RabbitMQ 状态检查。

| 核验项 | 结果 |
| --- | ---: |
| MySQL 最终库存 | 0 |
| MySQL 最终订单 | 100 |
| 重复用户订单 | 0 |
| 订单处理记录 | `PERSISTED` 100 |
| 消息发布记录 | `CONFIRMED` 100 |
| 无处理记录的订单 | 0 |
| 无订单的处理记录 | 0 |
| 归档死信记录 | 0 |
| Redis 秒杀库存 | 0 |
| Redis 一人一单集合基数 | 100 |
| RabbitMQ 主队列消息 | 0 |
| RabbitMQ 未确认消息 | 0 |
| RabbitMQ 死信队列消息 | 0 |

核心 SQL 参数为 `voucher_id=22`、测试用户范围 `1-1004`。完整 SQL 见 [`verify-seckill.sql`](verify-seckill.sql)，本轮实际核验输出见 [`sql-verification.txt`](reports/seckill-v22-1000-boundary/sql-verification.txt)。核心查询如下：

```sql
SELECT voucher_id, stock
FROM tb_seckill_voucher
WHERE voucher_id = 22;

SELECT COUNT(*) AS final_orders
FROM tb_voucher_order
WHERE voucher_id = 22
  AND user_id BETWEEN 1 AND 1004;

SELECT user_id, voucher_id, COUNT(*) AS duplicate_count
FROM tb_voucher_order
WHERE voucher_id = 22
  AND user_id BETWEEN 1 AND 1004
GROUP BY user_id, voucher_id
HAVING COUNT(*) > 1;
```

### 公开证据

- [JMeter 脚本](reports/seckill-v22-1000-boundary/seckill-v22-1000.jmx)
- [原始 JTL](reports/seckill-v22-1000-boundary/seckill.jtl)
- [HTML 报告入口](reports/seckill-v22-1000-boundary/html-report/index.html)
- [运行汇总 JSON](reports/seckill-v22-1000-boundary/run-summary.json)
- [测试准备记录](reports/seckill-v22-1000-boundary/prepare.txt)
- [SQL 核验结果](reports/seckill-v22-1000-boundary/sql-verification.txt)

本轮指标只适用于记录中的本机单实例和测试数据，不能直接外推为生产容量或线上 SLA。简历或面试使用这些数字时，应同时说明升压时间、请求数量、JTL 口径和异步最终核验结果。

## 其他测试脚本

- [`seckill-100-users.jmx`](seckill-100-users.jmx)：基础秒杀测试配置。
- [`shop-cache-30-users.jmx`](shop-cache-30-users.jmx)：商户缓存访问测试配置，默认 30 个线程、10 秒升压、持续 60 秒；测量前应先单独预热商户详情。

压测使用的真实 Token、数据库密码和本机运行日志不属于公开证据，不应提交到 Git。临时结果统一放在被忽略的 `artifacts/` 目录中，只有完成脱敏和核验的材料才进入 `docs/load-test/reports/`。
