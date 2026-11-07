# 插件移动布局声明 v1

状态：实现。此协议只描述 DOM 布局，不扩展 package.json 的 dshGo manifest，也不增加 bridge 权限。

插件可在其顶层组件上设置 `data-dsh-mobile-policy`，值为 JSON：

```html
<section data-dsh-mobile-policy='{"v":1,"mode":"pluginResponsive"}'>…</section>
```

Schema：同目录的 `plugin-mobile-policy.schema.json`。支持 automatic、pluginResponsive、compatibility、desktop；可选 compact（240–1024 CSS px）和六条规则的布尔开关。未知字段忽略；不支持的版本或无效的已知字段降级为 compatibility，插件仍可使用。未声明的旧插件继续保守语义识别；未知组件不重排。控制器根节点有声明时，以根节点为准；否则要求后代中只有一个声明，多个后代声明时保守降级，避免猜测生效策略。

优先级：全局关闭／probe > 用户按包覆盖 > 有效 DOM 声明 > 保守语义识别。用户可以覆盖插件自称响应式的声明。声明只作用于已有隔离边界内，不跨 iframe、Shadow DOM，不触碰官方导航和 modal。异常和诊断不收集声明原文或输入值。

官方 list slot 无逐条 DOM 标记。同一 slot 多包的用户模式不一致时，全 slot 使用 compatibility；有显式覆盖但无法确认包所有者时同样使用 compatibility。该限制不通过猜测 DOM 归属或提升权限解决。

迁移无需修改旧插件。插件作者确认自身响应式后再声明 pluginResponsive；需宿主帮助时用 automatic，按规则禁用不适合的变换。撤销声明可直接删除属性，用户设置和插件业务数据不变。
