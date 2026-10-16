# Eclipse 面板设置入口与网盘配置

## Recovery anchor

- 目标：删除手机/电视首页原生悬浮齿轮，在 Eclipse 顶部连接状态面板增加 App 设置和网盘登录/Cookie 入口。
- 验收：设置可打开；网盘入口进入当前源原有配置中心；退出后仍为 Eclipse 首页；Cookie 不经网页读取/写入；已有盘搜表单不变；两端编译与针对性测试通过；原子提交并创建恢复标签。
- 状态：用户已批准；代码、两端编译、3 项原生测试、真实 Eclipse DOM 快照自检与截图核对完成。实现提交 8ef37482c0992db4bfa09da663ea8e97d9199852；恢复标签 recovery/home-panel-settings-20261016/20261016154241-8ef37482c099。因仓库忽略规则默认排除 docs/，本记录单独强制纳入仅文档闭环提交；不再修改功能代码或重跑验证。
- 分支/回滚基线：main / a991d0927781328e448e183cf46ea2d0fc255393。
- lane：standard；task guard：home-panel-settings-20261016。
- 保护初始改动：gradle.properties、app/.cxx/ 下 35 个未跟踪文件，均不得提交。
- 当前文件：本文件、HomeWebBridge/HomeWebController、Eclipse 面板脚本、手机 VodFragment、两端首页文件/布局、字符串、HomePanelSettingsTest；专用浮动 drawable 已删除。
- 未验证：实际手机/电视扫码及 Cookie 登录流程；第三方网页后续 DOM 改动需要重新核对。
- 下一步：真机打开 Eclipse 六边形面板，从“网盘登录 / Cookie”完成一次原配置页登录并返回首页。

## 用户要求与范围

用户要求移除先前首页浮动按钮，把设置入口放到截图六边形按钮打开的面板内，并明确网盘配置指 App 网盘登录/Cookie 配置。

范围为移除 ce14bce8 的浮动入口、增加 Eclipse 专用面板脚本、补充只导航的 SDK 方法、复用手机/电视现有分类页、必要字符串与测试。不得改接口源、Cookie 文件格式、网盘解析、播放器、第三方远端网站或现有盘搜表单。

## Best-practice review

决策问题：如何从第三方 Eclipse 的现有面板进入 App 设置和源的网盘登录配置，同时保持首页和登录数据归属不变？

### 证据（访问日期均为 2026-10-16）

