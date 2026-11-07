# 插件移动适配验证记录

日期：2026-11-07。实现基线 `428613eae339fb5a292167865d3a5c83b3fe2d10`，结果对应本记录所在提交的源码。方案来源为《dsh-Go 插件 Slot 与插件岛 Mobile 全面适配项目实施方案》V1.0；其中建议的类名、字段和示例不是既有协议。

## 实现与方案的对应

| 模块 | 本次结果 |
|---|---|
| M1 策略与诊断 | 四种模式、全局/单包持久化开关、运行中更新、稳定降级原因、结构统计；启动中更改模式在 ready 后补发 |
| M2 原生设置 | 复用已有编辑 sheet、滚动和保存条，以真实 Schema 表单验证窄屏/文字缩放；没有为设计占位类另建框架 |
| M3 Web 核心 | 岛与 Web Slot 共用控制器及六条规则；容器宽度、可逆属性、动态 DOM、分批扫描、隐藏/卸载清理 |
| M5 交互 | 四边 inset、焦点可见性、保留插件取消语义的返回处理；平台手势仍需完整 App 验收 |
| M4 DSH Web | 仅允许的插件 Slot；官方导航不分类；多包策略冲突降级；手机布局关闭及隐藏 Slot 清理 |
| M6 声明 | 已实现 DOM v1 RFC/JSON Schema，未知字段忽略、无效声明降级；未添加未经支持的 manifest 字段 |
| M7 降级与可观测 | 异常清理及 compatibility、结构诊断、已有池/Host 回退、双语指南和回滚入口 |
| 验证与交付 | Flutter/Node 回归、独立真实 Android WebView 工程与脚本、Android 11/15 CI 矩阵 |

保留已有 WebView 池、Host gate、导航白名单、权限及回退路径。多个需要填满页面的岛仍使用有限高度分配；原生配置使用列表，不把所有实时面板强改成无限高列表。

## 自动化检查

命令从仓库根目录执行（Flutter 测试从 `app/`）。本机工具链先 `source tooling/env.sh`。

```bash
dart analyze app
node --test app/test/plugins/mobile/*.mjs
python3 app/tool/l10n_merge.py --check
python3 app/tool/guide_sync.py --check
bash -n tooling/test-mobile-webview.sh
git diff --check

cd app
DSH_TEST_FONT_DIR=/usr/share/fonts/opentype/noto flutter test --no-pub \
  test/plugins/islands/ test/plugins/mobile/ test/plugins/seats/ \
  test/plugin_config/ test/web/ test/settings/index/index_sync_test.dart \
  test/settings/quick_panel_settings_test.dart test/settings/settings_ia_golden_test.dart
```

静态分析无问题；Node 声明测试 2 项通过，无跳过；文案 50 parts / 5751 keys 与指南 26 个文件同步检查通过。Flutter 上述最终回归命令 589 项通过，无跳过；启动竞态修复后另跑 `flutter test --no-pub test/plugins/islands/island_view_test.dart`，24 项通过。SSH 网页布局重建修复后另跑 `flutter test --no-pub test/web/web_mode_test.dart`，22 项通过。

原生矩阵：320/360/393/480/600/768/1024 logical px × 100%/130%/200% 字号 × 明暗主题，42 种组合；30 字段表单滚到末项后实际打开编辑器。另验证 IME inset 下编辑 sheet、320px/200% 字号 golden。入口与界面设置的六张现有 golden 仅因模式菜单/开关更新，逐图检查过；新增两张表单 golden。

DOM 读写分批改动后另跑 `flutter test --no-pub test/plugins/islands/plugin_page_harness_test.dart`，14 项通过。Node harness 验证同一文档反复宽窄恢复、关闭后恢复、增删子树、超过 1400 节点、错误降级和 pagehide 后 observer 不复活。它模拟几何，不能替代下面的真实 WebView。

## App 构建

常规入口 `tooling/build-apk.sh --debug -f arm64 --keep-daemon` 本机遇到 Maven TLS 下载失败。直接 offline 也因仓库索引缺失而无法解析已缓存的 Flutter embedding。为继续验证，把现有 `~/.gradle/caches/modules-2/files-2.1/io.flutter` 的 POM/JAR 按 Maven 目录复制至 `/tmp/dsh-mobile-flutter-maven`，通过临时 Gradle init 脚本仅为本 App 及其插件子项目添加该 `io.flutter` 仓库，没有修改仓库构建配置、SDK 或全局 Gradle 配置。

