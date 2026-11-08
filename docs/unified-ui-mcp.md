# dsh-Go 统一 UI 接入说明

日期：2026-11-08。对应本地 dsh-Go 基线 `f61203a9`；本轮交付包含实现、回归测试与下述真机验证记录。

## 本轮实现

手机侧新增 Kotlin `UiMcpFacade` 与独立的 12 个 `ui_*` 工具，复用现有 `PhoneCore`、串行 worker、SafetyGate、原生确认和无障碍驱动。没有新增无障碍服务，也不从中文屏幕文本推测包名、窗口、密码或可编辑状态。

.NET 侧新增 `dshgo-ui` Provider 和工厂，消费原生结构化结果；stdio 客户端保留 MCP `structuredContent`。旧 `dshgo` 文本适配器仍保留，但因缺少可信目标身份而拒绝 Host 授权访问。Windows 上游的可信身份改造与 Windows 实机测试后续进行。

## 在手机上启用及回退

1. 安装本轮构建且与原安装签名兼容的 APK；保留原有数据。
2. 在 DSH「设置 → 手机操控」启用手机操控与无障碍服务，打开「统一 UI 工具」开关。
3. 重启引擎。开关在下一次引擎启动生效，不中断正在进行的任务。
4. 以 MCP `tools/list` 为准：统一模式只显示 12 个 `ui_*` 工具，旧模式显示原有 13 个 phone 工具。工具在引擎中可能带 `mcp__ui__` 或 `mcp__phone__` 前缀；RPC 中使用裸名。
5. 回退时关闭「统一 UI 工具」并重启引擎。无需卸载、清数据或修改系统保护。

同一引擎启动只接受选定的 surface；统一模式拒绝旧 phone 连接，防止通过裸工具绕过 UI 引用约束。relay 默认仍为旧 phone 模式；最后一个参数为 `ui` 时才请求统一模式。

## 工具和结果契约

| 类型 | 工具 |
|---|---|
| 发现 | `ui_list_devices`、`ui_list_targets`、`ui_get_capabilities` |
| 观察 | `ui_snapshot`、`ui_find`、`ui_get_element`、`ui_wait_for`、`ui_screenshot` |
| 写入 | `ui_click`、`ui_set_text`、`ui_press_key`、`ui_scroll` |

成功 envelope 为 `{success:true,data:{...,bridge:{version:1,generation:"..."}}}`，失败为 `{success:false,error:{code,message}}`；同时发布 JSON 文本与 MCP `structuredContent`，截图使用 image 内容块。错误按稳定 code 处理，不匹配中文文案。`observed` 表示有后置观察，不能当作业务结果已验证；仅下发的动作返回 `dispatched`。

手机本机设备 ID 为 `android-local`，目标来自实际前台包名与 Android 无障碍窗口 ID。调用方的 `applicationId`、`windowId` 只作期望值，不能作为授权凭据。快照元素包含规范角色、标签、资源 ID、bounds、enabled/visible、允许动作与敏感标记；不发布输入值、原生节点或指纹。

每个调用方应传独立 `sessionId`（最长 128 字符）。会话固定两小时过期，过期 ID 不复活；每次桥启动最多接受 128 个 ID。快照和短 ref 绑定当前会话、最新观察和桥代次。其他会话观察、原生观察、写入或重启都会使旧引用失效。写入失败或结果不确定时不自动重试，应重新观察。

`ui_set_text` 仅接受可用、可见、可编辑的普通 textbox，最长 2000 字符；敏感字段不接受点击或代填。`ui_press_key` 支持 back/home/recents/notifications。滚动 amount 为 1..10 个原生步骤；显式 ref 必须同时带 snapshotId，且 amount=1；多步滚动每步重新观察并校验目标。省略 ref 时从前台窗口的可滚动元素中选择目标，找不到则拒绝；不沿用旧驱动跨窗口选择最大列表或盲滑的规则。

## 策略及并发边界

沿用原生拒绝名单、敏感操作确认、暂停/停止、锁屏保护。可选原生 `uiAllowedPackages` 在这些保护之外进一步收紧读取和写入；空列表表示不额外限制，畸形列表拒绝所有目标。UI 开关不会解除原有规则。

结构化观察与文本、图像一起过滤。未经授权的来源窗口使整个 UI 观察被丢弃；敏感字段隐藏名称与动作；不完整或含敏感字段的截图拒绝返回。原生确认结束后重验任务归属，执行前在同一 worker 与驱动锁内重验包名、窗口、screenId，并保留原有元素指纹复核。Android 系统或用户仍可在系统调用之间改变界面，不能宣称系统级原子性。

同一手机只使用一个写入通道。当前 gate 不跨 Host 进程，也不自动协调 ADB 与无障碍服务；没有将 `android-local` 猜测为 ADB 序列号。

## .NET 接入范围

`dshgo-ui` 工厂支持注入 `IMcpUpstreamClient`，或配置合法本地 stdio relay 的 command/args/workingDirectory。仅在该进程确实拥有 App 同 UID、本地权限及合法桥凭据时才可使用。手机原生 UI 工具本身不依赖 .NET。

PC 不能直接运行手机的 guest relay 来接入桥。此次没有增加 TCP 暴露、ADB 转发、run-as 提权或 token/UID 校验绕过；也未证明 .NET 可在当前 Android/PRoot 环境运行。因此新 Provider 默认不启用，不能将其当作已完成的 PC→手机端到端传输。

## 验证记录