| 来源/版本 | 等级 | 支持事实、适用性和限制 | 决策影响 |
| --- | --- | --- | --- |
| https://vvee.ccwu.cc/ ，HTML SHA-256 b159bd20743f72499561244e9803ffeb0786e6eed0787a1637555aa86459c266；临时副本 /tmp/webhtv-eclipse.html | A，当前实际页面 | connToggle 打开 connBody；现有 conn-section-title、conn-btn 可复用；面板为静态 DOM；页面不含 App 设置入口。远端 HTML 会变化。 | App document-end 注入两枚按钮；不存在面板时不污染其它页面；不复制整个远端页面。 |
| https://qist.wyfc.qzz.io/xiaosa/api.json 与 https://qist.wyfc.qzz.io/xiaosa/spider.jar ，JAR SHA-256 6ad6b42e7a97ac53b204ece93f0b263382f98b4dcaff3f8da2fc072ede9660bf | A，运行产物 | 当前源包含 csp_Config；已读取其 classes.dex 的 Config.homeContent/categoryContent/action/init。homeContent 提供 quark、uc、baidu、xunlei、guangya、cloud189、cloud123、alipan 等配置分类；登录动作由原 Spider 执行。不是 App 自己统一拥有的 Cookie 编辑器。 | 查询当前加载源中 API 为 csp_Config 的站点，复用 SiteApi.homeContent 和现有原生分类页；无配置中心时明确报错。 |
| 本仓库 a991d0927781328e448e183cf46ea2d0fc255393：HomeWebBridge.handle/openSetting，HomeWebController.injectSdk/getSdk/Listener | A，实际调用链 | bridge 在 Task 工作线程处理；UI 通过 App.post；SDK 使用统一 invoke/Promise；document-end 已有注入入口。 | 沿用现有分发，不添加直接读取 Cookie 的 JavascriptInterface。 |
| 同一 revision：SiteApi.homeContent；mobile FolderActivity.start；leanback VodActivity.start；ChoiceDialog.showSingle | A，成熟现有实现 | 分类页已支持 Spider action 和登录相关交互。手机 FolderActivity 使用 Result 第一分类；电视 VodActivity 已有多分类导航。 | 手机用现有 ChoiceDialog 选择配置分类再打开 FolderActivity；电视直接进入 VodActivity；均不 setHome，返回保留 Eclipse。 |
| https://developer.android.com/reference/android/webkit/WebView#addJavascriptInterface(java.lang.Object,%20java.lang.String) | A，官方文档 | JS bridge 在后台线程执行，接口对 frame 暴露，需保持线程安全与权限收敛。适用于现有 WebView SDK。 | 网盘入口只允许打开当前源的配置页，不接受任意文件路径、不返回 Cookie；UI 切主线程。 |
| https://developer.android.com/develop/ui/views/layout/webapps/webview ，页面标注 2026-10-01 更新 | A，官方文档 | WebView 支持 Java/JS 交互和返回导航；复用 WebView 及正确生命周期有助于稳定性。 | 复用现有首页，不增加用于登录的第二个网页容器。 |
| https://developer.mozilla.org/en-US/docs/Web/API/MutationObserver ，页面标注 2025-06-10 更新 | B，平台资料 | 可观察 DOM 变化并 disconnect；但已读页面的面板是静态 HTML。 | 当前使用 document-end 单次幂等挂载；不引入轮询或常驻 observer。 |

证据类别处理：上游源/运行产物已核对；官方文档已读；成熟项目代码和测试模式采用本仓库已使用的 SDK、分类页和 ChoiceDialog。该任务不是上游合并，未发现必须靠上游 PR/issue/revert 才能决定的问题，故不做无目标的历史搜索。网页平台资料已读；不涉及新算法、性能指标或 native，论文、基准和跨项目性能报告不适用。无需新增依赖或修改 ABI。

### 方案比较

1. 不变：保留浮动按钮，不能满足本次入口位置要求。
2. 修改远端 Eclipse/把完整网页内置：本仓库不控制远端，复制会失去后续更新；把 Cookie 重写成网页编辑器会扩大数据所有权和安全范围，拒绝。
3. WebHTV 窄适配（推荐）：document-end 为准确的 Eclipse origin 和 connBody 挂载本地按钮；App 设置复用 app.openSetting；新增 app.openDriveConfig 只查询当前源 csp_Config 并打开现有原生配置分类；登录和保存仍由原 Spider 完成。

### 推荐实施步骤

1. 新增 app.openDriveConfig SDK 调用和 Listener 原生导航回调，保持已有 SDK 方法兼容。
2. 后台查找当前 csp_Config 站点并读取分类；主线程在手机用 ChoiceDialog/FolderActivity、电视用 VodActivity；缺站点、空分类或加载异常由原 Promise 错误链提示。
3. Eclipse 面板增加“App 设置”和“网盘登录 / Cookie”按钮，复用网页 conn-btn/conn-actions 样式并保留键盘/遥控器可聚焦性；幂等挂载，失败显示明确提示。
4. 删除手机/电视浮动按钮布局、监听、可见性和安全区 margin 代码以及专用 drawable。
5. 验证并记录结果，task guard finish 原子提交与恢复标签。

### 验收与验证

- 手机/电视 Java 与资源编译；针对性 Robolectric 测试验证导航不改 VodConfig.home、缺少配置站点错误、原生 Intent/分类结果。
- 用真实 Eclipse DOM 的本地快照验证面板按钮只出现一次、原表单保留、设置/网盘点击分别调用正确 SDK、错误可见；真实页面可访问时做浏览器可视核对。
- 确认所有 webSettings 引用与 drawable 删除，不改变普通首页设置入口。
- 真机可用时验证从面板打开网盘配置、完成/取消原有登录交互、返回 Eclipse；不可用则明确报告未验证部分，不宣称验证过扫码登录。

