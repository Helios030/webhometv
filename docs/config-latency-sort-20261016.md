# 设备网络检测点播接口延迟并排序

## Recovery anchor

- 目标/验收：打开设置中的点播源切换列表时，设备按当前网络测量源 URL 的 HTTP 响应延迟，显示毫秒数；成功项升序、失败项置底；取消不更新旧窗口；手动选择及当前源保持原行为；两端编译和针对性测试通过；原子提交并恢复打标。
- 状态：用户已批准并完成实现；两端编译、8 项网络检测测试及 2 项电视列表测试通过，待原子提交/恢复打标。
- 分支/HEAD/回滚基线：main / fb0809fe7cdbd3e2d2fd63652f907c35649bad15。
- lane/guard：standard / config-latency-sort-20261016。
- 保护初始脏文件：gradle.properties、app/.cxx/ 下 35 个未跟踪文件。
- 范围：公共 ConfigLatencyProbe、两端 ConfigAdapter/HistoryDialog、三个语言 strings、测试及本文件；不改数据库、接口内容、站点健康排序、播放器或全局网络配置。
- 已完成：公共检测会话、两端列表与生命周期接入、三种语言文本；针对性验证及结果见下文。任务改动尚未提交。
- 未验证：真实手机/电视当前网络、网络切换及遥控器操作；本机网络测试使用 MockWebServer，电视焦点使用 Robolectric。
- 下一步：task guard finish 原子提交所有任务文件并创建本地恢复标签。

## Best-practice review

决策问题：如何让源排序反映设备实际访问配置地址的等待时间，并避免阻塞 UI、错误地把排队时间当网络延迟、误切换焦点或遗留后台请求？

### 证据（访问日期 2026-10-16）

| 来源/版本 | 等级 | 事实、适用性、限制及决策影响 |
| --- | --- | --- |
| 本仓库 fb0809fe7cdbd3e2d2fd63652f907c35649bad15：mobile/leanback HistoryDialog.initView/onTextClick/onStart；ConfigAdapter.addAll/onBindViewHolder/remove | A | 两端通过 Config.getAll(type) 读取同一接口数据库；排除当前源后展示；点击 ConfigListener.setConfig。适合只在点播且非 readOnly 的切换窗口启动检测，排序只修改展示列表，不改数据库 time/当前源。删除可能在检测中发生，因此结果只更新 Map 和排序现存列表，不能用旧快照覆盖列表。 |
| 同 revision：catvod OkHttp.client/client(timeout)/newCall/cancel；Decoder.getJson；utils/Task | A | 实际源加载使用 App OkHttp 的 DNS/代理/拦截器；已有 client.newBuilder 短超时模式。检测继承相同网络配置，避免强制绕过用户代理。独立 4 worker 执行同步 Call，计时从 worker 内开始，排除本批任务排队时间。不占用 Task 的五个通用 worker，不取消全局 client。 |
| 同 revision：player/diagnostic/PanNetworkDiagnosticRunner.cancel/release；测试 PanNetworkDiagnosticRunnerTest | A，成熟项目代码/测试 | 已有单会话 Executor、跟踪 Call、取消和 release 模式；完整网盘吞吐测试依赖 Media3 且强制 NO_PROXY，不能直接用于源响应延迟。仅复用生命周期思路，不调用重型播放器诊断。 |
| OkHttp 官方 parent-5.4.0： https://raw.githubusercontent.com/square/okhttp/parent-5.4.0/okhttp/src/commonJvmAndroid/kotlin/okhttp3/OkHttpClient.kt | A | 已读实际来源：newBuilder 共享连接池并继承配置；callTimeout 限定完整调用，connect/read 单独限时；取消/释放有明确边界。决定复用 App client、调用总超时 5 秒，连接/读 3 秒；响应头返回后关闭 response，不读取完整配置或 JAR。连接复用会影响测值，故只作为当前设备实际访问快照。 |
| OkHttp 官方同 tag： https://raw.githubusercontent.com/square/okhttp/parent-5.4.0/okhttp/src/commonJvmAndroid/kotlin/okhttp3/Dispatcher.kt | A | 已读排队/并发实现；异步 enqueue 存在等待队列。选择固定 4 worker + execute，在真正开始请求时计时，不把队列等待计入访问延迟。 |
| Android 官方 RecyclerView.Adapter： https://developer.android.com/reference/androidx/recyclerview/widget/RecyclerView.Adapter#setHasStableIds(boolean) ，页面更新 2026-06-24 | A | stable ID 必须唯一；接口数据库 ID 已满足。排序后通过稳定 ID 恢复/保持对应条目身份；完成后仅排序一次，避免实时跳动列表；对遥控器焦点保留做针对性测试。 |
| Android 官方 Fragment lifecycle： https://developer.android.com/guide/fragments/lifecycle ，页面更新 2026-02-26 | A | onDestroyView 后应释放 view 引用。onDismiss/onDestroyView 都取消本会话，回调在主线程再次检查取消状态，避免关闭/旋转后更新旧列表。 |

证据类别处理：精确本地实现/调用者/现有测试、官方库源码、Android 平台文档、成熟相关实现均已读取。不是上游合并，不存在需要评估的候选 commit；未发现必须靠 PR/issue/revert 决定的问题，故不做无目标历史搜索。该需求为已有 HTTP 客户端和列表生命周期的窄组合，不涉及新网络算法/协议或吞吐结论，论文、跨项目 benchmark 与普通博客不适用。初始 OkHttp 站点文档 URL 404 后已改读当前安装版本官方源码，未把搜索摘要当结论。

### 方案比较

