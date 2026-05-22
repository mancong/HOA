# Building HOA

## HOA 介绍

HOA（HarmonyOS On Android）是一个在 Android 上运行 OpenHarmony HAP 应用的运行时环境。它基于 ArkUI-X 跨平台框架，通过 `harmony-on-android` 组织下的 6 个 fork 仓库提供 OHOS HAP 兼容适配，将 ArkTS 页面渲染到 Android SurfaceView 上。

构建产物 `app-debug.apk` 约 85MB，内含 ArkUI-X 引擎、ETS VM、Skia 及 151 个 OHOS API 插件 .so。

**完整构建流程:**

```
repo init (HOA manifest)
  → build/prebuilts_download.sh (预编译工具链)
    → ./build.sh (编译 ArkUI-X)
      → scripts/sync_arkui_x.sh (产物同步到 HOA 项目)
        → ./gradlew assembleDebug (APK 打包)
```

总耗时约 30 分钟（首次），磁盘需求约 100GB（源码 + 产物）。后续增量构建 2-5 分钟。

---

## 前提条件

| 工具 | 用途 | 安装方法 |
|------|------|---------|
| **repo** | 管理多仓库源码树 | `curl -s https://raw.gitcode.com/gitcode-dev/repo/raw/main/repo-py3 > /usr/local/bin/repo && chmod a+x /usr/local/bin/repo` |
| **git** + **git-lfs** | 版本控制 / 大文件 | 系统包管理器 (`apt install git git-lfs`) |
| **python3** + **pip3** | 构建脚本 | `apt install python3 python3-pip` |
| **Java 17** | Android APK 编译 | `apt install openjdk-17-jdk` |
| **Android SDK** | Gradle 构建 | Android Studio 或 `sdkmanager`（需 API 34+） |

**操作系统**: Ubuntu 18.04/20.04/22.04/24.04 均可。其他 Linux 发行版可能需在 `build.sh` 中跳过 OS 版本检查。

**Android SDK 配置**: 项目根目录创建 `local.properties`：

```
sdk.dir=/path/to/Android/Sdk
```

---

## Step 1: 下载源码

HOA 使用独立 manifest 仓库管理源码树。其中的 `hoa-weekly.xml` 通过 `<include name="default.xml"/>` 拉取 ArkUI-X 全部仓库，再将 6 个定制仓库 override 到 `harmony-on-android` 组织的 fork。

```bash
mkdir -p ~/arkui-x-hoa
cd ~/arkui-x-hoa

# 通过 HTTPS 下载 (无需注册 SSH)
repo init -u https://gitcode.com/harmony-on-android/manifest \
          -b hoa-weekly -m hoa-weekly.xml --no-repo-verify
repo sync -c -j4
repo forall -c 'git lfs pull'
```

> **SSH 方式**（需注册 GitCode 公钥）：将 `-u` 改为 `git@gitcode.com:harmony-on-android/manifest.git`

---

## Step 2: 下载预编译工具链

```bash
bash build/prebuilts_download.sh --build-arkuix --skip-ssl
```

该脚本下载 Clang、GN、Ninja 等预编译构建工具。

---

## Step 3: 编译 ArkUI-X

```bash
./build.sh --product-name arkui-x --target-os android
```

构建产物输出到 `out/arkui-x/aosp_clang_arm64_release/`。

常用选项：

| 选项 | 说明 |
|------|------|
| `--product-name arkui-x` | 编译产品名称（必须） |
| `--target-os android` | 目标平台，可选 `android` / `ios` |
| `--gn-args` | 额外 GN 参数 |
| `--log-level info` | 日志等级，可选 `info` / `debug` |

> **注意**: 首次构建约需 30 分钟。后续增量构建只需 2-5 分钟。

---

## Step 4: 同步产物到 HOA 项目

```bash
cd /path/to/HOA
ARKUI_BUILD=~/arkui-x-hoa/out/arkui-x/aosp_clang_arm64_release ./scripts/sync_arkui_x.sh
```

该脚本复制以下产物：

| 类型 | 源路径 | 目标路径 |
|------|--------|---------|
| Native 库 (.so) | `arkui/`, `plugins/` | `app/src/main/jniLibs/arm64-v8a/` |
| 系统资源 .abc | `arkui/ace_engine_cross/*.abc` | `app/src/main/assets/sys/systemres/abc/` |
| 资源文件 | `obj/interface/sdk/systemres/` | `app/src/main/assets/sys/systemres/` |
| 字体 | `obj/interface/sdk/systemres_fonts/` | `app/src/main/assets/sys/systemres/fonts/` |
| ICU 数据 | `icu_data/out/icudt74l.dat` | `app/src/main/assets/sys/systemres/icudt72l.dat` |
| arkui_android_adapter.jar | `arkui_android_adapter.jar` | `app/libs/` |
| stub.an | `gen/arkcompiler/ets_runtime/stub.an` | `app/src/main/assets/sys/stub/arm64-v8a/` |

增量构建时可使用 `--so-only` 仅更新 .so 文件：

```bash
./scripts/sync_arkui_x.sh --so-only
```

---

## Step 5: 构建 APK

```bash
./gradlew assembleDebug
```

产物: `app/build/outputs/apk/debug/app-debug.apk` (~85MB)

---

## 常见工作流

### 日常开发（修改了 Java/Kotlin 代码）

