# 压测与证据归档

这份目录描述如何把 JMeter 的接口采样结果和异步订单最终结果放在一起核验。`.jmx` 只是测试配置，不能单独证明吞吐量、P95、P99 或错误率；每轮测试必须保存原始 `.jtl` 和 HTML 报告。

## 测试脚本

秒杀脚本默认使用 100 个线程、10 秒升压窗口，每个线程执行 1 次请求。`tokens_file` 应指向本机准备好的、脱敏的有效登录 Token 文件，不要把真实 Token 提交到 Git。商户缓存脚本默认使用 30 个线程、10 秒升压窗口，持续 60 秒，测量前应先单独预热一次商户详情。

秒杀脚本：

```powershell
$jmeter = 'D:\Java_dp\apache-jmeter-5.6.3\bin\jmeter.bat'
$script = 'docs\load-test\seckill-100-users.jmx'
$result = 'artifacts\load-test\seckill-100-users.jtl'
$report = 'artifacts\load-test\seckill-100-users-html'
$tokens = 'D:\Java_dp\dianping\src\main\resources\tokens.txt'

New-Item -ItemType Directory -Force 'artifacts\load-test' | Out-Null
& $jmeter -n -t $script -Jtokens_file=$tokens -l $result -e -o $report
```

商户缓存脚本：

```powershell
$script = 'docs\load-test\shop-cache-30-users.jmx'
$result = 'artifacts\load-test\shop-cache-30-users.jtl'
$report = 'artifacts\load-test\shop-cache-30-users-html'
& $jmeter -n -t $script -l $result -e -o $report
```

运行前确认：

- MySQL、Redis、RabbitMQ 和 Spring Boot 服务均已启动。
- 测试优惠券库存和 Token 用户是专用测试数据；不要直接对生产或日常开发数据做压测。
- 记录机器配置、JDK、Redis/RabbitMQ 版本、测试优惠券 ID、初始库存和 Token 有效用户数。
- JMeter 测量的是 HTTP 受理耗时；接口返回成功后，还要等待异步队列清空，再执行 [`verify-seckill.sql`](verify-seckill.sql)。

## 结果目录

建议每轮使用独立目录，例如 `artifacts/load-test/2026-09-07-seckill-100/`，至少保留：

- `*.jmx`：脱敏后的测试配置。
- `*.jtl`：原始采样结果。
- `html/`：JMeter HTML 报告。
- `environment.txt`：机器、JDK、服务版本和配置摘要。
- `verification.txt`：前后库存、最终订单数、重复订单数、待处理数和死信归档数。

只有当 `.jtl`、HTML 报告、核验 SQL 输出和代码提交号能够互相对应时，才把 QPS、平均耗时、P95/P99 和错误率写入 README 或简历。当前仓库不填入未经重新执行的历史数字。
