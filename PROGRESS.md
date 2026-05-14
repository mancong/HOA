# HOA 项目进展

## 当前状态

**路线已修订** — 从"从零构建 arkcompiler_ets_runtime"转向"基于 ArkUI-X 构建体系 + 3 个定向 Patch"。
详见 `PLAN.md`。

**当前里程碑**: OHOS HAP "Hello World" 已在 Android 设备上渲染成功（2026-05-15）。
下一个目标：解决 `@ohos:` NAPI 模块依赖，完善 Ability 生命周期。

---
## 关键突破：ABC Record 名匹配（2026-05-15）

### 问题

OHOS 编译的 ABC 文件中，class descriptor 使用归一化 URL 格式：
`L&entry/src/main/ets/entryability/EntryAbility&;`

但 `GetOutEntryPoint` 返回的 entry point 是：
`entry/src/main/ets/entryability/EntryAbility`

`ParseEntryPoint` 去掉 `L` 和 `;` 后得到 record 名：
`&entry/src/main/ets/entryability/EntryAbility&`

而 entry point 没有 `&` 分隔符，导致 `CheckAndGetRecordInfo` 精确匹配失败：
`Cannot find module 'entry/src/main/ets/entryability/EntryAbility'`

### 修复

**文件**: `arkcompiler/ets_runtime/ecmascript/platform/common/module.cpp:GetOutEntryPoint`

在 OHOS 模式下，将 entry point 用 `NORMALIZED_OHMURL_TAG`（`&`）包裹：
- 输入: `entry/ets/entryability/EntryAbility.abc`
- 输出: `&entry/src/main/ets/entryability/EntryAbility&.abc`
- `.abc` 剥离后: `&entry/src/main/ets/entryability/EntryAbility&` → 匹配 record 名

`TransformToNormalizedOhmUrl` 第 253 行有 guard：
`StringHelper::StringStartWith(oldEntryPoint, "&")` 检测到 `&` 前缀后直接返回，不做变换。

### 验证

```
HOA-DEBUG: GetOutEntryPoint OHOS mode, input=entry/ets/entryability/EntryAbility.abc,
           output=&entry/src/main/ets/entryability/EntryAbility&.abc
HOA-DEBUG: Record found OK, entry=&entry/src/main/ets/entryability/EntryAbility&
HOA-DEBUG: Instantiate done, hasException=0
HOA-DEBUG: Evaluate done, hasException=0
→ Hello World 渲染成功
```

### 当前 Patch 清单

ArkUI-X 运行时上的定向修改：

| # | 文件 | 修改 | 用途 |
|---|------|------|------|
| 1 | `module.cpp:GetOutEntryPoint` | OHOS 模式：`&` 包裹 entry point | ABC record 名匹配 |
| 2 | `js_runtime.cpp:ArkJsRuntime::Initialize` | 读取 `OHOS_HAP_MODE` 环境变量，调用 `SetOhosHapMode(true)` | 激活 OHOS 模式 |
| 3 | `js_pandafile_executor.cpp` | 多处 HOA-DEBUG 日志 | 调试追踪（可清理） |

---

## 测试 HAP

### 阶段 1: ArkUI-X 格式 HAP（当前）

**文件**: `/data/share/arkui-x-example/entry/build/default/outputs/default/entry-default-unsigned.hap` (113KB)

- bundleName: `app.hackeris.arkuixexample`
- compileMode: `esmodule`
- modules.abc: 7.6KB，2 个 record
- ABC record 含 bundleName 前缀，与 ArkUI-X 运行时原生兼容

### 阶段 2: OHOS 原生格式 HAP

**文件**: `/data/share/entry-default-unsigned.hap` (123KB)

- bundleName: `app.hackeris.harmonyexample`
- modules.abc: 12.8KB，3 个 record（含 backup extensionAbility）
- ABC record 无 bundleName，有 src/main/ — 需 3 个 Patch 才能加载

---

## 已完成

### Phase 1: 构建基础设施

#### Git Submodules（4 个，均来自 Gitee）

| 仓库 | 路径 | 用途 |
|------|------|------|
| `openharmony/arkcompiler_runtime_core` | `third_party/arkcompiler_runtime_core` | Panda VM, libpandafile, libpandabase, JIT compiler |
| `openharmony/arkui_ace_engine` | `third_party/ace_engine` | ACE 渲染引擎 |
| `arkui-x/app_framework` | `third_party/app_framework` | Stage 应用模型 |
| `arkui-x/arkui_for_android` | `third_party/arkui_for_android` | ArkUI-X Android 适配器 |

#### 构建系统（`third_party/`）

| 文件 | 说明 |
|------|------|
| `CMakeLists.txt` (1907行) | 19个 CMake 目标，arm64-v8a NDK 交叉编译 |
| `build_runtime.sh` (1000+行) | 构建编排：patch → codegen → compile → install |
| `android_port.patch` | 8 个上游 C++ 源码的 Android 移植补丁 |
| `build_gen/` | 代码生成输出（15 阶段 Ruby ERB 流水线，可从零生成） |

#### Android 移植补丁

上游 `arkcompiler_runtime_core` 子模块代码是纯 OpenHarmony 源码，需要 8 个补丁才能用 Android NDK 编译：

