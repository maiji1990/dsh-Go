# 插件界面的统一自动适配（AUTO-ADAPT）

> 2026-10-31。App 端已实现 P0–P2：不写插件代码也能把大多数插件配置画成原生表单；画不了的部分用原始 JSON；插件自带网页界面时优先开网页岛；入口标出「原生 / 网页 / 只读」。

## 0. 原则

一个插件入口只回答一个问题：**这台主机、这个插件，现在能用什么方式显示？** 顺序固定，后面的只在前面的做不到时出现：

1. 插件声明的 `dshGo` 原生视图；
2. schema 原生表单（含新增的可空数组 / 对象字典）；
3. 原始 JSON 编辑器（能写但不能画成控件）；
4. 插件自己的网页界面（`autoGenerate: false` 且网页岛可用）；
5. 「在网页版打开」；
6. 只读说明。

第 1、5、6 步是已有能力；本设计补齐 2、3、4，并让配置入口与插件座位共用同一套判断。

## 1. P0：配置入口不再绕过统一决策

`PluginConfigPage` 在读到表单后先问：插件是否声明了自己的页面（`autoGenerate: false`）？如果是，并且主机网页可达、桥接占用表里这个包有 `settings.section` 座位、座位计划给出网页岛，就直接用 `PluginSeat` 打开那个岛，而不是画半成品表单。

- 岛可用：用户看到插件作者写的完整界面（账号、模型、看板）。
- 岛不可用（中转、没有占用表、预算不够）：退回原生表单，顶部仍有「在网页版设置」。
- 这是纯客户端判断，不需要插件改代码；判断代码在 `app/lib/ui/plugins/config/plugin_config_page.dart`。

## 2. P0：画不了的 JSON 用原始 JSON 编辑器

`SchemaFieldView` 不再把所有 `FieldKind.readOnly` 都变成「在网页版编辑此项」。如果字段是可写的普通 JSON 形状（对象、数组、字典、`any`、全部成员都是 JSON 形状的联合），行上显示当前摘要，点开是一个 JSON 编辑器，保存仍走原来的 `controller.set`，主机照常校验。

- 含写-only 密钥（`role('secret')`）的子树永远不显示原始值，继续走网页。
- 函数、类实例、`never`、标量联合（如 `number | const`）仍走网页：它们不是 JSON。
- 代码：`rawJsonEditable` / `containsSecret`（`schema_fields.dart`），编辑器复用 `showSequenceFieldSheet` 的 `any` 分支。

## 3. P1：表单引擎补齐三类结构

`schema_fields.dart` 的 `kindOf` 先剥掉「可空单成员联合」`union([const(null), X])`（`structuralNode`），于是：

| schema | 以前 | 现在 |
|---|---|---|
| `union(null, array(object))` | 只读 | `objectList`，逐项表单 |
| `dict(union(null, object))` | 只读 | `objectDict`，键 → 对象表单 |
| `union(object, object)` | 只读 | 原始 JSON |

对象列表里的 `role('credential-ref')` 字段照常显示（它是引用名，不是密钥），可以新建账号条目；密钥值仍由凭据服务写入。

## 4. P2：入口标注与诊断

`pluginConfigMode`（`plugin_config_entries.dart`）把每个配置入口归为：

- `native`：原生表单（含原始 JSON）；
- `web`：插件自带网页界面；
- `readOnly`：主机不允许写，或表单未启动。

设置在 设置 › 引擎参数 › 插件、插件详情、插件清单三处显示对应的词；插件详情新增「适配情况」卡片，逐个配置入口给出模式和一句说明。排查「为什么这个插件没有原生 UI」时先看这里。

## 4.1 网页岛的手机宽度适配

插件自带的 React 页面按桌面宽度写死时，直接放进岛里会挤成一条或横向截断。岛在加载后做三件事（`assets/islands/island.js` + `island_mobile.css`）：

- 补 `viewport`：`width=device-width, initial-scale=1, viewport-fit=cover`；
- 配色：把 App 的玻璃表面（卡片、控件、描边）填进 DSH 的 `--dsw-alias-*` token，网页卡片与原生设置卡同色，并随 App 深浅色/主题色切换；
- App 风格层：识别插件的大卡片，换成 App 的卡片填充/描边/14px 圆角；只把 9–14px 的正文文字上调 10%（最多 15px），图标、代码、胶囊不碰；
- 通用宽度约束：固定宽容器/卡片/表格不超过框宽，长词断行，图片/视频自适应，输入框 16px 防聚焦放大；
- 运行时布局适配：行内 flex 允许换行；多列 grid 在超宽时堆成一列；仍宽于框的子树加横向滚动，而不是被裁掉。

不改插件源码，也不改 DSH Web 自身的布局；只作用于 `[data-dsh-go-island~="frame"]` 内部。

### 4.2 官方网页版里的插件页面

网页模式下，插件的自带页面（`settings.section`、`plugins.*` 等 slot）仍然由 DSH Web 直接渲染，
岛的样式不在这里。`assets/web/dsh_web_phone.css` 在手机布局（`html[data-dsh-app-layout="drawer"]`）
下对**插件 slot 内部**补同一套“能放下、能滚动”的通用约束：宽容器/卡片/表格不超过面板宽度、
长词断行、图片/视频自适应、`pre/code` 换行、输入框 16px 防聚焦放大、表格自己在内部横向滚动。

它不碰 DSH 自己的组件（只选 `[data-slot="settings.section"]`、`[data-slot="settings.general.item"]`、
`[data-slot^="plugins."]` 的后代），也不改颜色、字号和间距；插件的设计仍然按它自己的样子显示，
只是不再把手机屏幕撑破。设置 → 界面 → 网页版 → 手机布局 关掉时这一层和外壳适配一起停用。

## 5. 边界

- 任意 React / Canvas 界面不能自动变原生；第 4 步的网页岛是通用兜底，不是原生转换。
- 网页岛依赖主机网页可达；中转连接没有网页时只能原生表单或网页链接。
- 原始 JSON 不绕过主机校验；校验失败仍标在字段上。
- `role('secret')` 的裸密钥仍不可写，需要插件绑定凭据（原有约束）。
