# 引擎层（engine bundle）

> 状态：阶段 1（构建侧）已实现（2026-11-03）。目标：把 DSH 引擎从 rootfs 里拆出来，按版本下载、
> 校验、切换，同时保住补丁与桥接的安全边界。

## 现状

- `runtime/build-runtime.sh` 把 **Ubuntu base + Node + Python + pnpm + DSH npm 树（已打补丁）+ dsh-go-bridge** 打成一个
  `rootfs-<abi>.tar.xz`（约 116 MB），`runtimeVersion` 是所有输入（含补丁、桥接）的哈希。
- App 首次启动整包下载（OPT-02），解包到 `files/runtime/rootfs`，用 `/usr/local/bin/dsh` 启动。
- 结果：**换一个 DSH 版本 = 重新发一个 116 MB 的 runtime**；用户无法自选版本。

## 目标

1. rootfs 只带系统层（Ubuntu base、Node、Python、pnpm、proot 库等），约 **90 MB**（xz；实测 r15
   全量 123 MB，引擎包 34 MB）。
2. DSH 引擎单独打 `engine-<dshVersion>-<patchSet>.tar.xz`（约 **34 MB**），内含：
   - `opt/dsh`（打了该版本补丁的 npm 树）
   - `engine.json`：`dshVersion`、`patchSet`（补丁集哈希）、`bridgeVersion`、布局与挂载点
   - App 侧的 `/opt/dsh-go`（bridge、boot cache、direct 加载器，约 1–2 MB）留在 rootfs：
     它与 App 版本绑定、与 DSH 版本无关，每次换引擎重复下载没有意义。
3. App 维护 `files/runtime/engines/<engineId>/`，可并存多个版本；默认用内置/最近一次成功启动的版本，失败可回滚。
4. manifest 增加 `engine` 段（`id`、`dshVersion`、`patchSet`、`url`、`sha256`、`bytes`）；`rootfs` 段不再含 DSH。

## 安全约束（不可妥协）

- **只允许发布过的引擎版本**：补丁里的 `route-guard` 是所有 HTTP/WS 路由的鉴权边界，
  `dsh-go-bridge` 是 App 的协议层。未打补丁的任意 npm 版本不能作为"可安装版本"。
- 引擎包在**构建机**上打完补丁再打包，设备端只做哈希校验与解包，不执行任何打补丁逻辑。
- 每个引擎包带 `patchSet` 哈希，App 只接受与 manifest 中 `patchSet` 一致的包；
  版本列表由我们发布（GitHub Release / 自有主机），不是 npm 任意版本。

## 构建侧改动

`runtime/build-runtime.sh`（阶段 1 已实现）：

1. `ENGINE_BUNDLE=1` 时，`assemble` 在写完 rootfs 后，从**同一份已审计的 entries** 里切出
   `opt/dsh`，加 `engine.json`，按与 rootfs 相同的规则（排序、uid/gid 0、固定 mtime）
   打成 `engine-<abi>.tar.xz`。两份归档不会因为各走一套打包逻辑而漂移。
2. `WITHOUT_DSH=1`（需配合 `ENGINE_BUNDLE=1`）把 `opt/dsh` 从 rootfs 里移除；`/usr/local/bin/dsh`、
   `/opt/dsh-go`、`/etc/dsh-app/dsh-web.patch.yml` 等启动胶水仍留在 rootfs，指向 `/opt/dsh`，
   由设备侧挂载补齐。
3. `engine.json`：`id`、`dshVersion`、`nodeVersion`、`bridgeVersion`、`patchSet`、`abi`、
   `root`/`bridge`/`boot`/`direct` 路径。`patchSet` 是补丁文件 + bridge + boot + direct 源码的哈希。
