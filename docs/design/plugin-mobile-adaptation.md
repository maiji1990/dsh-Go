# 插件 Slot 与插件岛的移动端适配（MOBILE-ADAPT）

> 2026-11-07。**状态：已审计，未实施。** 本文是 PR-1 的审计结论与后续 PR 的执行依据，不是已完成的适配报告；任何「已通过真机验证」的说法都必须等实测记录。
>
> 基线：`f9804fa9`（1.0.12）。实施前重新 `git fetch` 并锁 `HEAD SHA`，行号以实际 HEAD 为准。

## 0. 三档标准与承诺边界

| 档次 | 定义 | 能否作为交付 |
|---|---|---|
| L0 防越界 | 内容不冲出屏幕，但可能细小、凌乱、不好按 | 不能 |
| L1 可操作 | 内容可达，表单能填、弹窗能关、键盘不挡，功能正确 | P0 最低验收 |
| L2 移动友好 | 按组件语义重排，操作层级清楚，触控和导航合理 | 本项目目标 |

承诺：**App 自己控制的布局与容器做到 L2**；**任意第三方插件保证 L1**，无法安全识别时给可滚动的桌面视图 +「在网页版打开」；**不承诺**把任意 React 页面自动变成原生。

## 1. 基线与既有事实

| 项 | 值 | 来源 |
|---|---|---|
| 仓库 HEAD | `f9804fa9` | `git log -1` |
| Flutter / Dart | 3.47.7 / 3.13.5 | `.github/workflows/ci.yml` 的 `FLUTTER_VERSION` |
| DSH | `0.2.2-rc.1` | `runtime/build-runtime.sh` 的 `DSH_VERSION` |
| 运行时 | `r17-dsh0.2.2-rc.1-node24.22.1-ubuntu24.04.5-3c4c919cd37a` | 已发布的 runtime Release |
| 现有 JS 测试基座 | `app/test/plugins/islands/support/island_page_harness.mjs`（node，无 DOM 库，虚拟时钟；`view` / `panel` / `settings` / `rightbar` / `plugin` 五个 target） | 由 `island_view_target_test.dart`、`island_names_test.dart`、`plugin_page_harness_test.dart` 调用 |
| 该基座的模型版本 | **DSH 0.2.2-rc.1**（已按 r17 的 `dsh-prefix` 校准） | 核对过的钩子：`[data-conversation-tabs]`、各 `[data-slot=…]`、`[data-shortcut-modal]`、`[data-sidebar-right-tab]`、`[data-ds-dark-theme]`、`更多会话视图`；0.2.2 的 phone 壳（drawer + `inert`）用 `inertShell` 建模 |

harness 的页面模型已按 r17 的 `dsh-prefix` 校准到 0.2.2-rc.1（§1 最后一行列出核对过的钩子）。**上游每次同步 DSH 版本后要重跑这次核对**：钩子被改名时，harness 会先坏，比 App 坏得早，这是它的价值。

## 2. 现状审计

### 2.1 三条注入路径

| 路径 | 入口 | 适配载体 |
|---|---|---|
| A 原生 Seat | `plugin_settings_tiles.dart` → `plugin_seat_page.dart` → 原生表单 / 插件岛 | Flutter Widget |
| B 插件岛 | `island_view.dart` + `island_assets.dart` | `island.js` + `island.css` + `island_mobile.css` |
| C 官方网页版手机模式 | `web_view_page.dart` 注入 | `dsh_web_phone.css` 末尾的插件 slot 段 |

三条路径共用一份规则表（§5）和同一批 fixture，但渲染器各自实现：Flutter 那边不跨语言复刻 CSS 规则。

### 2.2 逐层结论（带行号）

**B 层 · `app/assets/islands/island_mobile.css`（55 行）** 只有 5 条边界规则：`text-size-adjust` 与换行（12-16）、块级 `max-width:100%`（19-24）、媒体不越界（26-29）、控件 `max-width` + 16px 防聚焦缩放（43-46）、超宽子树横向滚动（50-55）。**没有** label/控件纵排、没有工具栏换行、没有弹窗限高、没有 tab 横滑。

**B 层 · `island.js` 的 `fitToWidth()`（709-731）** 是唯一的布局变换，问题都已确认：