```bash
app/android/gradlew -p app/android --offline \
  -I /tmp/dsh-mobile-local-flutter.gradle --max-workers=2 \
  :app:assembleArm64Debug --console=plain
```

临时 init 脚本只在 `project.rootProject.projectDir` 为本 App Android 工程时，调用 `project.repositories.maven` 添加上述 `/tmp` URL，且 `content { includeGroup('io.flutter') }`。首次离线全量尝试在 dex 合并期间退出 143，没有得到成功结果；上方命令降低并行度后重试成功：`BUILD SUCCESSFUL in 5m 25s`，289 个任务（69 执行、220 最新）。最后的 Dart 设置修复、JS 分批及弹窗返回变更增量打包成功：`BUILD SUCCESSFUL in 46s`。产物 `app/build/app/outputs/apk/arm64/debug/app-arm64-debug.apk`；逐字节核对 ZIP 内五份生产 JS/CSS，均与当前源码相同。APK SHA-256：`99e8c5680b3a69f26b0628ea82bf9cb0000720a9e79b72a11dbbc14bc2090178`。未将该完整 App APK 安装覆盖用户现有 App，真机交互结果来自独立测试包。

## Android 设备测试

独立包 `io.github.dshgo.mobiletest`，arm64-v8a 真机，Android API 37，WebView `155.0.8059.43`。没有安装覆盖正式 App，不连接主机、账号或真实插件数据。输入全部为合成值；图像由测试 WebView 自身绘制，未捕获设备屏幕。

可重复入口：

```bash
tooling/test-mobile-webview.sh /tmp/plugin-mobile-report
```

本轮为处理手机安装提示，分别执行 `assembleDebug --offline`、更新安装、`adb shell am instrument -w io.github.dshgo.mobiletest/.MobileInstrumentation` 和 pull 测试应用自己的 files。报告保存源码资源 SHA-256；必须与工作树逐项匹配才算最终通过。早期 88 项通过的报告因后续资源变化不作为最终证据。最终 **95 项通过，5 份 JS/CSS 哈希全部匹配**；[JSON 报告](artifacts/plugin-mobile-2026-11-07/report.json)保留逐项结果与哈希。已人工检查的合成页面图：[表单](artifacts/plugin-mobile-2026-11-07/mobile_form.png)、[表格和卡片](artifacts/plugin-mobile-2026-11-07/mobile_table.png)、[弹窗和标签条](artifacts/plugin-mobile-2026-11-07/mobile_dialog_tabs.png)。输入页滚动后的软件绘制图为空白，不作为视觉证据；输入路径结论来自原生交互及 DOM 断言。

测试内容：七种 CSS 宽度及反复恢复、固定宽页面边界/工具栏换行、卡片列数、保留表格列的内部滚动、官方导航隔离、Slot 隐藏/显示及删除、1500 余节点、动态增删、100 次 stop/start、30 次已实际应用的方向变化。200% text zoom 下，通过原生 InputConnection 输入并核对八类文本控件、焦点可见性、选择范围、返回后值保留；另验证 portal 根弹窗消费返回、插件异步关闭和原生 `<dialog>` 识别；打开并取消原生选择/日期/时间/颜色/文件选择器，点击 checkbox/radio 和保存按钮。range 出现在 fixture 中，未测试拖动滑块。

测试修正记录：最初键盘吞掉按键注入，改为 WebView 的原生 InputConnection；选择文本后关闭 IME 再测选择器，避免布局变化中的事件落点竞争。测试装配补入实际 `island_mobile.css` 边界层，防止只测试 role 标记而没有验证固定宽页面的最终呈现。

## 尚未完成的验收

以下不能由本轮合成页面结果替代，未标记为通过：

- Android 11/15 矩阵已接入 CI，尚未远程运行。本机 emulator 启动超时。
- 完整 App 内真实 React 插件、页面/面板/overlay 混合嵌套滚动和 Flutter platform-view 手势仲裁。
- TalkBack 人工朗读、系统长按复制粘贴、range 实际拖动、超长内容完整操作流程。
- 真机主机断连、低内存 renderer 回收、后台恢复和 release 模式帧耗时/内存验收；现有模拟测试只覆盖回退契约。
- 已将每批计算样式/几何读取与属性写入分离；复杂页面的 release 模式帧耗时仍需实际测量。

因此代码补齐与已运行测试可审阅，但不能据此宣称方案全部 DoD 已获验收，也不承诺任意未知插件达到 L2。回滚与支持边界见[设计说明](../design/plugin-mobile-adaptation.md)。
