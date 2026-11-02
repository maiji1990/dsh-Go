# dsh-Go 优化清单

> 审查对象：`maiji1990/dsh-Go`（`ccch1mneyyy/dsh-Go` 的 fork）
> 基线 commit：`3276e9a`（2026-11-02，「merge: 重放本地改动到上游 e685259」）
> 上游对照：`e685259`（2026-11-02，「Merge pull request #319 from ccch1mneyyy/feat/attachment-streaming-upload」）；上游曾重写历史（旧的 `1207c11` 不在其祖先链里），本地以 e685259 为基线重放，`git rev-list --left-right --count origin/main...HEAD` = `1 0`（只多本次重放提交），**未落后**
> 实测 APK：2026-11-02 构建的 1.0.8 arm64 普通包 `versionCode=11021341`，32.5 MB，APK 内无 rootfs，`native-code` 只剩 `arm64-v8a`；离线包 `versionCode=11021351`，148.9 MB，内置 rootfs。两者都是 `compileSdk=36`、`targetSdk=28`
> 运行时版本：`r14-dsh0.2.1-rc.4-node24.22.1-ubuntu24.04.5-8502f4e42f76`（重放上游补丁后的 arm64 构建；已发布的 `…-53b4fe78516f` 对应重放前的版本，重建后需重新上传）
> 审查日期：2026-10-31（初版）、2026-11-01（基线刷新：1.0.4 → 1.0.8）、2026-11-02（OPT-02 首启下载）
> 状态：**进行中**（OPT-01/03/05/06/07/09 已完成，OPT-02 已发布待真机验证，OPT-04 决定保留）
> 当前进度：第 1 批（OPT-01/09）、第 2 批（OPT-05/06/07）、第 3 批（OPT-02 发布、OPT-03）已完成，OPT-04 决定保留；OPT-02 只剩真机验收；剩余第 4 批。
> **仓库策略（2026-11-02 确认）**：所有改动只保留在 `maiji1990/dsh-Go`；上游 `ccch1mneyyy/dsh-go` 只作为只读同步来源，**不向上游提交 PR、不推送分支**。发布（Release / runtime 归档）也落在 `maiji1990/dsh-Go`。

---

## 0. 结论摘要

工程质量整体很高：运行时全链路 sha256 固定、PRoot 有 `DT_NEEDED` 补丁与 16 KB 页对齐校验、签名三套、构建后回读 versionCode、测试密度少见。**不需要大改架构**，收益集中在两件事：**包体/分发**和**工程流水线**。

| 编号 | 事项 | 优先级 | 预估收益 | 工作量 |
|---|---|---|---|---|
| OPT-01 | arm64/x64 包混入其他 ABI（已修复） | P0 ✅ | −12.8 MB（实测） | 已完成 |
| OPT-02 | 158 MB rootfs 跟随每个版本分发（普通包改首启下载） | P0 ◐ | 普通包 34.1 MB；离线包 192.3 MB | 代码完成，发布/真机待验证 |
| OPT-03 | node_modules 里约 250 MB 可选功能常驻（inspector + Office 转换已移除） | P1 ✅ | rootfs −168.8 MiB（实测） | 已完成 |
| OPT-04 | WebRTC 原生库常驻（决定保留） | P1（保留） | 不裁剪（P2P 是卖点） | — |
| OPT-05 | 根目录 CI（analyze / test / 按需 build） | P1 ✅ | 回归前置 | 已完成 |
| OPT-06 | 985 张 golden 占 304 MB（已无损压缩到 164 MB） | P1 ◐ | 工作区 −140 MB；clone 需历史重写 | 部分完成 |
| OPT-07 | analyzer 严格项（strict-casts + 分目录规则） | P2 ✅ | 缺陷前置 | 已完成 |
| OPT-08 | Gradle 未开缓存/并行 | P2 | 构建时间 | 0.5 天 |
| OPT-09 | `stop_daemon` 会杀掉同机所有 Gradle daemon | P2 ✅ | 避免互相干扰 | 已完成 |
| OPT-10 | 构建分钟 versionCode 同分钟碰撞 | P2 | 低概率失败 | 0.5 天 |
| OPT-11 | 本机引擎走 `127.0.0.1` 的 loopback TCP（3080 起） | P1（安全） | 消除跨 App 可达面 | 2–3 天 |
| OPT-12 | 插件构建脚本的审批粒度与审计 | P2（安全加固） | 降低供应链残余风险 | 1–2 天 |
| OPT-13 | AGPL-3.0 §13 缺少网络服务的源码入口 | P2（合规） | 合规 | 0.5 天 |
| OPT-14 | fork 与上游的关系（改为重放式同步） | P2 ✅ | 合并成本可控 | 已完成 |
| OPT-15 | `targetSdk=28` 的长期风险 | 监控 | — | — |

---

## 1. 实测基线

### 1.1 APK 组成（arm64 1.0.8：普通包 34.1 MB / 离线包 192.3 MB，2026-11-02 构建）

| 内容 | 普通包（压缩后） | 离线包（压缩后） | 原始 | 说明 |
|---|---:|---:|---:|---|
| `assets/runtime/rootfs-arm64-v8a.tar.xz` | **0**（首启下载） | **158.3 MB** | 786.0 MB | 普通包只带 manifest + licenses |
| `lib/arm64-v8a/*` | 25.5 MB | 25.5 MB | 57.5 MB | Flutter 引擎 + libapp + proot + WebRTC + Conscrypt |
| `lib/x86_64/*` / `lib/armeabi-v7a/*` | 0 | 0 | 0 | OPT-01 已排除 |
| `libapp.so`（arm64） | 13.1 MB | 13.1 MB | 30.9 MB | Dart AOT |
| `classes.dex` | 3.5 MB | 3.5 MB | 8.5 MB | 已 R8 |
| `assets/`（业务） | 2.6 MB | 2.6 MB | 5.3 MB | 字体、技能、桌宠、Web |
| `res/` + `resources.arsc` | 0.9 MB | 0.9 MB | 1.0 MB | |
| **合计** | **34.1 MB** | **192.3 MB** | | |