| 行为 | 位置 | 问题 |
|---|---|---|
| 取 `*` 前 1200 个节点 | `:715` `Math.min(nodes.length, 1200)` | 大页面后面的节点永远不处理 |
| `display:flex` 且横向 → `flexWrap='wrap'` | `:724` | 工具栏「碰巧」对了；「图标 + 文本」「按钮 + 输入框」这种不该断的行也被断 |
| `gridTemplateColumns` 轨道 ≥3 → `minmax(0,1fr)` | `:728-729` | 卡片网格对了，**8 列数据网格被压成一列**（误伤） |
| 只有 `resize` + 500ms/1600ms 各跑一次 | `:1218`、`:1360-1362` | 没有 MutationObserver，动态节点不处理 |
| 写 `el.style.*` 内联样式 | `:724`、`:729` | 没有恢复逻辑；变宽、切页、关闭开关都不会撤销 |

**C 层 · `dsh_web_phone.css` 插件 slot 段（末尾 86 行）** 只在 `[data-slot="settings.section"]`、`[data-slot="settings.general.item"]`、`[data-slot^="plugins."]` 上做 `max-width` / `overflow-wrap` / `pre,code` 换行，没有组件语义。

**B 层 · 一处自相矛盾（本次审计新发现）**：`island_mobile.css:17-20` 的注释写明「Never restyle a component's look (colours, type, spacing inside cards)」，但 `island.js:734-743` 对判定为「卡片」的节点直接改了 `backgroundColor`、`borderColor`、`borderRadius`，并打上 `data-dsh-go-card` 永久标记（不会撤销）。这条规则要么补进设计、要么删掉——按 §0 的承诺（适配只保可操作性）建议**删掉**，或者改成只加一个可撤销的 `data-dsh-mobile-role="card"` 标记，样式交给宿主主题。

**A 层 · 原生 Seat**

- `plugin_seat_page.dart:59-61`：`Column(children: [for (c in children) Expanded(child: c)])` 平分高度；`:70` 另有一条滚动分支，两者并存。原生表单走的是 `AppPage.list`（可滚动），平分只发生在「一个页面里放多个岛」的情形。
- `seat_island.dart:33-35`：改造前只给 `IslandForm.page` 加 `padding.bottom`，**横屏的左右刘海留白没有**（顶部由 `AppPage` 的 bar 负责，不重复加）。已补：`pageInsetsFor()` 在横屏时同时让出 `MediaQuery.padding` 的左右，单测直接覆盖两种朝向。
- **实测（PR-2 新增 `test/plugins/mobile/plugin_narrow_test.dart`，9 个用例）**：320 / 360 / 393 px × 2.0 字号下，插件岛页面、只有「在网页版打开」的页面、设置页插件行、超长分节标题**都没有溢出**，行与入口可达。也就是说 A 层的窄屏问题是「没人验证过」，不是「已知坏掉」；现在是有人守着。

### 2.3 三个 fixture 的判定

fixture 在 `app/test/plugins/islands/fixtures/`。`island_page_harness.mjs` 新增了 `plugin` target：把插件的页面画进官方设置 section，跑真实的 `island.js`，输出它改了哪些节点的哪些内联样式、留了哪些标记（`changed` / `cards` / `wide` / `residual`）。三个 fixture 都已接上（`plugin_page_harness_test.dart`，5 个用例）。

| fixture | 应达到 | 会出现什么 |
|---|---|---|
| `mobile_form.html` | L1 | **实测**：`toolbar`、`row-api`、`row-model` 三个 flex 行都被写 `flexWrap: wrap`——工具栏碰巧对了，两个 label+控件行换行后 label 仍是 `width:140px; text-align:right`，控件仍被挤出 frame，**不到 L1**；`card-summary` 被改 `backgroundColor` / `borderColor` / `borderRadius` 并留下 `data-dsh-go-card`；`residual` 列出所有被碰过的节点，**没有一处被撤销**。`.actions` 吸底条在键盘下的问题没有规则覆盖 |
| `mobile_table.html` | L1，卡片网格到 L2 | **实测**：`cards` 的 `gridTemplateColumns` 被改成 `minmax(0, 1fr)`（卡片网格压成 1 列，正确）；`filters` 被 wrap（可接受）；`data-table` **没有任何规则**，只被标成 wide，靠 `island_mobile.css` 的横向滚动兜住（可访问，算 L1）。注意 3-up 卡片宽度 180 < frame 的 55%，所以没有触发卡片重绘——重绘只在近满宽的卡片上发生 |
| `mobile_dialog_tabs.html` | L1 | **实测**：`dialog` 与 `dialog-body` **完全没有规则**——620px 高的固定弹窗在 360px 高的 frame 里，底部「取消 / 确认」落在视口外（L0 不达标）；`tabs` 只拿到通用的 `flexWrap: wrap`，不是横向滚动，选中项也不会被 `scrollIntoView`；`dialog-footer` 被 wrap（无害） |

