# Building HOA

## Quick start

```bash
# 1. Clone with submodules
git clone --recurse-submodules <this-repo>
cd HOA

# 2. Build all native .so files from source (~30 min first build)
cd third_party
./build_runtime.sh all

# 3. Build APK
cd ..
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk` (~94 MB, 17 native libraries).

## Quick start — full build (ArkUI-X runtime)

The ArkUI-X runtime (`libarkui_android.so` + `arkui_android_adapter.jar` + systemres) is built
from a separate source tree at `/data/share/hoa2/arkui-x/`. All artifacts are copied into the
HOA project via `scripts/sync_arkui_x.sh`.

```bash
# 1. Build ArkUI-X for Android (~30 min, 700MB+ output)
cd /data/share/hoa2/arkui-x
./build.sh --product-name arkui-x --target-os android

# 2. Copy all outputs into HOA project (one command)
cd /path/to/HOA
./scripts/sync_arkui_x.sh

# 3. Build APK
./gradlew assembleDebug
```

## Prerequisites

| Tool | Version | Check |
|------|---------|-------|
| Android NDK | 28.2+ | `ls /apps/android/ndk/28.2.13676358/build/cmake/android.toolchain.cmake` |
| CMake | ≥ 3.10 | `cmake --version` |
| Ruby | ≥ 3.0 | `ruby --version` |
| Gradle | 8.x (wrapper) | `./gradlew --version` |

If your NDK is at a different path, edit `third_party/build_runtime.sh` line 27 (`NDK=...`).

## Build steps in detail

### `./build_runtime.sh all` — full pipeline

```
do_preflight  →  verify NDK, CMake, Ruby, git submodules
do_patch      →  apply android_port.patch (idempotent)
do_codegen    →  generate 100+ C++ headers from ISA YAML via Ruby/ERB (skips if build_gen/ exists)
do_compile    →  CMake + NDK arm64-v8a cross-compile → 12 .so files
do_install    →  strip + copy to app/src/main/jniLibs/arm64-v8a/
```

Individual steps:

```bash
./build_runtime.sh codegen     # only regenerate build_gen/
./build_runtime.sh compile     # only cross-compile (needs build_gen/)
./build_runtime.sh install     # only copy .so files
./build_runtime.sh clean       # remove build_android/
```

### Code generation (15 stages)

The ArkCompiler runtime uses a Ruby/ERB codegen pipeline that generates thousands of lines
of C++ headers and source files from ISA YAML specifications. These are reproducible from
the source tree — rebuilding `build_gen/` from scratch takes ~2 minutes.

```bash
rm -rf build_gen
./build_runtime.sh codegen
```

Stages: ISA combine → ISA templates → type.h → plugin options → libpandabase →
compiler headers → runtime options → bytecode definitions → plugin defines →
entrypoints → assembler codegen → intrinsics.yaml → compiler intrinsics →
runtime intrinsics → runtime plugins → asm_defines.h + cross_values →
verification headers → verification templates.

### Android portability patches

The upstream `arkcompiler_runtime_core` source is pure OpenHarmony code. Eight patches in
`android_port.patch` make it compile with Android NDK (Clang 19, libc++, arm64-v8a). The
`do_patch` step is idempotent — safe to run multiple times.

### Build output

| .so | Size | Built from |
|-----|------|-----------|
| libarkruntime.so | 18 MB | Source (ETS VM, GC, interpreter) |
| libarkcompiler.so | 6.6 MB | Source (JIT compiler, optimizer) |
| libarkassembler.so | 980 KB | Source (bytecode assembler) |
| libpandafile.so | 612 KB | Source (Panda file format) |
| libpandabase.so | 464 KB | Source (utilities, logging, threading) |
| libziparchive.so | 212 KB | Source (ZIP/HAP extraction) |
| libz.so | 156 KB | Source (zlib) |
| libarkaotmanager.so | 76 KB | Source (AOT compilation) |
| libc_secshared.so | 72 KB | Source (secure C runtime) |
| libhongengine_c.so | 20 KB | Source (C wrapper API) |
| libarktarget_options.so | 8 KB | Source (target-specific compiler flags) |
| libc++_shared.so | 1.7 MB | NDK (C++ STL) |
| libarkui_android.so | 79 MB | ArkUI-X prebuilt SDK |
| libarkui_componentsnapshot.so | 528 KB | ArkUI-X prebuilt SDK |
| libarkui_focuscontroller.so | 508 KB | ArkUI-X prebuilt SDK |
| libhilog.so | 152 KB | ArkUI-X prebuilt SDK |

### ArkUI-X output files

The `./build.sh --product-name arkui-x --target-os android` build produces these files:

| Output | Path under `out/arkui-x/aosp_clang_arm64_release/` | Destination in HOA |
|--------|------|-----------|
| `arkui_android_adapter.jar` | `.` | `app/libs/` |
| `libarkui_android.so` | `arkui/arkui-x/` | `app/src/main/jniLibs/arm64-v8a/` |
| `libarkui_componentsnapshot.so` | `arkui/ace_engine_cross/` | `app/src/main/jniLibs/arm64-v8a/` |
| `libarkui_focuscontroller.so` | `arkui/ace_engine_cross/` | `app/src/main/jniLibs/arm64-v8a/` |
| `libhilog.so` | `plugins/hilog/` | `app/src/main/jniLibs/arm64-v8a/` |
| `resources.index` (1.3MB) | `obj/interface/sdk/systemres/` | `app/src/main/assets/arkui-x/systemres/` |
| `resources/` (base/dark/wearable) | `obj/interface/sdk/systemres/resources/` | `app/src/main/assets/arkui-x/systemres/resources/` |
| Fonts (`HMSymbolVF.ttf` etc.) | `obj/interface/sdk/systemres_fonts/` | `app/src/main/assets/arkui-x/systemres/fonts/` |
| ICU data `icudt74l.dat` (30MB) | `icu_data/out/` | `app/src/main/assets/arkui-x/systemres/icudt72l.dat` |
| Framework .abc files (~50+) | `arkui/ace_engine_cross/*.abc` | `app/src/main/assets/arkui-x/systemres/abc/` |

Note: the ICU file is renamed from `icudt74l.dat` to `icudt72l.dat` to match
the filename the Android adapter expects at runtime. If the build produces a
different ICU version, adjust the target filename accordingly.

### `scripts/sync_arkui_x.sh` — 自动化产物同步

重建 ArkUI-X 后，运行此脚本将**所有**编译产物复制到 HOA 项目。所有从 ArkUI-X 外部复制到项目中的文件均由该脚本统一管理。

**用法**：

```bash
# 完整同步（所有文件）
./scripts/sync_arkui_x.sh

# 仅同步 .so（修改 C++ 后快速更新）
./scripts/sync_arkui_x.sh --so-only

# 仅同步 systemres .abc
./scripts/sync_arkui_x.sh --abc-only

# 仅同步资源文件 (JAR/resources/fonts/ICU/stub.an)
./scripts/sync_arkui_x.sh --res-only

# 预览（不实际复制）
./scripts/sync_arkui_x.sh --dry-run

# 指定构建产物目录
ARKUI_BUILD=/custom/path/out/.../aosp_clang_arm64_release ./scripts/sync_arkui_x.sh
```

**完整同步清单**（约 90 个文件）：

| Section | 数量 | 源路径（相对于 `aosp_clang_arm64_release`） | 目标路径（相对于项目根目录） | 说明 |
|---------|------|------|------|------|
| A1 | 1 | `arkui/arkui-x/libarkui_android.so` | `app/src/main/jniLibs/arm64-v8a/` | ArkUI-X Android 主库（含 OHOS HAP Patch） |
| A2 | 39 | `arkui/ace_engine_cross/*.so` | `app/src/main/jniLibs/arm64-v8a/` | ACE 渲染引擎组件库 |
| A3 | 1 | `arkui/arkui_components/libplatformview.so` | `app/src/main/jniLibs/arm64-v8a/` | Android 平台视图桥接 |
| A4 | 1 | `plugins/hilog/libhilog.so` | `app/src/main/jniLibs/arm64-v8a/` | OHOS 日志系统 NAPI 模块 |
| B1 | 43 | `arkui/ace_engine_cross/*.abc` | `app/src/main/assets/arkui-x/systemres/abc/` | 框架 UI 系统组件（\<Text\>/\<Button\>/...） |
| C1 | 1 | `arkui_android_adapter.jar` | `app/libs/` | ArkUI-X Android Java 适配器 |
| C2 | 1 | `obj/interface/sdk/systemres/resources.index` | `app/src/main/assets/arkui-x/systemres/` | 系统资源索引 |
| C3 | ~ | `obj/interface/sdk/systemres/resources/` | `app/src/main/assets/arkui-x/systemres/resources/` | 系统资源目录（base/dark/wearable） |
| C4 | ~ | `obj/interface/sdk/systemres_fonts/` | `app/src/main/assets/arkui-x/systemres/fonts/` | 字体文件（HMSymbolVF.ttf 等） |
| C5 | 1 | `icu_data/out/icudt74l.dat` | `app/src/main/assets/arkui-x/systemres/icudt72l.dat` | ICU 国际化数据（重命名 74l→72l） |
| C6 | 1 | `gen/arkcompiler/ets_runtime/stub.an` | `app/src/main/assets/arkui-x/stub/arm64-v8a/` | ArkTS 运行时桩文件 |

**常见工作流**：