普通包 `versionCode=11020700`，APK 内无 rootfs，首次启动按 `manifest.rootfsUrl` 下载 158,255,652 B 并按 sha256 校验；离线包 `versionCode=11020700`，rootfs 内置。两者 `native-code` 都只有 `arm64-v8a`。

### 1.2 rootfs 分层（786 MB）

| 路径 | 大小 | 文件数 |
|---|---:|---:|
| `/opt/dsh/node_modules` | **370.5 MB** | 14 560 |
| `/opt/node/bin`（Node 24） | 123.4 MB | 1 |
| `/usr/lib/aarch64-linux-gnu` | 96.2 MB | 1 418 |
| `/opt/python` | 78.3 MB | — |
| `/usr/bin` | 36.1 MB | 308 |
| `/usr/local/lib` | 20.6 MB | 440 |
| `/usr/share/perl` | 18.7 MB | 1 199 |
| `/usr/lib/git-core` | 11.0 MB | 49 |

`node_modules` 内部 Top：

| 包 | 大小 | 性质 |
|---|---:|---|
| `@deepseek-ai/libreoffice-kit-wasm` | **152.4 MB** | `soffice.wasm` 127.8 MB + `soffice.data` 23.0 MB，文档预览才用 |
| `sherpa-onnx-linux-arm64` | 40.3 MB | ONNX 运行时，语音输入才用 |
| `@deepseek-ai/dsh-experimental-inspector` | 23.5 MB | devtools / lighthouse |
| `@img/sharp-libvips-linux-arm64` | 18.3 MB | 图片处理 |
| `@deepseek-ai/dsh-client-ui-sidebar-documentpreview` | 14.7 MB | PDF/Excel 客户端 |
| `openai` / `@google/genai` / `@anthropic-ai/sdk` | 19.8 MB | 模型 SDK |
| `@vscode/ripgrep-linux-arm64` | 4.8 MB | 搜索 |

源码中 `*.map` 仅 2.3 MB、`*.md/test/docs` 17.3 MB——说明 `runtime/lib/rtbuild.py` 的裁剪（docs/man/locale/apt lists/dev metadata）已经在做，这部分不用重复建议。

### 1.3 仓库体积

| 项 | 大小 |
|---|---:|
| 本地克隆（含 `.git`） | 851 MB（跟踪文件 224 MB + `.git` 627 MB） |
| `app/test` | 191 MB（跟踪文件 174 MB；另含未跟踪的 `failures/` 调试图 17 MB） |
| `app/test` 中 golden PNG | 985 张 / 164 MB（git 跟踪的 `goldens/`） |
| `app/test/*_test.dart` | 742 个 |
| `site/home/dist`（构建产物入库） | 6.5 MB |

> 以上为干净克隆口径。本地工作目录另有未跟踪的 `app/build`（2.5 GB）、`app/.dart_tool`（591 MB）、`runtime/.work`（564 MB），不计入仓库体积。golden 无损压缩后工作区变小，但历史 blob 仍在，clone 体积没有下降。

### 1.4 复现命令

```bash
# APK 组成
unzip -l app.apk | awk '/lib\//{print $1, $4}' | sort -k2
unzip -l app.apk | grep rootfs

# ABI 列表
aapt2 dump badging app.apk | grep native-code
# -> native-code: 'arm64-v8a' 'armeabi-v7a' 'x86_64'

# rootfs 分层（先取出 xz）
unzip -p app.apk assets/runtime/rootfs-arm64-v8a.tar.xz > rootfs.tar.xz
python3 - <<'EOF'
import tarfile, lzma, collections
agg = collections.defaultdict(int)
with lzma.open('rootfs.tar.xz','rb') as f, tarfile.open(fileobj=f, mode='r|') as t:
    for m in t:
        if m.isfile():
            parts = m.name.lstrip('./').split('/')
            agg['/'.join(parts[:3])] += m.size
for k, v in sorted(agg.items(), key=lambda kv: -kv[1])[:20]:
    print(f"{v/1e6:8.1f} MB {k}")
EOF
```

### 1.5 原始数据

同目录下 `dsh-Go-优化清单-data/`：

| 文件 | 内容 |
|---|---|
| `rootfs-entries.tsv` | rootfs 全部 25 232 个文件及大小（制表符分隔；r12 数据，r14 为 25 235 个文件） |
| `rootfs-top.txt` | 顶层目录 / node_modules 包 / 单文件 Top 汇总 |
| `apk-badging.txt` | APK 包名、versionCode、targetSdk、native-code |

需要复查时可重新生成，命令见 1.4；APK 本身未入库（约 183.4 MiB / 192.3 MB），需要时从手机 `pm path` 拉取，或使用本地忽略目录里的 `app/build/app/outputs/flutter-apk/app-arm64-release.apk`。

> `rootfs-entries.tsv` 来自 r12；当前 r14 的未压缩总量只差 47 KB（786,038,299 vs 785,990,973），包级大小逐项一致，数据可直接沿用。

---

## 2. 问题清单

### OPT-01 arm64 包混入 x86_64 / armeabi-v7a 原生库

