# 插件 Slot 与插件岛的移动端适配

实现基线：`428613ea`；后续工作树补齐策略、共享控制器、增量调度、可逆布局、Web Slot 集成和设备测试入口。实际验收结果见 [测试记录](../testing/plugin-mobile-adaptation.md)，不要把实现清单当作全部设备已经通过。

## 支持范围

- L0：边界约束；L1：可达、可填写、可保存、可返回；L2：按已知组件语义重排。
- 原生 Schema 表单复用现有滚动、编辑 sheet 和保存条；插件 Seat 页面、配置岛都可按包选择布局。
- page/panel/overlay 共用适配器；probe 不启动适配器。WebView 池、安全 gate、导航白名单和 renderer fallback 沿用现有实现。
- 未识别的组件不重排；超宽内容使用原有边界层及横向滚动。iframe、Shadow DOM、Canvas 内容依赖插件自身适配；不跨源、不读取其内部。
- 不承诺任意第三方 React 插件达到 L2。官方 Web 的 body portal 无可靠归属时不接管；App 岛只处理现有隔离机制确认的 layer。

## 策略与开关

`MobilePolicyResolver` 只影响展示，不改变 Seat Planner 的可用性判断或 bridge 权限。

优先级：全局关闭／probe > 用户按包覆盖 > 有效插件声明 > 已知语义规则。默认 automatic 仅转换识别成功的组件；其余维持边界兼容。

| 模式 | 行为 |
|---|---|
| automatic | 六类语义规则 + 超宽滚动标记 |
| pluginResponsive | 插件自己响应式；仅保留边界兼容 |
| compatibility | 关闭组件变换，保留边界兼容 |
| desktop | 不做组件变换或超宽标记；用户关闭时停止控制器，DOM 声明则保留策略观察；岛自身隔离边界仍在 |

设置 › 界面 › 网页版 › 插件手机布局是总开关。插件独立页面和自带配置岛的右上角菜单提供按包覆盖与恢复默认。关闭「网页版手机布局」同时停止官方 Web Slot 控制器；它不改变 App 插件岛的设置。

偏好键为 `mobile_adapter_enabled` 与 `plugin_mobile_modes`，不存用户输入。全局关闭优先于单包选择；未知旧偏好被忽略。多个插件共用一个无逐条 DOM 标记的 list slot，且策略冲突或包归属不明时，整个 slot 保守使用 compatibility。

## 共享 Web 控制器

两宿主均加载 `app/assets/islands/mobile_layout.js` 和 `island_mobile_components.css`。App 通过 `IslandAssets` 注入，并按 ready 的 slot／已隔离 layer 建立控制器；官方网页通过独立 `app/assets/web/plugin_mobile.js` 管理允许的插件 slot。官方手机布局脚本无需改写。

| 规则 ID | 识别与变换 |
|---|---|
| form.stack | flex 的 label + 文本类控件纵排；不把 checkbox/radio/hidden/range/color 当普通表单行 |
| toolbar.wrap | 全为按钮的工具栏换行；排除 nav、navigation、tablist |
| card-grid.collapse | 已确认的卡片 grid 收为一列；排除 table、ARIA grid/row/gridcell |
| table.scroll | 宽记录表保留行列，在表格自身的有限宽 scrollport 横滚 |
| dialog.fit | 明确 dialog 限宽限高；横屏高度不足也处理，内容滚动、头尾可达 |
| tab.scroll | 标签条横滚，只移动标签条自身，不滚动整页 |

统一默认断点 480 CSS px，按可用容器宽度判断。显式声明可覆盖断点及规则，协议见 [RFC](plugin-mobile-policy-rfc.md) 和 [Schema](plugin-mobile-policy.schema.json)。不向旧 package.json manifest 填加未支持字段。

调度使用 TreeWalker、去重集合和 requestAnimationFrame，每批最多 200 个节点，扫描按 8ms 预算让出主线程；先批量读取，再统一写入标记，标签条几何读取与滚动写入也分开；后续节点不会因为超过 1200 而永久漏掉。MutationObserver 只观察插件 root 内的新增子树及相关属性；ResizeObserver 响应容器变化。适配器自有属性不触发自身 mutation 回环；页面隐藏时暂停调度。

变宽时按容器宽度撤销 compact；语义识别保留已有角色，不依赖已被 CSS 改过的 flexDirection 或 grid 列数。移除节点会释放引用；stop 断开 observer、取消待执行帧、撤销自己持有的属性并恢复原值。暂停的温热岛不会因后台 DOM 更新重新启动适配器。

## 交互与降级

- 四边安全区从 Seat 经 IslandHandle 的初始配置和动态更新传至 JS/CSS，避免只算 inset 而没有消费。
- 焦点和 visualViewport 变化时，仅在控件被遮挡后确保可见；字号遵循 WebView text zoom。
- 页面返回优先处理输入焦点与插件自己支持的 cancel/Escape，再返回原生页面。不移除插件弹窗 DOM，不替插件接受确认操作。
- 规则异常清理当前适配并进入 compatibility；诊断只有稳定原因码，不记录异常文本。
- 内存预算、主机不可达、renderer crash 继续沿用 IslandHost fallback，不新开无限 WebView。
- `window.__dshGoIsland.mobileDiagnostics()`、`window.__dshGoPluginMobile.inspect()` 输出模式、宽度、规则计数、队列、访问节点数和批次耗时，不包含输入、聊天或凭据。

## 验证与回滚

Flutter widget/golden 验证原生表单、窄屏入口、四边 inset 传输、池与 hit region；Node harness 验证同一文档的宽度恢复、动态节点、模式切换与节点预算。真实布局使用 [独立 Android WebView 测试工程](../../tooling/mobile-webview-tests/README.md)，记录资源 SHA-256，避免旧安装包假绿。

CI 包含 Flutter、Node 和 Android 11/15 emulator job，并汇入 CI OK。设备测试使用独立包名和合成 fixture；图像仅由测试 WebView 自己绘制，不捕获设备屏幕。

立即回滚：关闭全局开关，或将受影响包设为 compatibility/desktop。控制器停止并清理标记；不删除插件数据或用户配置。代码回退时，回退本次相关源码及同步生成物；不要清空 SharedPreferences 或重装删除用户数据。