### 风险、兼容和回滚

- 远端面板 DOM 变化可能导致入口无法挂载；限定准确 origin 和结构，缺失时不乱插入其它网页；保留系统返回及原有非全屏设置路径。
- 仅支持当前源提供的 csp_Config；其它源缺少配置中心时给出明确错误，不猜测 Cookie 路径。
- Cookie 不读入网页、不新增文件写入、不迁移原保存数据；登录仍使用源原生动作，兼容性等同原配置页。
- 性能：一个小本地脚本、一次幂等 DOM 挂载；用户点击网盘入口才加载配置源；不常驻轮询、不新增网络后台任务；包体只增加小脚本/字符串/Java 代码并删除浮动 drawable。
- 回滚：revert 本任务唯一提交即可恢复先前入口；不涉及 schema、二进制、数据格式或破坏性迁移。

## 用户批准

用户明确确认：“确认，按此方案实施”。

## 实施和验证记录

- 新增 `app.openDriveConfig`，后台只匹配当前已加载源的 type=3 / csp_Config 站点并调用 SiteApi.homeContent；缺少站点、空分类和异常走现有 Promise reject。UI 在 App.post 主线程打开。
- 手机端使用现有 ChoiceDialog 选择配置分类后进入 FolderActivity，电视端进入现有 VodActivity；不调用 setHome，因此保留 Eclipse 当前首页。
- HomeWebController 的 document-end SDK 回调仅在 HTTPS / vvee.ccwu.cc 挂载本地脚本；`connBody` 不存在则不挂载，存在则幂等新增两枚可聚焦按钮；复用 conn-btn/conn-actions 样式、48px 最小高度；按钮异步禁用防重入并恢复，失败在 role=status 中提示。
- 删除两端全部 webSettings 布局/监听/可见性与安全区 margin 代码和 selector_web_home_settings.xml；普通原生设置入口保持原实现。
- 编译：`:app:compileMobileArm64_v8aDebugJavaWithJavac` 和 leanback 编译均通过。第一次针对性测试编译被 SDK 37 移除旧 FingerprintManager 与 Robolectric shadowOf 重载解析冲突阻断；测试改为 Shadow.extract 后只重跑受影响测试，未改生产代码或依赖。
- `HomePanelSettingsTest`：3 tests / 0 failures / 0 errors；覆盖缺失配置中心、空分类错误、电视原生 Intent 与 Eclipse 首页保持。结果位于 `app/build/test-results/testLeanbackArm64_v8aDebugUnitTest/TEST-com.fongmi.android.tv.web.HomePanelSettingsTest.xml`。
- `node --check app/src/main/assets/js/eclipse-app-panel.js` 和 `git diff --check` 通过；源码搜索确认无剩余 webSettings/selector_web_home_settings 引用。
- Tabbit 创建目标失败且接管新标签页失败，任务已结束，无页面修改。改用内置浏览器读取本地真实 Eclipse HTML 快照。临时 HTTP 服务最初指向整个 /tmp 被自动审批拒绝，已收窄到仅包含 index.html 的 `/tmp/webhtv-panel-fixture` 并获准。
- DOM 浏览器自检 PASS：脚本挂载两次仍只有一个入口区域；设置调用正确；网盘拒绝错误可见、按钮恢复；随后成功调用正确；盘搜地址/账号/密码表单保留。截图 `/tmp/webhtv-home-panel-preview.png` 已目视核对，面板顶部布局正常。
- 未做真机扫码/登录：没有声称已验证实际认证；本次不读取、不复制、不改写 Cookie 文件。
- 本次回滚为 revert 任务唯一实施提交；前态 a991d0927781328e448e183cf46ea2d0fc255393 保留已有源和默认首页配置。