- **优先级**：P0　**状态**：**已完成**（2026-11-01，`6e617f9`）　**收益**：−12.8 MB（实测 205.2 → 192.3 MB）
- **证据**

  ```text
  $ aapt2 dump badging dsh104.apk | grep native-code
  native-code: 'arm64-v8a' 'armeabi-v7a' 'x86_64'
  ```

  `app/android/app/build.gradle.kts:49-58`：

  ```kotlin
  flavorDimensions += "abi"
  productFlavors {
      create("arm64") {
          dimension = "abi"
          ndk { abiFilters += "arm64-v8a" }   // ← += 是追加，不是覆盖
      }
      create("x64") {
          dimension = "abi"
          ndk { abiFilters += "x86_64" }
      }
  ```

  实际打包时 `lib/x86_64/libjingle_peerconnection_so.so`（16.1 MB）、`lib/x86_64/libconscrypt_jni.so`、`lib/armeabi-v7a/*` 仍然进了 arm64 包。

- **根因待确认**：`abiFilters +=` 只会追加；如果 Flutter Gradle 插件或某个 AAR 先把 3 个 ABI 写进 `abiFilters`，这里等于没过滤。需要先打印 `android.defaultConfig.ndk.abiFilters` 确认是谁写入的。

- **方案**
  1. 在 `androidComponents.onVariants` 里按 flavor 显式排除：

     ```kotlin
     androidComponents {
         onVariants(selector().withFlavor("abi" to "arm64")) { v ->
             v.packaging.jniLibs.excludes.addAll(
                 listOf("lib/x86_64/**", "lib/armeabi-v7a/**"))
         }
         onVariants(selector().withFlavor("abi" to "x64")) { v ->
             v.packaging.jniLibs.excludes.addAll(
                 listOf("lib/arm64-v8a/**", "lib/armeabi-v7a/**"))
         }
     }
     ```
  2. 在 `tooling/build-apk.sh` 回读 versionCode 的位置加断言，防止回归：

     ```bash
     abis=$("$aapt2" dump badging "$apk" | sed -n 's/^native-code: //p')
     case "$f:$abis" in
       arm64:*x86_64*|arm64:*armeabi-v7a*) die "arm64 APK 仍含其他 ABI: $abis" ;;
       x64:*arm64*|x64:*armeabi-v7a*)     die "x64 APK 仍含其他 ABI: $abis" ;;
     esac
     ```

- **验收**：`aapt2 dump badging app-arm64-release.apk | grep native-code` 只输出 `'arm64-v8a'`；APK 至少小 12 MB。
- **修复（2026-11-01，`6e617f9`）**
  - `app/android/app/build.gradle.kts` 新增 `androidComponents`：arm64 variant 排除 `lib/x86_64/**`、`lib/armeabi-v7a/**`；x64 variant 排除 `lib/arm64-v8a/**`、`lib/armeabi-v7a/**`。
  - `tooling/build-apk.sh` 用 `aapt2` 回读 `native-code`，flavor 与 ABI 不匹配时直接构建失败。
  - 实测 arm64 1.0.8 APK：205,159,980 → **192,315,655 B（192.3 MB）**，`native-code: 'arm64-v8a' 'fonts'`；x64 APK 35.4 MB，`native-code: 'fonts' 'x86_64'`。
  - 未再逐项打印 `defaultConfig.ndk.abiFilters` 追根因；以 variant 级 `packaging.jniLibs.excludes` 为准，两个 flavor 均已实测生效。

---

### OPT-02 158 MB rootfs 跟随每个版本分发

- **优先级**：P0　**状态**：**已发布，真机待验证**（2026-11-02：代码 `ee5c070` + `794f4b9`；归档已上传）
- **实现**：
  - 普通 `arm64` / `x64` flavor 只带 `assets/runtime/manifest.json` + licenses；`arm64Offline` / `x64Offline` 内置完整 rootfs。
  - `runtime/build-runtime.sh` 生成 `assets-manifest/`；manifest 新增 `rootfsUrl`（`RUNTIME_BASE_URL`）。
  - `RuntimeDownloader`：HTTP Range 断点续传、size/sha256 校验，失败保留 `.part`；安装成功后才删除归档。
  - `RuntimeInstaller`：优先用内置 asset（离线包），否则按 `rootfsUrl` 下载；旧 runtime 的下载目录会清理。
  - `tooling/build-apk.sh` 支持 `-f arm64-offline`；`runtime/set-runtime-url.sh` 可在上传后回填 URL。
- **实测**：普通 arm64 包 **34,062,810 B（34.1 MB）**，APK 内无 rootfs；离线包 **192,319,042 B（192.3 MB）**，含 rootfs；Kotlin 单测 7 个通过。
- **仍需完成**：
  1. ~~上传 rootfs 到 `manifest.rootfsUrl`~~ **已完成（2026-11-02）**：归档 `rootfs-arm64-v8a.tar.xz`（116.5 MiB）已上传到 `maiji1990/dsh-Go` 的 Release tag `runtime-r14-dsh0.2.1-rc.4-node24.22.1-ubuntu24.04.5-53b4fe78516f`（`--latest=false`），HTTP 200、122,149,248 B；`runtime/set-runtime-url.sh` 已回填 `rootfsUrl`，普通 arm64 包 `versionCode=11021341` 重新打出。x86_64 未发布，其 `rootfsUrl` 已置空（普通 x64 包请用离线 flavor）；
  2. 在真机/模拟器上验证首次下载、断点续传、失败重试、离线包，以及同 `runtimeVersion` 更新不重复下载；
  3. `release_assets.py` / 三条线路目前只发布 APK；runtime 归档按 `docs/release.md` 单独上传（本次即按该流程）。
- **验收**：正常网络首次安装能下载并启动；断网提示明确；同 `runtimeVersion` 更新不重复下载；离线包无网可用。
---

### OPT-03 node_modules 里约 250 MB 可选功能常驻

- **优先级**：P1　**状态**：**已完成**（2026-11-02：inspector 移除 + Office 转换移除；sherpa / sharp / documentpreview 按取舍保留）
- **证据**：见 1.2 表。`libreoffice-kit-wasm` + `sherpa-onnx` + `inspector` + `libvips` 合计 **234 MB**（原始），占 rootfs 30%。