- .NET：新增原生身份、结构化快照、单次条件输入、引用消费、重启代次、稳定错误码、截图及滚动边界测试；总计 126 项通过（最终检查见实施记录）。
- Flutter：改动文件静态分析无问题，策略回归测试通过。当前 Dart LSP 在中文路径下崩溃，使用临时英文路径的同源副本完成检查；测试设置 `DSH_TEST_FONT_DIR=/usr/share/fonts/opentype/noto`。
- Node：实际执行 APK 内 relay 字符串，旧 phone 与新 UI 的握手和 MCP 字节透传共 2 项通过。
- Android：主源码编译通过；完整 JVM 测试 **971 项通过**（新增 Facade 17 项、模式/策略 2 项）。普通 offline debug 构建缺少 Flutter debug Maven 元数据，在线下载 TLS 握手失败。本次 JVM 验证通过 `/tmp` 临时 Gradle init 脚本，将 `flutter_embedding_debug` 与 `arm64_v8a_debug` 替换为同版本已缓存的 release 依赖，仅用于 JVM 验证；项目依赖、生产配置未改变，不能将其视为 debug 引擎实机验收。
- 生产 arm64 release APK 通过仓库 `tooling/build-apk.sh --release --keep-daemon -- --no-pub` 构建，未使用上述 JVM 临时替换；构建成功：`1.0.12+11081037`，约 35.2 MB；构建脚本已核对版本与 arm64 ABI。`apksigner verify` 与 ZIP 完整性校验通过。重新连接后已比对已安装应用签名，一致；覆盖安装成功。
- 真机：PTP_AN10、Android 17、arm64-v8a；同签名覆盖安装成功，版本为 `1.0.12+11081037`，原有对话保留。用户手动开启无障碍后，原生读屏与统一 UI 开关保存已验证。
- 同 UID guest relay：MCP 初始化、仅 12 个 UI 工具、拒绝旧 phone surface、设备与实际包名/窗口身份、可编辑元数据、禁用控件无动作、错误包名/窗口拒绝、按钮代填拒绝、跨会话旧 ref 拒绝、wait_for 和原生 UI 图像返回均通过。
- 真机动作：用户手动批准单次输入与点击。原生输入返回 `observed`，界面实际显示 `ui-mcp-verified`；写入后的旧 ref 失效。点击后原生读屏确认计数从 0 变为 1；一步滚动后可见首行从第 1 行变为第 18 行。
- 敏感字段：合成假密码不出现在结构化输出中；字段名称为 `[REDACTED]`、actions 为空，代填返回 `ACTION_UNSUPPORTED`，截图返回 `SECURE_CONTENT` 且没有图像块。

[真机验收报告](validation/dshgo-ui-device-2026-11-08.json) 保留实际阶段结果与失败记录。最初 Android/data 测试路径在 guest 中报 `ERR_MODULE_NOT_FOUND`，改用独立下载目录后启动成功。首次确认有用户误操作，该次不计为正常动作通过；较长测试间隔后系统无障碍已关闭，工具可用性检查失败，用户再次手动开启后继续。输入成功后的首次点击前读屏曾返回 `POLICY_DENIED`，用户确认未切换页面；之后只读探测和独立点击/滚动/敏感阶段均通过，原拒绝原因尚未确定，不能将该次记录删除或声称全程无失败。写入均未自动重放。

ADB 截图命令仍失败；原生 UI 截图返回图像内容已验证，二者不能混为同一链路。本次未补授系统权限或修改原生保护来绕过拒绝。验收结束后已正常关闭测试 relay、卸载独立合成测试应用并移除手机端临时脚本和标志文件；DSH 应用与用户数据保留。

本地复核命令（在各自仓库运行，需相应依赖与字体）：

```bash
# unified-ui-mcp
bash scripts/check.sh --no-restore -m:1 -p:UseSharedCompilation=false

# dsh-Go
python3 app/tool/l10n_merge.py --check
node --test runtime/tests/phone-ui-relay.test.mjs
# 常规联网依赖齐备后可直接运行；本轮缓存受限时使用临时 init 脚本。
(cd app/android && ./gradlew :app:testArm64DebugUnitTest -Ptarget-platform=android-arm64)
# 本轮 Flutter 使用 /tmp 英文路径同源副本，以绕过分析器路径错误。
(cd app && DSH_TEST_FONT_DIR=/usr/share/fonts/opentype/noto flutter test --no-pub test/phone/unified_ui_policy_test.dart)
```

本次尚未实机补测：显式拒绝/停止/锁屏、桥重启后旧 ref、关闭开关恢复旧模式，以及独立安全窗口。相关本地回归不能替代这些实机路径。测试不得通过被测 UI 自动批准原生确认，不卸载或清除 DSH 数据。

## 后续范围

Windows 实机验证按用户要求后续进行。完整跨进程/跨通道设备协调、审批票据、远程安全传输、截图资源保留策略、Fork/Subtree 固定与供应链发布流程仍不属于已完成项。

## 最终 APK

- 路径：`/home/wangbc/文档/dsh-Go/app/build/app/outputs/flutter-apk/app-arm64-release.apk`
- 版本：`1.0.12+11081037`，arm64 release。
- SHA-256：`c9d3c837f19169d004eae80a32d72344753a1bd1ae39298094bc0522ce8977fc`。
- 安装：已完成签名兼容检查与覆盖安装；未卸载、清数据或自动批准手机确认。真机基础读屏、开关、统一模式拒绝旧连接、正常输入/点击/滚动及敏感字段保护均已验证；其余实机范围与一次未确定原因的读屏拒绝见上文。