```bash
./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 修改了 ArkUI-X C++ 代码

```bash
cd ~/arkui-x-hoa
./build.sh --product-name arkui-x --target-os android
cd /path/to/HOA
./scripts/sync_arkui_x.sh --so-only
./gradlew assembleDebug
```

### 完整重建

```bash
cd ~/arkui-x-hoa
./build.sh --product-name arkui-x --target-os android
cd /path/to/HOA
./scripts/sync_arkui_x.sh
./gradlew assembleDebug
```

---

## HOA 定制仓库

以下 6 个仓库被 override 为 [harmony-on-android](https://gitcode.com/harmony-on-android) 组织的 fork（`hoa-weekly` 分支）：

| 仓库 | 源码路径 | 主要修改 |
|------|---------|---------|
| **arkcompiler_ets_runtime** | `arkcompiler/ets_runtime` | OHOS HAP ABC record 名适配（SDK 5.0/6.0 双格式兼容） |
| **build** | `build` | GN 构建模板 Android NDK 交叉编译适配 |
| **app_framework** | `foundation/appframework` | OHOS_HAP_MODE 环境变量注入 VM 标志位、hapPath 前缀 |
| **arkui_for_android** | `foundation/arkui/ace_engine/adapter/android` | RSUIDirector 创建（白屏修复）、WebView shouldInterceptRequest 拦截、JNI setenv 桥接、resources.index 路径修复 |
| **arkui_napi** | `foundation/arkui/napi` | Android NAPI .abc 路径修复、资源路径标记适配 |
| **plugins** | `plugins` | 权限 JNI 检查绕过、webview 资源路径适配 |

全部修改已提交到 `hoa-weekly` 分支（基于 ArkUI-X weekly_20260518）。详细说明见 `docs/ARKUI-X_PATCHES.md`。

---

## 标志传递链

OHOS HAP 模式通过以下链路从 Java 层传递到 C++ VM：

```
HoaApplication.kt
  → StageApplication.setOhosHapMode(true)       [arkui_for_android]
    → JNI nativeSetOhosHapMode                  [arkui_for_android]
      → setenv("OHOS_HAP_MODE", "1")            [arkui_for_android]
        → js_runtime.cpp 读取环境变量           [app_framework]
          → JSNApi::SetOhosHapMode(vm_, true)   [arkcompiler_ets_runtime]
            → IsOhosHapMode() 分支判断          [arkcompiler_ets_runtime]
```

---

## 项目结构

```
HOA/
├── manifests/
│   └── hoa-weekly.xml              # HOA manifest 副本（源: harmony-on-android/manifest）
├── scripts/
│   └── sync_arkui_x.sh             # ArkUI-X 产物同步脚本
├── app/
│   ├── src/main/jniLibs/arm64-v8a/ # Native 库 (来自 ArkUI-X 构建)
│   ├── src/main/assets/sys/       # 系统资源 (来自 ArkUI-X 构建)
│   ├── src/main/java/app/hackeris/hoa/  # Android 应用源码
│   └── build.gradle.kts
├── docs/
│   ├── BUILD.md                   # 构建文档 (本文件)
│   └── ARKUI-X_PATCHES.md         # ArkUI-X 源码修改详细说明
└── agents/                        # 技术调研与进展文档
    ├── PLAN.md
    ├── PROGRESS.md
    └── test-hap-analysis.md
```

---

## Troubleshooting

### repo init 失败

确认网络可访问 `gitcode.com`。如果在受限网络环境，可尝试代理：

```bash
export HTTP_PROXY=http://proxy:port
export HTTPS_PROXY=http://proxy:port
```

### build.sh 报 "OS version not supported"

ArkUI-X 构建脚本内置了 OS 版本检查。如果系统是较新版本（如 Ubuntu 24.04），在 `build.sh` 的 OS 版本检查逻辑中添加对应版本号。

### prebuilts_download.sh 下载失败 (SSL)

使用 `--skip-ssl` 参数跳过 SSL 验证：

```bash
bash build/prebuilts_download.sh --build-arkuix --skip-ssl
```

### APK 体积过大

`libarkui_android.so` (~82MB) 是体积最大的单文件——内含完整 ArkUI-X 引擎、ETS VM、Skia。这是架构决定的，无法有效削减。

### 构建失败，提示 .so 缺失

先运行 `./scripts/sync_arkui_x.sh` 确保所有 ArkUI-X 产物已同步到项目。

### resources.index 不匹配

如果 HAP 内 `$r()` 引用资源失败，检查：
1. `HapInstaller` 是否正确写入 `files/hap/{bundleName}.{moduleName}/resources.index`
2. `StageAssetProvider::Preload()` 是否从 `files/hap/` 正确复制到 `files/sys/`

---

## 附录 A: HOA manifest

HOA 定制 manifest 维护在 `harmony-on-android/manifest` 仓库的 `hoa-weekly` 分支：

```xml
<!-- hoa-weekly.xml -->
<?xml version="1.0" encoding="UTF-8"?>
<manifest>
  <remote fetch="https://gitcode.com/harmony-on-android" name="hoa" />

  <!-- ArkUI-X baseline: pull all non-customized repos -->
  <include name="default.xml" />

  <!-- HOA customized repos: override to harmony-on-android / hoa-weekly branch -->
  <project path="arkcompiler/ets_runtime" name="arkcompiler_ets_runtime"
           remote="hoa" revision="hoa-weekly" />
  <project path="build" name="build"
           remote="hoa" revision="hoa-weekly" />
  <project path="foundation/appframework" name="app_framework"
           remote="hoa" revision="hoa-weekly" />
  <project path="foundation/arkui/ace_engine/adapter/android"
           name="arkui_for_android" remote="hoa" revision="hoa-weekly" />
  <project path="foundation/arkui/napi" name="arkui_napi"
           remote="hoa" revision="hoa-weekly" />
  <project path="plugins" name="plugins"
           remote="hoa" revision="hoa-weekly" />
</manifest>
```

HOA 项目本地也保留了一份副本：`manifests/hoa-weekly.xml`。修改后推送到 manifest 仓库即可生效。