- **已完成（2026-11-02）**
  - `runtime/lib/rtbuild.py` 新增「按包裁剪」：`DROPPED_PACKAGES` 在组装 rootfs 时丢弃并计入 build-report 的 `optional-bundle` / `office-conversion`；`DSH_RUNTIME_KEEP_OPTIONAL=1` 可全部保留（主机调试用）。
  - 新增 `runtime/patches/zzzzzzz-optional-bundles.diff`：从 `dsh-app-boot` 的 `OPTIONAL_BUNDLES` 去掉 inspector-profile，避免插件页出现「已列出但包不存在」的开关项。
  - 新增 `runtime/patches/zzzzzzzz-office-removal.diff`：从 `dsh-web-app/cordis.patch.yml` 去掉 `office-to-pdf` 插件条目（否则启动会加载已删除的包）。两个补丁都在 `pins.json` 里固定 before/after 哈希。
  - 裁剪的包：`dsh-experimental-inspector` / `-inspector-profile` / `-session-inspector`（桌面 CDP 调试，App 与网页版都不引用）、`dsh-office-to-pdf` / `libreoffice-kit` / `libreoffice-kit-wasm` / `dsh-skill-office`（Office→PDF 转换，产品决定移除）。
  - 实测（r14，arm64）：rootfs **749.6 → 580.8 MiB**（−168.8 MiB），归档 **151.0 → 116.5 MiB**（−34.5 MiB），文件 25 237 → 24 338；x86_64 归档 153.8 → 123.2 MiB。
  - 验证：`runtime/test-host.sh`（x86_64 + bwrap，NETWORK=0）全绿——`dsh web` 启动、token/登录、route guard 89/0、原生插件 167/0、v1.2 29/0；根因确认：`dsh-api-remotes` 的静态 import 只在未加载的 `lib/types/client/index.js` 里，运行时入口不受影响，文档预览的 Office 页签通过 `ctx.inject(["remote.officeToPdf"])` 自动不注册（PDF/文本预览不受影响）。

- **保留项与理由**
  1. `sherpa-onnx`（39 MB）：App 的听写（`app/lib/state/voice_model_state.dart`、`voice/voice_model_assets.dart`）直接用引擎的 voice-input bundle + SenseVoice；可后续把模型（241 MB，另存）改成按需下载，而不是删运行时。
  2. `sharp-libvips`（18 MB）：`sharp` 是媒体/图片路径的核心依赖。
  3. `sidebar-documentpreview`（15 MB）：`dsh-web-app` 直接依赖，网页版 PDF/文本预览要用。

- **验收**：`dsh web` 能启动、普通对话/工具调用可用；Office 文档不再提供转换/预览（产品取舍），PDF/文本预览正常。

---

### OPT-04 WebRTC 原生库常驻

- **优先级**：P1　**状态**：**已决定保留**（2026-11-02：P2P/远程直连是产品卖点，不裁剪）
- **证据**：`lib/arm64-v8a/libjingle_peerconnection_so.so` 12.3 MB（压缩 5.9 MB）、`lib/x86_64/…` 16.1 MB。只在 DSH Remote 的 P2P/TURN 直连路径使用。
- **结论**：`flutter_webrtc` 是编译期插件，不能做成运行时可选；移除等于砍掉 P2P 直连（`link_status.dart` 已有 `webrtcUnavailable` 降级，SSH/中继仍可用）。产品上保留，故本项不再推进；若以后要省这 13 MB，只能整体移除 P2P 路径或单独出「远程直连」安装包。

- **验收**：不适用（保留）。P2P 直连继续随包提供。

---

### OPT-05 根目录没有 CI（只有 Issue 模板）

- **优先级**：P1　**状态**：**已完成**（2026-11-01，`7ca81f8`）
- **实现**：新增 `.github/workflows/ci.yml`：
  - `analyze`：Flutter 3.47.7 + `flutter pub get` + `dart analyze --fatal-infos`；
  - `test`：4 分片跑完整 `flutter test`；字体固定用 Noto Sans CJK **2.004**（`Sans2.004` 的 OTC 包解到 `$RUNNER_TEMP/noto-cjk`，`DSH_TEST_FONT_DIR` 指向它），避免发行版字体的字形差异让 golden 失败；
  - `build`：仅在 main 或手动触发，缓存 `runtime/out`，构建 **arm64-offline** 并上传 APK（普通包依赖已发布 runtime URL，CI 没有），避免 PR 被 158 MB 运行时和 Android SDK/NDK 拖慢。
- **验收**：PR 上 analyze / test 必过；build 在 main / workflow_dispatch 产出 artifact。`market/.github/workflows/` 保持独立。
---

### OPT-06 985 张 golden 占 304 MB

- **优先级**：P1　**状态**：**已完成（在 e685259 新基线上重压）**（`0a02d5d` 初版；2026-11-02 `72d34e0` 重做）
- **证据（修复前）**：`app/test` 331 MB（跟踪文件 314 MB），其中 git 跟踪的 golden PNG 985 张共 304 MB；单张最大 1.48 MB（`app/test/shell/adaptive/goldens/wide_panel_dark.png`）。
- **已完成**：
  - `tooling/optimize-goldens.sh` 用 `oxipng -o4 --strip safe` 只重压编码；像素不变，`matchesGoldenFile` 继续通过。
  - 2026-11-02 在 resync 后的基线上重做：995 张 **310 MB → 167 MB**（`app/test` 337 → 194 MB）。
  - 其中 20 张（share 卡片 14 + plugin_config 4 + engine_settings 2）先按当前代码用 `flutter test --update-goldens` 重新生成：share 的二维码跟着更新源指向 `maiji1990/dsh-Go`，plugin_config/engine_settings 是 fork 的插件适配文案。
  - 压缩后回归：Linux（WSL）上 share/plugin_config/engine_settings/access 的 golden 全部通过。