| 文件 | 修改内容 |
|------|----------|
| `compiler/optimizer/ir/runtime_interface.h` | 添加 `include/object_header.h`；`ark::EntrypointId(id)` → `static_cast<size_t>(id)` |
| `compiler/optimizer/code_generator/encode_visitor.cpp` | 添加 `#include "events_gen.h"` |
| `compiler/optimizer/ir_builder/inst_builder.h` | `IsBootContext()` 改为 stub（返回 false） |
| `plugins/ets/compiler/optimizer/ets_intrinsics_peephole.cpp` | 添加 `#include "runtime/include/thread.h"` |
| `plugins/ets/runtime/ets_class_linker_extension.cpp` | 无启动 panda 文件时跳过 `EtsPlatformTypes` 初始化 |
| `plugins/ets/runtime/ets_vm_api.cpp` | 入口点名称 `ETSGLOBAL::main` → `_GLOBAL::main` |
| `runtime/arch/asm_support.cpp` | 禁用 `static_assert`（交叉编译偏移量差异） |
| `verification/verifier_messages_data.cpp` | 生成文件 include 路径适配 |

补丁通过 `build_runtime.sh do_patch` 应用，支持幂等检测（不会重复打补丁）。

#### 从源码构建的 .so 文件

编译产物（stripped 大小）：

| .so 文件 | 大小 | 来源 |
|----------|------|------|
| libarkruntime.so | 18MB | 源码编译 |
| libarkcompiler.so | 6.6MB | 源码编译 |
| libarkassembler.so | 980KB | 源码编译 |
| libpandafile.so | 612KB | 源码编译 |
| libpandabase.so | 464KB | 源码编译 |
| libziparchive.so | 212KB | 源码编译 |
| libz.so | 156KB | 源码编译 |
| libarkaotmanager.so | 76KB | 源码编译 |
| libc_secshared.so | 72KB | 源码编译 |
| libhongengine_c.so | 20KB | 源码编译 |
| libarktarget_options.so | 8KB | 源码编译 |
| libc++_shared.so | 1.7MB | NDK 复制 |
| libarkui_android.so | 95MB | ArkUI-X 示例项目 |
| libarkui_componentsnapshot.so | 540KB | ArkUI-X 示例项目 |
| libarkui_focuscontroller.so | 520KB | ArkUI-X 示例项目 |
| libhilog.so | 156KB | ArkUI-X 示例项目 |

### Phase 2: ArkCompiler 运行时验证

#### 设备验证结果 (2026-05-09)

| 阶段 | 状态 | 说明 |
|------|------|------|
| Stage 0: APK 安装 & 启动 | ✅ | Honor YLE-W09, arm64-v8a |
| Stage 1: .so 加载链 | ✅ | 12 个库全部加载成功 |
| Stage 2: ABC 文件验证 | ✅ | hello.abc + etsstdlib.abc |
| Stage 3: ETS VM 创建 | ✅ | `ark::ets::CreateRuntime` 成功 |
| Stage 4: ETS 模块执行 | ✅ | `hello.abc` 执行成功，exit_code=42 |
| Stage 5: OHOS HAP 渲染 | ✅ | EntryAbility + Index 加载成功，Hello World 显示 |

### Phase 4: HAP 加载流程研究 ✅ ABC 加载突破

#### 关键发现

- `ExecutePandaFile(func_main_0)` 不是正确的合并 ABC 加载方式
- OHOS 通过 JSNApi + ModuleResolver + SourceTextModule 加载
- record 名双维度差异（bundleName + src/main/）是核心阻塞点
- 6 个信息缺口全部已解决（`/data/share/hoa2/information-gap-analysis.md`）

#### ABC Record 名匹配修复（2026-05-15）

- 根因：ABC record 名含 `&` 分隔符（`&entry/src/main/ets/...&`），entry point 不含
- 修复：`GetOutEntryPoint` 在 OHOS 模式下用 `NORMALIZED_OHMURL_TAG` 包裹输出
- 结果：`EntryAbility.abc` + `Index.abc` 均加载成功，Hello World 渲染

#### 研究产出文档（`/data/share/hoa2/`）

30 份技术调研文档，覆盖：加载流程、构建系统、核心不兼容点、运行时子系统、Stage 模型、渲染、NAPI 模块机制等。

---

## 待完成（修订后路线）

### Phase 1: 构建验证（1-2 周）

基于 ArkUI-X 构建系统 + OHOS 源码 + 3 个定向 Patch，编译 libohos_android.so

### Phase 2: ABC 加载验证（1-2 周）

先用 ArkUI-X 格式 HAP 验证基线，再用 OHOS 格式 HAP 验证 Patch 效果

### Phase 3: @ohos: 模块依赖解决（2-3 周）

NAPI 模块 stub/端口，分 3 级处理

### Phase 4: Ability 生命周期集成（2 周）

适配 JsAbility、JNI 渠道、instanceName 解析

### Phase 5: ArkUI 渲染集成（2 周）

DeclarativeFrontendNG + resources.index + SurfaceView 渲染

### Phase 6: 完整集成与测试（2 周）

端到端流程贯通

---

## 其他待办

- MainActivity 添加启动 StageActivityV2 的按钮（目前只能通过 adb 启动）
- 修复 `libimonitor.so` 加载警告（非致命，OHOS 特定库）

## 文档

- PLAN.md — 完整技术方案与落地路线
- FOUND.md — OHOS HAP 加载流程分析
- DETAIL.md — 旧版详细实施步骤（Phase 5-9，已被新 PLAN.md 取代）
- BUILD.md — 构建文档