1. 不改变：继续按历史时间展示，不满足设备测速排序。
2. ICMP ping 或 HEAD/全量下载：ICMP 可能被禁并不能体现 TLS/HTTP/重定向；HEAD 存在服务器不支持或与 GET 处理不同；全量下载/JAR 执行扩大流量和范围。拒绝。
3. 窄适配（推荐）：真实配置 URL 发起 GET，复用设备 App HTTP 网络栈；测量从请求实际开始到最终响应头返回的时间（含连接/TLS/重定向，排除本批任务排队）；仅 2xx 计成功；不读取或执行配置体；四个并发 worker，单调用最多 5 秒。

### 推荐行为与接受标准

- 打开点播切换窗口即异步检测，条目显示“检测中”；仍可即时选择/删除。
- 全部完成后统一按成功延迟升序排序，并显示 `%d ms`；同延迟或全部失败保持原相对顺序。
- 本地 file/assets/clan 配置不做互联网检测，标为“本地”，排在成功网络项后、失败前；不支持的地址/HTTP 错误/超时标为“不可达”，置底。
- 仅检测当前列表中的候选项，沿用现有排除当前源逻辑；不自动选择最快源，不改持久化排序/源名称/首页设置；直播/壁纸/readOnly 列表不新增检测。
- 每次新开窗口重新检测；不使用电脑预置时间或跨网络缓存，不新增定时后台监控、设置开关或依赖。
- 对所有请求强制网络访问而非缓存；继承设备现有 DNS/代理；继承原 TLS 行为，不引入新的跳过证书校验。
- 关闭/取消/销毁窗口取消仅本会话 calls、停止 executor，并抑制已排队 UI 回调；删除条目后回调不得恢复该条目。
- 网络并发/排序/显示/取消逻辑由公共类共享；两端仅接入现有 Adapter/HistoryDialog。完成排序启用稳定 ID 并验证电视焦点仍指向原条目。

### 最小实施/验证

1. 公共检测会话：有界 executor、计时、状态 Map、稳定排序、可取消；测试用已有 MockWebServer 验证响应/重定向、HTTP 错误、超时、取消、计时与本地地址分类。
2. 两端 Adapter 接入状态文本和稳定 ID；HistoryDialog 在点播非只读场景启动，并在 onDismiss/onDestroyView 取消。
3. 公共算法/网络测试、Robolectric 列表稳定 ID/焦点场景，手机及电视 Java/资源编译。可用设备上测试当前网络和开关窗口；无设备时如实记录未验证部分。
4. 更新本文件结果；因 docs/ 被忽略，明确仅 force-add 本文件；task guard 原子提交并创建恢复标签。

### 风险、性能、兼容与回滚

- 响应延迟不代表影片播放速度/内容有效性，仅代表配置地址当次可访问性；网络波动会改变排名。
- 慢源最多等待自身 5 秒；大量配置分批排队，UI 仍可操作，关闭即取消。每次只读响应头并关闭连接体，避免全量文件/JAR 下载。
- 继承连接池意味着同域连接复用可能更快，符合实际加载行为；没有跨网络缓存，不承诺严格冷启动 RTT。
- 不新增 API 对外协议、数据库 schema、Cookie 或认证数据存储，不修改 native/ABI；包体仅小量 Java/字符串。
- 回滚：revert 本任务唯一提交，恢复旧接口列表显示/排序，无数据迁移。

## 批准与实施记录

用户明确回复“确认，按此方案实施”，批准以上行为和范围。

实现采用公共 `ConfigLatencyProbe`，固定 4 个 worker；继承 App OkHttp 网络配置，单次调用 5 秒超时，计时不含批次排队，只测最终响应头。两端 `ConfigAdapter` 显示检测状态，全部完成后对当前仍存在的条目稳定排序；数据库 ID 作为稳定条目 ID。`HistoryDialog` 仅在点播非只读列表启动，关闭和销毁时取消会话；取消同时抑制已排队的主线程回调。无数据库变更、自动切源、依赖新增或初始脏文件改动。

### 验证记录

- 手机 `compileMobileArm64_v8aDebugJavaWithJavac` 和电视 Java/资源编译通过，日志 `/tmp/webhtv-config-latency-test.log`。
- `ConfigLatencyProbeTest` 8 项全部通过：成功/错误状态及排序、本地/无效地址、重定向且不等待响应体、超时、最多 4 并发且排队时间不计入延迟、进行中取消、已排队回调取消、空列表。首次联合运行共 10 项、仅电视焦点测试失败，网络 8 项和删除条目测试通过。
- `ConfigLatencyAdapterTest` 2 项最终通过（0 failure/error），日志 `/tmp/webhtv-config-latency-focus-test.log`。验证最快置顶、焦点仍对应原数据库 ID、点击仍选择原条目、失败文本、删除项不被旧结果恢复。
- 焦点测试最初读取 Robolectric Activity 的 `getCurrentFocus()` 返回 null；补齐帧任务仍如此。最终直接断言 RecyclerView 的 `findFocus()`，先确认请求焦点成功，再确认排序后的条目 ID；实际列表焦点保持正确，未放宽生产行为。
- `git diff --check` 通过；最终 task guard finish 还会执行暂存区空白和范围检查。
- 没有可用真机验证本轮设备网络和遥控器行为；不把 HTTP 响应延迟解释为影片播放吞吐或源内容有效性。

文档因仓库忽略 `docs/`，仅对本任务文档 force-add；其余路径沿用 guard 的范围检查、原子提交及立即打标。回滚锚点仍为上列基线，提交本身可独立 revert。