- **未完成 / 注意**：
  - Git 历史里的旧 blob 仍在，`.git` 只增不减；要真正缩小 clone，需要重写历史或迁移 LFS（破坏性/协作成本高，需单独决定）。
  - 进一步减少尺寸矩阵、删除不必要的 golden 仍可做，但会改变覆盖率；`site/home/dist`（6.5 MB）仍是构建产物入库。
- **验收**：工作区 golden 995 张 / 167 MB；clone 目标 < 250 MB 仍需历史处理。
---

### OPT-07 `analysis_options.yaml` 只有 `flutter_lints`

- **优先级**：P2　**状态**：**已完成（分目录推进）**（2026-11-01，`25cacf6`）
- **实现**：
  - `app/analysis_options.yaml`：`strict-casts` + `unawaited_futures` + `prefer_final_locals`。
  - `app/lib/state/analysis_options.yaml`、`app/lib/runtime/analysis_options.yaml`：追加 `strict-inference`、`strict-raw-types`、`avoid_dynamic_calls`、`avoid_slow_async_io`（include 根配置）。
  - 修复全仓告警/错误；`dart analyze --fatal-infos` 通过，CI analyze 使用同一命令。
  - 同步更新 `objectDict` 的 market schema 测试期望与 2 张 drawer golden。
- **后续（未在本批）**：把 `strict-inference` / `strict-raw-types` / `avoid_dynamic_calls` 推到其余 `lib/` 与 `test/`；packages 已有 strict-casts/raw-types，但 `dart analyze` 仍受测试 fake 的 override 警告影响，需单独清理。
- **验收**：新增代码不得新增告警；CI analyze 通过。
---

### OPT-08 Gradle 未开缓存/并行

- **优先级**：P2　**状态**：待处理
- **证据**：`app/android/gradle.properties` 只有 jvmargs 与两个模板开关。
- **方案**

  ```properties
  org.gradle.parallel=true
  org.gradle.caching=true
  org.gradle.configuration-cache=true   # 需先验证 Flutter 插件兼容性
  org.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=1g
  ```

  `configuration-cache` 与 Flutter Gradle 插件历史上偶有冲突，建议先在本地跑 `./gradlew :app:assembleArm64Release --configuration-cache` 验证。
- **验收**：干净构建时间下降；缓存命中时二次构建明显加快。

---

### OPT-09 `stop_daemon` 会杀掉同机所有 Gradle daemon

- **优先级**：P2　**状态**：**已完成**（2026-11-01，`6e617f9`）
- **证据**：修复前 `tooling/build-apk.sh:129-134`

  ```bash
  stop_daemon() {
    ((keep_daemon)) && return 0
    (cd "$APP/android" && ./gradlew --stop -q >/dev/null 2>&1) || true
    local pids
    pids=$(pgrep -f '[G]radleDaemon' || true)   # ← 无差别匹配
    [[ -n $pids ]] && kill $pids 2>/dev/null || true
  }
  ```

- **影响**：同一台机器上其他 Android 项目的构建会被打断（`--keep-daemon` 的注释也提到这个场景）。
- **方案**：删掉 `pgrep` 两行，保留 `./gradlew --stop`（它只停本项目的 daemon）；若确实要强制，按 `-Dorg.gradle.appname` 或项目路径过滤。
- **验收**：在本机另一个项目构建期间执行 `build-apk.sh`，对方的 daemon 不受影响。
- **修复（2026-11-01，`6e617f9`）**：`stop_daemon` 只保留 `(cd "$APP/android" && ./gradlew --stop -q ...)`；`pgrep` / `kill` 两行已删除。`build-apk.sh --dry-run -f all` 仍只打印 `gradlew --stop`。

---

### OPT-10 构建分钟 versionCode 同分钟碰撞

- **优先级**：P2　**状态**：待处理（记录）
- **现状**：`tooling/build_number.py` 用 `(year-2026)*1e8 + MMDDhhmm`，设计合理（跨月跨年单调、固定 UTC+8、有上限 2046 年），文档也写清了。
- **风险**：同一分钟内两次构建得到同一个 versionCode；同一台机器并行出 arm64/x64 是刻意的，但两个 CI job 或两个人同时构建会撞号，安装时只会「无变化」。
- **方案**：保持现状亦可；若要更稳，CI 用 `--build-number` 传入 `max(build_minute, previous+1)`，或在 `build-apk.sh` 里检测 `dist/` 已有同名/同号产物时提示。
- **验收**：`tooling/test_build_number.py` 增加一个「同分钟重复调用」用例。

---

### OPT-11 本机引擎走 `127.0.0.1` 的 loopback TCP（安全）

- **优先级**：P1　**状态**：待处理
- **证据**：PRoot 启动参数仍是 `dsh web --host 127.0.0.1 --port {PORT} --no-open`；端口由 App 在 **3080–3100** 间挑空闲值（`DshProcess.kt` 的 `DEFAULT_PORT = 3080` + `pickPort()`），不是固定 3080。未授权请求返回 `401 dsh web authentication required`。token 从 stdout 解析，写入日志前会经 `CrashClassifier.redact` 脱敏；但它仍出现在 WebView 加载的 `?token=` URL 和插件 island 的启动地址里。
- **风险**：Android 上 loopback 不隔离，设备上任意 App 都能连 `127.0.0.1:{port}`，token 成为唯一屏障；token 若出现在 URL/剪贴板/日志/分享链接里，等于失守。
- **方案**
  1. 首选：改为 **Unix domain socket**（放 app 私有目录，如 `files/run/dsh.sock`），WebView/OkHttp 走 UDS；跨 App 直接不可达，token 只作为纵深防御。
  2. 短期不改的话，确认：token 每次启动随机生成、只存内存/私有文件、日志已脱敏、不出现在 `--patch` 配置文件与分享链接中；`--host` 不提供 `0.0.0.0` 选项。