### 2.4 审计结论

| 优先级 | 缺口 | 归入 |
|---|---|---|
| P0 | 弹窗限高 + 内部滚动；tab 横滑；label/控件纵排；样式可撤销 | PR-3 |
| P1 | Grid 误伤（数据网格被压缩）；动态节点 | PR-3 |
| P2 | 工具栏折叠策略、44px 触控目标、横屏重排 | PR-4 |

## 3. 共享文件边界（上游同步的硬约束）

本仓库是 fork，`main` 定期合并 `upstream/main`。适配代码必须落在**冲突面最小**的位置：

| 文件 | 上游是否也在改 | 允许的改动 |
|---|---|---|
| `app/assets/web/dsh_web_phone.css` | 是（1.0.12 内有 `cc78049d`、`b9f11c05` 等 3 次） | **只动末尾插件 slot 段**，或新建独立 CSS 由注入层加载；不动前 384 行 |
| `app/lib/ui/web/web_view_page.dart` | 是（与上游逐字节相同） | 不改，除非先与上游对齐 |
| `app/assets/web/dsh_web_phone.js` | 是（逐字节相同） | 不改 |
| `app/assets/islands/island.js`、`island_mobile.css` | 否（上游只有 1.0.0 初始提交） | 可重构 |
| `app/lib/ui/plugins/**` | 否 | 可重构 |

另外两条边界来自 DSH 0.2.2 本身：

1. **768px 以下官方自带 drawer**，`inert` 由官方管理（上游 `cc78049d` 已适配）。宿主导航、抽屉、官方 modal 一律不接管。
2. 插件 slot 的稳定钩子是 `data-slot` 与 `role`/`aria-*`；不要依赖 CSS-module 的哈希类名。

## 4. 目标架构

```text
        Slot / Seat 计划（现有）
                 │
        MobilePolicy（新增，按插件）
                 │
     ┌───────────┴───────────┐
  原生 Seat                插件岛 / 官方 Web slot
 NativeMobileAdapter      WebMobileAdapter
  LayoutBuilder + IME      scoped CSS + 增量规则引擎
     └───────────┬───────────┘
             Diagnostics（不含用户输入）
```

模式下限：未识别 = `compatibility`（只保边界 + 横向滚动），识别成功且规则命中才进 `automatic`。

```dart
enum MobileAdaptMode { pluginResponsive, automatic, compatibility, desktop }
```

策略优先级：用户按插件覆盖 > 插件声明（未来）> 验证过的规则 > 保守检测 > `compatibility`。

## 5. 规则表（先做六条）

| 规则 | 命中依据 | 手机行为 | 保护条件 | 现状 |
|---|---|---|---|---|
| `form.stack` | 同级 `label` + 控件，容器 < 480 | label 与控件纵排，控件满宽 | 不改 switch/checkbox 语义 | 未实现 |
| `toolbar.wrap` | 子元素都是按钮且一行放不下 | wrap，主操作保持可见 | 不碰 `tablist` / `nav` | 部分（无条件 wrap） |
| `card-grid.collapse` | Grid 且子项是卡片（标题 + 短内容），列数 ≥3 | 1 列 | 表格式 Grid 不适用 | 会误伤 |
| `table.scroll` | `table` 且列数 ≥5 | 包一层横向滚动容器，首列可读 | 不拆行、不改列宽 | 部分（仅 CSS） |
| `dialog.fit` | `[role=dialog]` / `[aria-modal]` | 限高（视口 − 安全区），头尾固定、body 滚动 | 不改 portal 层级 | 未实现 |
| `tab.scroll` | `[role=tablist]` 溢出 | 横向滑动 + 选中项 `scrollIntoView` | 不改 tab 顺序 | 未实现 |

规则只加 `data-dsh-mobile-role` / `data-dsh-mobile-compact` 这类自己的标记，样式放在独立的 `island_mobile_components.css`，不改插件原有 inline style。

## 6. 分 PR 执行