4. manifest 增加 `engine` 段（`id`/`archive`/`archiveSha256`/`archiveBytes`/`patchSet`/`url`/
   `rootfsIncludesDsh`）和顶层的 `rootfsVersion`（只由系统层输入算出：base、Node、Python、
   pnpm、deb、proot、`rtbuild.py`、bridge/boot/direct；不含 DSH 版本、补丁和 `RUNTIME_REVISION`）。
   App 用 `rootfsVersion` 判断要不要重下 rootfs，用 `engine.id` 判断要不要换引擎。
   `publish-runtime.sh` 会把引擎包一起上传到同一个 Release，`set-runtime-url.sh` 回填 `engine.url`。
5. runtimeVersion 仍是整体输入哈希（`ENGINE_BUNDLE` / `WITHOUT_DSH` 也进哈希）；
   引擎包 id 用 `engine-<dshVersion>-<patchSet 前 12 位>`。

## 设备侧改动

- `RuntimeInstaller`：rootfs 安装不变；引擎安装新增 `EngineInstaller`：
  - 下载（Range 续传、sha256、失败保留 `.part`）→ 解包到 `files/runtime/engines/<id>`（先 staging 再 rename）；
  - 记录 `engines/current` → `<id>`；启动前校验 `engine.json` 的 `id`/`patchSet`/`dshVersion`
    与 manifest 一致；只保留当前 + 上一个版本（回滚用）。
  - 与 rootfs 共用一条进度条：rootfs 占 `rootfsBytes`，引擎占 `archiveBytes`
    （离线包同理）。rootfs 已装好、只差引擎时，重试只补引擎。
- `DshProcess`：启动命令仍是 `/usr/local/bin/dsh`，只在 proot argv 里加一条绑定：
  `-b <engine>/opt/dsh:/opt/dsh`（`/opt/dsh-go` 留在 rootfs，不参与绑定）。这样 launch 模板、
  boot cache 预加载、`--patch` 路径和现有补丁都不需要改。
  Direct 模式用同一份 `mounts` 映射 `/opt/dsh` 的 ripgrep 与入口路径。
- 失败回滚：启动连续失败（现有 `自动进入安全模式` 逻辑）→ 回退到上一个引擎 / 内置引擎。
- UI：设置 → 本机引擎 → "引擎版本"：列出内置版本 + 已下载版本 + 可下载版本（manifest 提供），
  下载进度复用 runtime 的进度组件（阶段 3）。

## 迁移

- manifest 的 `engine.rootfsIncludesDsh` 为 true 时（老 runtime 把 DSH 放在 rootfs 里），
  App 直接把 `rootfs/opt/dsh` 移动成 `engines/<id>`（不重新下载），并校验 DSH `package.json`
  的版本；patchSet 不一致的旧树不会被顶替成新引擎。
- 已发布 r15 runtime 继续可用（离线包仍内置整套）；新的拆分从 r16 起生效。

## 验收

1. 干净安装：下载 rootfs（~90 MB）+ 引擎（~34 MB）→ 启动对话。
2. 只升级引擎：换一个新版本引擎包（不换 rootfs）→ 启动、对话正常；旧版本保留可回滚。
3. 引擎包 sha256 或 patchSet 不匹配 → 拒绝安装并给出明确错误。
4. 无网 + 离线包：内置 rootfs + 内置引擎直接可用。
5. `runtime/test-host.sh` 对拆分后的产物继续通过（route guard / 原生插件 / v1.2）。

## 工作量

| 阶段 | 内容 | 预估 |
|---|---|---|
| 1 | 构建侧：`--without-dsh`、engine 打包、manifest 字段 | 1 天 |
| 2 | 设备侧：EngineInstaller + 启动切换 + 迁移 | 1.5–2 天 |
| 3 | UI：版本列表、下载、回滚 | 1 天 |
| 4 | 测试与真机验收 | 0.5–1 天 |

首个可交付里程碑：阶段 1 + 阶段 2（能下载并切换一个引擎版本），UI 先用现有引擎页的下载入口。