- **验收**：用另一个 App（或 `adb shell` 下的 toybox `nc`）连当前选中的 loopback 端口无法获得任何未授权数据；UDS 方案下不再监听任何 TCP 端口。

---

### OPT-12 插件构建脚本的审批粒度与审计（安全，残余风险）

- **优先级**：P2（安全加固）　**状态**：待处理（2026-11-01 重写；初版“pnpm 默认执行 postinstall”的前提已不成立）
- **现状（已验证）**
  1. 运行时是 **pnpm 11.28.5**（`runtime/locks/pnpm-11.28.5`）。pnpm 11 的 `strictDepBuilds` 默认开启：依赖的生命周期脚本默认不执行，非交互安装遇到被跳过的脚本会直接报 `Ignored build scripts: …` 并失败，而不是静默执行。
  2. 内置的 `@deepseek-ai/dsh-plugin-manager` 0.2.1-rc.4 已实现「阻断 → 记录 → 批准」闭环：
     - 把 `ERR_PNPM_IGNORED_BUILDS` / `Ignored build scripts:` 归为 `build-blocked`；
     - 把待批包名以 `allowBuilds: <name>: set this to true or false` 写进 profile 的 `pnpm-workspace.yaml`，只接受精确包名（通配符被排除）；
     - 安装请求可带 `approvedBuilds`，只放行仍在待批列表里的名字，其余以 `stale-approval` 拒绝。
  3. App 侧已有对应交互：`plugins_state.dart:1081` 把 `build-blocked` 映射为待批列表（`:951` 的 `canApproveBuilds`），`plugin_actions.dart:211` 的 `approveBuildsAndRetry()` 弹确认框后带 `approvedBuilds` 重试。
  4. 官方市场把 `install-scripts` 标为 high risk（`market_sidecar.dart:23`），详情页会显示风险说明。
- **残余风险**
  1. 批准弹窗只列**包名**（`plugin_actions.dart:240-256`），不展示将执行的 `preinstall/install/postinstall` 正文；用户只能盲批。
  2. 批准按**包名**持久化，不绑定版本或 integrity。已批准的包升级到新版本后，新脚本会直接执行，不再询问。
  3. 没有「插件名 + 版本 + integrity + 批准时间」的审计清单，出问题难以追溯。
  4. 脚本在 guest 内以 root 运行，可读写 `/root/.dsh` 里的凭据和配置；批准等于完全信任该包。
  5. 对确实需要构建的插件，一刀切 `--ignore-scripts` 会让插件不可用，不能作为默认策略。
- **方案**
  1. 弹窗展示脚本正文：安装失败时由 plugin-manager 返回每个待批包的 `preinstall/install/postinstall` 文本（可从 registry manifest 或本地 store 的 `package.json` 读取），至少给出命令、来源 registry 和包版本。
  2. 把批准从「包名」升级为「包名@版本 + integrity」：版本或 integrity 变化时重新进入待批。实现上可在 pnpm 的 `allowBuilds` 之外维护一份 DSH 自己的批准表，或在升级流程里主动清除该包的批准。
  3. 增加插件审计页：包名、版本、integrity、批准时间、批准时的脚本摘要，可导出。
  4. 批准后仍以最小环境执行脚本：清除 `DEEPSEEK_API_KEY`、web token 等敏感环境变量，限制可写目录，避免构建脚本直接接触 `/root/.dsh`。
  5. 对 `sdk/plugin-kit` 已知包预置白名单（固定 integrity），其余保持逐次批准。
  6. 保留 `runtime/build-runtime.sh:240` 对**受信、锁版本**依赖的 `--ignore-scripts`；那是构建期供应链，不要把它推广成运行时的一刀切。
- **验收**
  - 带 `postinstall` 的测试插件安装后进入 `build-blocked`，脚本不执行；批准后能正常构建。
  - 已批准包升级到新版本时会重新询问（方案 2 落地后）。
  - 弹窗能看到实际脚本内容；审计页能列出已批准插件。
  - `pnpm-workspace.yaml` 中不出现通配符批准。

---

### OPT-13 AGPL-3.0 §13 缺少网络服务的源码入口（合规）

- **优先级**：P2　**状态**：待处理
- **证据**：`services/share-worker` 是 `go.dshtui.com` 上对外提供下载/分享页的服务；`src/download-page.ts` 只有线路选择（国内/海外/GitHub），没有源码入口。
- **要求**：AGPL §13 要求通过网络与程序交互的用户能获得对应源码。仓库公开并不自动满足「从运行中的服务提供」。
- **方案**：在下载页/分享页页脚加 `Source code (AGPL-3.0)` 链接（指向本仓库对应 tag），App 的「关于」页同样加一条；`market/` 与 `site/` 若有服务端逻辑同理。
- **验收**：`curl https://go.dshtui.com/download | grep -i source` 能命中；链接指向与运行版本一致的 tag。

---

### OPT-14 fork 与上游的关系