| PR | 内容 | 验收 |
|---|---|---|
| **PR-1 基座与可测性** | ✅ 已完成：`plugin` target + 三个 fixture + 页面模型校准到 0.2.2-rc.1 + `diagnostics`（slot / mode / width / rules / cards / wide / inertShell / shellStillInert）+ 幂等断言（同一 fixture 跑两次 `changed` 与 `diagnostics` 必须一致） | `flutter test test/plugins/islands/` 83 个用例全绿 |
| **PR-2 原生 Seat** | ✅ 已完成：`seat_island` 补横屏左右留白（`pageInsetsFor()`，单测覆盖竖/横屏）；新增 320/360/393 × 2.0 字号的 9 个用例（岛页面、网页兜底、设置行、超长标题）。实测没有溢出，`SettingsTile` 与 `AppPage.list` 无需改动 | `flutter test test/plugins/mobile/` 9 个用例全绿 |
| **PR-3 Web 适配核心** | `fitToWidth()` → `MobileLayoutController`（ResizeObserver + MutationObserver + `requestAnimationFrame` 批处理 + 节点/时间预算 + `changed` 表可撤销）；新增 `island_mobile_components.css`；实现 §5 六条规则 | 三个 fixture 达到 L1；变宽/卸载后 `residual` 为空；单帧 ≤8ms |
| **PR-4 双宿主收尾** | 同一套规则接进 `dsh_web_phone.css` 的插件 slot 段（只碰那 86 行）；与「网页版手机布局」开关联动；弹窗/触控/旋转专项；真机记录 | 关掉开关后 DOM 无残留标记；Android 真机截图 + 步骤 |

## 7. 测试

- **复用现有基座**：`island_page_harness.mjs` 的虚拟时钟 + 手写 DOM 模型（node，无第三方 DOM 库）。不要引入 jsdom/jest——仓库没有这些依赖，CI 也没装。
- **`island_page_harness.mjs` 的 `plugin` target**（已实现）：`target: "plugin"` + `pluginDom`（fixture 的 JSON 模型，`{name, tag, attrs, style, box, scroll, kids}`）+ `width` / `frameHeight` / `inertShell`，输出 `{changed, cards, wide, residual, diagnostics}`。fixture 的 HTML 是给人看的参照，JSON 场景是给 harness 跑的同一页面，两者改一处要一起改。
- **已自动化的两条**：`diagnostics`（哪个 slot、什么模式、多宽、命中了哪些规则、清没清 inert）与**幂等**（同一 fixture 跑两次，`changed` 与 `diagnostics` 必须逐字节一致）。`residual` 为空是可撤销性的判据，由 PR-3 的测试来收紧。
- **判据**：幂等（两次运行 `changed` 相同）、可逆（变宽后 `residual` 为空）、预算（单帧 ≤8ms / ≤200 节点）、作用域（`data-slot` 之外的节点零改动）。
- **Flutter**：`app/test/plugins/mobile/` 下 widget/golden（320/360/393/480/600），字号 1.0/1.3/2.0。
- **真机**：Android 11 与 Android 15 各一台，记录截图、系统版本、步骤。

## 8. 开关与回滚

- 全局：`mobileAdapterEnabled`（设置 → 网页版手机布局 联动）
- 单插件：`pluginMobileMode[package]`（插件入口长按 → 「兼容视图」）
- 回滚：停 observer、删 `data-dsh-mobile-*`、移除注入的 `<style>`；**不删用户配置与插件数据**

## 9. 明确不做

1. 插件 manifest 的 `dshGo.mobile` 声明协议——等真实插件作者需求，先写 RFC 与 schema 校验。
2. 承诺任意插件达到 L2。
3. 修改 `dsh_web_phone.css` 前 384 行、`web_view_page.dart`、`dsh_web_phone.js`。
4. 引入新的 JS 测试依赖（jsdom / vitest / jest）。
5. 用提高 `z-index` 的方式"修"弹窗层级。

## 10. 对外部方案（V1.0 计划）的勘误

| 外部说法 | 实际 |
|---|---|
| 「JS test 的目录与 runner 待确认」 | 已有 `app/test/plugins/islands/support/island_page_harness.mjs`，node + 虚拟时钟，由两个 Dart 测试调用 |
| 「基线待锁定」 | `f9804fa9`；且 harness 的页面模型是 DSH 0.2.1-rc.4，需先校准 |
| 「M4 修改 `dsh_web_phone.css`」 | 该文件上游也在改，只能动插件 slot 段 |
| 「`fitToWidth` 只扫前 1200 节点、无条件 wrap flex、Grid≥3 转单列」 | 全部属实（`:715`、`:724`、`:728-729`），本文 §2.2 已收录 |
| M1–M7 七个模块 | 收敛为 4 个 PR；M6（插件声明协议）移出范围 |