```bash
# 修改了 ArkUI-X 的 C++ 代码后：
cd /data/share/hoa2/arkui-x
./build.sh --product-name arkui-x --target-os android
cd /path/to/HOA
./scripts/sync_arkui_x.sh --so-only    # 仅更新变化的 .so
./gradlew assembleDebug                # 重新打包 APK
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 完整重建（全部产物更新）：
./scripts/sync_arkui_x.sh              # 所有文件
./gradlew assembleDebug
```

### OHOS HAP mode patches (3 targeted patches in hoa2)

To run OHOS-format HAPs (native HarmonyOS bytecode), the ArkUI-X source in
`/data/share/hoa2/arkui-x/` needs 3 patches before building. These handle the
record-name format differences between ArkUI-X and OHOS:

| # | File | Change | Purpose |
|---|------|--------|---------|
| 1 | `arkcompiler/ets_runtime/ecmascript/ecma_vm.h` | Add `isOhosHapMode_` flag + accessors | VM flag to conditionally activate OHOS behavior |
| 2 | `arkcompiler/ets_runtime/ecmascript/platform/common/module.cpp` | `GetOutEntryPoint`: insert `src/main/` + wrap with `&` NORMALIZED_OHMURL_TAG when OHOS mode | OHOS ABC records use `&pkg/src/main/path&` format |
| 3 | `arkcompiler/ets_runtime/ecmascript/module/module_path_helper.cpp` | `ParseAbcPathAndOhmUrl` + `ParseUrl`: OHOS-mode path logic | OHOS records omit bundleName prefix |
| 4 | `arkcompiler/ets_runtime/ecmascript/jspandafile/js_pandafile_executor.cpp` | Guard `AdaptOldIsaRecord` with `!vm->IsOhosHapMode()` | Skip ArkUI-X record format adaptation |
| 5 | `arkcompiler/ets_runtime/ecmascript/napi/jsnapi_expo.cpp` + `.h` | Add `SetGlobalOhosHapMode`/`GetGlobalOhosHapMode` | Global flag set before VM creation |
| 6 | `foundation/appframework/ability/ability_runtime/cross_platform/frameworks/native/jsruntime/src/js_runtime.cpp` | Check `OHOS_HAP_MODE` env var after VM creation | Wire env var → VM flag |
| 7 | `foundation/arkui/ace_engine/adapter/android/stage/ability/java/jni/` (`.cpp` + `.h`) | Add `SetOhosHapMode` JNI using `setenv` | JNI bridge: Java → env var |
| 8 | `foundation/arkui/ace_engine/adapter/android/stage/ability/java/src/` (`.java`) | Add `StageApplication.setOhosHapMode()` wrapper | Public API for Java code |

The flag chain is: `HoaApplication.kt` → `StageApplication.setOhosHapMode(true)` →
JNI `SetOhosHapMode` → `setenv("OHOS_HAP_MODE", "1")` →
`js_runtime.cpp` reads env → `JSNApi::SetOhosHapMode(vm_, true)` →
patch #1-#4 check `vm->IsOhosHapMode()`.

## Project structure

```
HOA/
├── third_party/
│   ├── build_runtime.sh          # Build orchestrator (1000+ lines)
│   ├── CMakeLists.txt            # Cross-compilation build (11 SHARED targets)
│   ├── android_port.patch        # 8 patches for Android NDK compatibility
│   ├── build_gen/                # Generated headers (15-stage codegen output)
│   ├── build_android/            # CMake build directory (gitignored)
│   ├── arkcompiler_runtime_core/ # Submodule: Panda VM, compiler, assembler
├── scripts/
│   └── sync_arkui_x.sh            # ArkUI-X 产物同步脚本
├── app/
│   ├── src/main/jniLibs/         # Native libraries (gitignored, built by build_runtime.sh)
│   ├── src/main/cpp/             # JNI bridges
│   ├── src/main/assets/arkui-x/  # System resources + test ABC files
│   └── build.gradle.kts
└── PROGRESS.md                   # Project progress tracker
```

## Troubleshooting

**"fatal error: 'asm_defines.h' file not found"** — Run codegen first: `./build_runtime.sh codegen`

**Ruby OpenStruct errors** — Fixed in the codegen scripts. If you see `uninitialized constant OpenStruct` on Ruby ≥ 3.3, ensure you're using the latest build_runtime.sh.

**inst_builder_gen.cpp is empty or truncated** — Remove build_gen and re-run codegen:
```bash
rm -rf build_gen
./build_runtime.sh codegen
```

**NDK not found** — Edit `NDK=/path/to/your/ndk` in `build_runtime.sh` line 27.

**APK too large** — The 95 MB `libarkui_android.so` dominates. If you only need
ArkCompiler runtime (no UI), you can exclude ArkUI-X .so files.