- **优先级**：P2　**状态**：**已完成（改为重放式同步）**（2026-11-02，`3276e9a`）
- **现状**：GitHub 上的 `main` 曾被同步成上游，本地历史与上游在 `08d76f5` 之后分叉（上游重写过历史，旧的 `1207c11` 不在其祖先链里）。直接 merge 预演有 **194 个冲突文件**（139 个 `app/test`，其中约 120 张 golden）。实际做法：以 `e685259` 为基线新建 `resync/e685259`，用 `git diff 1207c11 main`（排除 golden PNG）重放本地净改动，**只有 4 个文件需要手工解冲突**（`RuntimeInstaller.kt`、两个 update 测试、`docs/release.md`），其余 clean。
- **风险/注意**：
  1. 上游会持续重写或 squash 历史；每次同步前先 `git fetch` 比对 `merge-base`，如果又分叉就继续用「重放净改动」而不是直接 merge；
  2. 重放会丢原始的 20 个提交粒度（本次压成一个 `merge:` 提交），提交信息里保留了改动清单；
  3. golden 仍不在补丁里：本次沿用上游的 PNG，`tooling/optimize-goldens.sh` 需要时可再跑；
  4. 上游新增了 `runtime/patches/zzzzzzz-input-recovery.diff` 并改了 `zzzzzz-session-deletion.diff`、`zzz-title-cache-refresh.diff`、`pins.json`；重放后 runtimeVersion 变为 `…-8502f4e42f76`，已发布的 `…-53b4fe78516f` 需要重新上传。
- **建议**：
  1. 同步流程：`git fetch origin` → 确认 `origin/main` 是否包含本地基点 → 分叉则新建 `resync/<sha>` 重放 `git diff <上次同步点> main`（排除 golden）→ 解冲突 → 生成物重跑（l10n / guide / replay_viewer）→ analyze + 测试 → 普通 push（新分支以 origin/main 为祖先，无需 force）；
  2. **不向上游提交**：上游仅作只读同步来源（2026-11-02 确认）；
  3. 生成文件（`app/lib/l10n/gen/*`、`app/lib/share_code/replay_viewer.g.dart`）冲突时用对应生成器重跑，不要手改；
  4. golden 冲突按 `docs/testing/goldens.md` 用 `--update-goldens` 重新生成并验证。
- **验收**：`git log --oneline origin/main..HEAD` 只有本地提交；`flutter analyze --fatal-infos` 通过；`runtime/test-host.sh` 通过；仓库无冲突标记。
---

### OPT-15 `targetSdk=28` 的长期风险（监控项）

- **状态**：已记录，不需要现在改
- **现状**：`docs/architecture.md:78` 已说明「与 Termux 相同，避开 Android 10+ 对应用私有目录 `exec` 的限制」；`runtime/manifest.json` 里 `minLoadAlign: 16384` 说明 16 KB 页已处理。
- **风险**：Android 16+ 对老 targetSdk 的兼容提示、未来商店/系统策略收紧；若哪天必须升到 34+，需要替代的 `exec` 方案（nativeLibraryDir 加载器 / 直接启动模式）。
- **建议**：在 `docs/architecture.md` 补一节「升级 targetSdk 的前置条件与验证清单」，并在每次 Android 大版本发布后回归一次。

---

## 3. 建议实施顺序

| 批次 | 内容 | 目标 |
|---|---|---|
| 第 1 批（1 天） | OPT-01、OPT-09 ✅ | 已完成：arm64 205.2 → 192.3 MB；stop_daemon 只停本项目 |
| 第 2 批（3–5 天） | OPT-05、OPT-07、OPT-06 ✅（OPT-06 部分） | 已完成：CI、严格分析、golden 304→164 MB |
| 第 3 批 ✅ | OPT-02（已发布）、OPT-03（已完成）、OPT-04（决定保留） | 普通包 34.1 → 32.5 MB；离线包 183.4 → 148.9 MB；rootfs −168.8 MiB（749.6 → 580.8 MiB），归档 151.0 → 116.5 MiB |
| 第 4 批（按需） | OPT-11、OPT-12、OPT-13、OPT-08、OPT-10、OPT-14 | 安全/合规/构建体验/上游同步（不回上游提交） |

依赖关系：OPT-02 与 OPT-03 共用「运行时分层/按需下载」的机制，建议一起设计；OPT-05 的 CI 应在 OPT-02 之前落地，否则拆包改动没有回归保护。

---

## 4. 待确认的产品取舍

1. **是否接受「APK + 首次下载运行时」**？如果目标是「一个 APK 装完即用、离线可用」，OPT-02 只能做分层裁剪，收益会小很多（192.3 → ~120 MB）。
2. **WebRTC 的 P2P 路径是不是核心卖点**？如果是，OPT-04 只能做「延迟加载」不能删。
3. **Python / git 是否所有用户都需要**？如果只是少数技能用，`/opt/python`（78 MB）和 `git`（11 MB）也可以变成可选层。
4. **插件构建脚本的批准粒度**？当前默认拒绝、按包名批准；是否要绑定版本/integrity 并在升级时重新确认，决定 OPT-12 做到什么程度。

---

## 5. 已确认不需要改的点

- 运行时供应链：Ubuntu base → `Packages.xz` → deb → Node/CPython 全部 sha256 固定，`runtime/locks/*.json` 可复现；
- PRoot 的 `DT_NEEDED` 补丁、`minLoadAlign: 16384` 校验；
- v1/v2/v3 三套签名（照顾国产 ROM 扫描器）；
- `build-apk.sh` 构建后 `aapt2` 回读 versionCode/versionName 校验；
- `--dart-define-from-file` + `shred` 处理 beta token；
- rootfs 解包的防御性校验（relative symlink、no member under symlink、setuid 清理、parent-before-child）——这部分设计比多数同类项目严谨；
- `runtime/lib/rtbuild.py` 的 docs/man/locale/apt-lists/dev-metadata 裁剪（所以本清单不再重复建议）。

---

## 6. 变更记录

