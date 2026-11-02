# Golden 基准图更新

Golden（`matchesGoldenFile`）失败不一定是代码坏了：样式、文案或行高按预期变化后，基准图没跟上，也会失败。处理顺序是「先判断、再更新、最后验证」，不要直接 `--update-goldens` 把回归一起盖掉。

## 1. 先判断该修代码还是该更新图

1. 看失败目录里的对比图：
   - `app/test/**/failures/*_isolatedDiff.png`：只有变化的像素；
   - `*_maskedDiff.png`、`*_masterImage.png`、`*_testImage.png`：基准图、实际渲染和差异。
2. 确认差异来自本次改动，且是想要的渲染结果。不是的话修代码，不要更新图。
3. 合并上游后出现的失败，先确认是不是本地既有问题：

   ```bash
   # 在合并前的 commit 上开一个临时 worktree 跑同一测试
   git worktree add --detach /tmp/dsh-premerge <merge-base-commit>
   cd /tmp/dsh-premerge/app
   flutter pub get
   DSH_TEST_FONT_DIR=/usr/share/fonts/opentype/noto \
     flutter test test/plugin_config/plugin_config_golden_test.dart
   git worktree remove --force /tmp/dsh-premerge
   ```

   如果合并前同样失败，说明是本地基准图早就过期，不是这次合并引入的；按第 2 步更新即可。

## 2. 只更新失败的那几张

用 `--plain-name` 限定测试名，避免把一个文件里本来通过的图全部重写。测试名是 `名称 (dark)` / `名称 (light)`，子串匹配即可同时更新明暗两版。

```bash
cd app

# 字体不在 /usr/share/fonts/noto-cjk 时指定实际目录；
# 缺字体会直接报 "Test font missing"，不会静默用方块字。
export DSH_TEST_FONT_DIR=/usr/share/fonts/opentype/noto

flutter test --update-goldens test/engine_settings/engine_settings_golden_test.dart \
  --plain-name "engine_settings_hub"

flutter test --update-goldens test/plugin_config/plugin_config_golden_test.dart \
  --plain-name "plugin_config_hub_r7"

flutter test --update-goldens test/plugin_config/plugin_config_golden_test.dart \
  --plain-name "plugin_config_bundle_sheet"
```

用仓库固定的 Flutter 版本（`tooling/setup-toolchain.sh` 的 `FLUTTER_VERSION`，当前 3.47.7）；不同版本的排版和字形有细微差异，不要把版本差异写进基准图。

字体和时区也要固定：基准图用 **Noto Sans CJK 2.004**（`Sans2.004` 的 OTC 包）且在 **UTC+8** 下生成。换字体或换时区会让聊天计时器、分享卡片的时间文案跟着变，像素比对就会失败；CI 因此固定 `TZ=Asia/Shanghai` 并下载同一版字体。

命名规则：`configGolden` / `engineGolden` 会为每个 `name` 生成 `goldens/<name>_dark.png` 与 `<name>_light.png`。只跑需要的 `--plain-name`，`git status` 里就只会出现对应的 PNG。

### 被门禁跳过的用例

`schema_structure_golden_test.dart`、`form_catalog_golden_test.dart` 需要显式打开：

```bash
flutter test --dart-define=W3_GOLDENS=true \
  test/engine_settings/schema_structure_golden_test.dart
```

没有 `W3_GOLDENS=true` 时它们是 **skipped**，不能把 skipped 写成 passed。更新这些基准图也用同样的 `--dart-define` 加 `--update-goldens`。

## 3. 验证

```bash
cd app
DSH_TEST_FONT_DIR=/usr/share/fonts/opentype/noto \
  flutter test test/plugin_config/plugin_config_golden_test.dart \
               test/engine_settings/engine_settings_golden_test.dart \
               test/engine_settings/schema_structure_golden_test.dart \
               test/engine_settings/form_catalog_golden_test.dart
```

期望 `All tests passed!`；`git status` 只应列出对应的基准图。再打开新图确认副标题、行高、圆角等差异确实符合预期。

## 4. 提交

只提交 PNG，提交信息写清为什么更新，例如：

```text
test(goldens): 同步插件设置模式副标题的基准图

本地插件自动适配为插件行加了「原生 / 网页 / 由配置文件固定」副标题，
上游同步后这 6 张基准图仍是旧版：
- engine_settings_hub dark/light
- plugin_config_hub_r7 dark/light
- plugin_config_bundle_sheet dark/light
```

不要把 `app/test/**/failures/` 一起提交（已被 `.gitignore` 忽略）。

## 5. 同步上游时的注意点

本 fork 有本地 UI 特性（插件设置模式副标题、卡片圆角、字号阶梯等），与上游同名基准图会产生 **二进制冲突**。不要手工「合并」PNG：

1. 先按普通冲突解决代码；
2. 选任意一侧的 PNG 作为占位，让合并能继续；
3. 按本文第 2 步用 `--update-goldens` 重新生成真正想要的图；
4. 按第 3 步验证。

2026-11-01 同步上游 1.0.8 时，`engine_settings_hub`、`plugin_config_hub_r7`、`plugin_config_bundle_sheet` 共 6 张图就是按这个流程更新的（提交 `0c145ac`）。

## 6. 无损压缩（OPT-06）

Golden 是仓库里最大的一块。`tooling/optimize-goldens.sh` 用 [oxipng](https://github.com/oxipng/oxipng) 只重压 PNG 编码：

```bash
tooling/optimize-goldens.sh            # 优化 git 跟踪的 app/test/**/goldens/*.png
tooling/optimize-goldens.sh --dry-run  # 只列出文件
```

- 像素不变；全不透明的 alpha 通道可能被去掉，`matchesGoldenFile` 解码后仍是同一张图。
- 2026-11-01：985 张 golden 从 **304 MB → 164 MB**；`app/test` 从 331 MB → 191 MB。
- 优化后必须重跑所有 golden 测试（见第 3 节）；2026-11-01 的 1577 个 golden 用例通过，158 个按 `W3_GOLDENS` 门禁跳过。
- Git 历史中的旧 blob 仍然存在，所以 **clone 体积不会因为这次提交而下降**；要真正缩小 clone，需要另行决定是否重写历史或迁移到 LFS，代价和协作影响都更大。