| 日期 | 变更 |
|---|---|
| 2026-10-31 | 初版：基于 `c624964` 与手机实测 1.0.4 APK |
| 2026-11-01 | 基线刷新到 `39dd598`（上游 `08d76f5`，运行时 r14）；更新 OPT-06/14 的数字；按 pnpm 11 + 插件管理器审批流重写 OPT-12（P1 → P2） |
| 2026-11-01 | 再次刷新：合并上游 1.0.8（merge `e5b17a8`，HEAD `50d4319`，上游 `1207c11`），基线改为 1.0.8 APK（`versionCode=11012019`，205.2 MB）；更新 APK / 仓库 / golden 数字，OPT-05 补充 Issue 模板说明，OPT-11 补充动态端口与日志脱敏，OPT-14 改为领先 10 个提交 |
| 2026-11-01 | 完成第 1 批：OPT-01（按 ABI 排除原生库，arm64 205.2 → 192.3 MB，x64 35.4 MB）与 OPT-09（不再 pgrep 杀全局 daemon），提交 `6e617f9`；基线更新为该构建 |
| 2026-11-01 | 完成第 2 批：OPT-05 CI（`7ca81f8`）、OPT-07 analyzer 严格项（`25cacf6`）、OPT-06 golden 无损压缩 304→164 MB（`0a02d5d`）；重建 1.0.8 APK `11012308`（192.3 MB） |
| 2026-11-02 | OPT-02 代码完成：普通包只带 manifest，首启按 `rootfsUrl` 下载并断点续传；离线 flavor 保留内置。普通 arm64 34.1 MB、离线 192.3 MB（`ee5c070`）；runtime 上传脚本/文档 `794f4b9`；发布上传与真机验证待做 |
| 2026-11-02 | 确认仓库策略：改动只保留在 `maiji1990/dsh-Go`，不向上游提交 PR；OPT-14 建议改为「上游只读同步」，OPT-02 的 runtime 归档发布目标改为 `maiji1990/dsh-Go` 的独立 Release |
| 2026-11-02 | 本地实测复核：golden 985 张 / 162 MB，`app/test` 169 MB，`.git` 541 MB；r14 运行时在 WSL 重建成功（151 MB 归档、835 个 ELF、0 架构错误），`rootfsUrl` 为 null，待回填 |
| 2026-11-02 | OPT-03 第一步：`rtbuild.py` 增加可选包裁剪（`DSH_RUNTIME_KEEP_OPTIONAL=1` 可保留），新增 `zzzzzzz-optional-bundles.diff` 把 inspector 从 `OPTIONAL_BUNDLES` 移除；rootfs 749.6 → 727.1 MiB（归档 151.0 → 147.4 MiB，arm64），`test-host.sh` 全绿 |
| 2026-11-02 | OPT-03 完成：按产品决定移除 Office 转换链（`dsh-office-to-pdf` / `libreoffice-kit` / `libreoffice-kit-wasm` / `dsh-skill-office`，146 MB），新增 `zzzzzzzz-office-removal.diff` 去掉 `dsh-web-app` 的插件条目；rootfs 727.1 → 580.8 MiB，归档 147.4 → 116.5 MiB，`test-host.sh` 89/167/29 全绿 |
| 2026-11-02 | OPT-02 发布：`rootfs-arm64-v8a.tar.xz`（116.5 MiB）上传到 `maiji1990/dsh-Go` 的 Release `runtime-r14-…-53b4fe78516f`（`--latest=false`，HTTP 200 / 122,149,248 B），`rootfsUrl` 已回填；普通 arm64 包 32.5 MB（`11021341`，包内无 rootfs）；x86_64 未发布，其 `rootfsUrl` 置空 |
| 2026-11-02 | OPT-04 决定保留：`flutter_webrtc` 是编译期插件，裁剪等于砍掉 P2P 直连；本项不再推进 |
| 2026-11-02 | 上游重写历史后重新同步：以 `e685259` 为基线新建 `resync/e685259`，重放 `1207c11..main` 的净改动（排除 golden PNG），194 → 4 个手工冲突；提交 `3276e9a`。基线、OPT-14 与运行时版本（`…-8502f4e42f76`）同步更新 |
| 2026-11-02 | 重放后 golden 回到上游版本：995 张 / 307 MB（OPT-06 的压缩需在新基线上重跑 `tooling/optimize-goldens.sh`）；已推送 `main`（`9e894ad`） |
| 2026-11-02 | OPT-06 在新基线上重做：20 张差异图重新生成（share 二维码/插件文案），其余 `oxipng -o4 --strip safe` 无损重压，995 张 310 → 167 MB（`72d34e0`）。Linux（WSL）全量测试 8829 通过 / 287 跳过 / 5 失败：drawer 2 张与 remote_bootstrap 2 个在干净上游同样失败，share_entries 1 个是并行负载下的 `pumpAndSettle` 超时（单独跑通过） |
| 2026-11-02 | CI 修复：`fonts-noto-cjk` 只带 Regular/Bold，测试需要 Light（`NotoSansCJK-Light.ttc`）→ test 任务改安装 `fonts-noto-cjk` + `fonts-noto-cjk-extra`；此前 test 分片在所有提交上都因缺字体直接失败 |
| 2026-11-02 | CI 再修：发行版字体（24.04 的 2023 版 / 26.10 的 2024 版）与基准图字形不同，`run_step_*`、`activity_strip_*`、`card_reply_*` 在 CI 上像素失败。test 任务改为固定下载 `Sans2.004` OTC 包并指向它；同时用 2.004 重新生成 `drawer_g9_folder_editor` 两张图（本机 2.004 下原本失败） |
| 2026-11-02 | CI 根因确认：像素失败来自**时区**（`TZ=UTC` 可完整复现，`TZ=Asia/Shanghai` 全部通过）——计时器与分享卡片渲染本地时间。CI test 任务固定 `TZ=Asia/Shanghai`，并在 `docs/testing/goldens.md` 写明字体/时区约定 |
| 2026-11-02 | 运行时 `…-8502f4e42f76` 已发布（arm64，116.5 MiB，HTTP 200），`rootfsUrl` 回填；普通包 `versionCode=11021704` 32.8 MB 重打（包内无 rootfs） |
