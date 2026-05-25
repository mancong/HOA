# HDS Stub 解决方案（已实施 2026-05-25）

## 问题本质：两条解析路径

HDS 组件（ActionBar 等）在 HAP bytecode 中有两条独立的解析路径，必须同时处理：

| 路径 | 格式 | 机制 | 处理方式 | 状态 |
|------|------|------|----------|------|
| ES module import | `import { HdsActionBar } from '@kit.UIDesignKit'` | `IsNativeModule()` → `requireNapi("hds.hdsBaseComponent")` | 嵌入式 ABC mock（ABC-only 模式） | ✅ |
| ABC record ref | `com.huawei.hmos.hdscomponent/HdsComponent/ets/pages/HdsActionBar` | `LoadJSPandaFile` → record lookup in ABC file | `ReplaceModuleThroughFeature` 重定向 | ✅ |

## 背景：OHOS 原生系统的模块解析

### OHOS 正常流程

1. **配置文件**：设备上存在 `/system/etc/system_kits_config.json`：
   ```json
   {
     "systemkits": [{
       "namespace": "@hms:hds.hdsBaseComponent",
       "targetohm": "实际HSP模块路径",
       "sinceVersion": 18
     }]
   }
   ```

2. **运行时注册**：`js_runtime.cpp` 在 VM 启动时读取配置，调用 `JSNApi::SetHmsModuleList(vm, systemKitsMap)` 注册到 `EcmaVM::hmsModuleList_`。

3. **模块解析**：`module_resolver.cpp:ReplaceModuleThroughFeature()` 查询映射表，将请求路径替换为 `targetohm`（实际 HSP 包路径），加载对应 ABC 模块。

### ArkUI-X 缺失了什么

- `js_runtime.cpp` 从未调用 `SetHmsModuleList`
- 没有 `system_kits_config.json`
- 没有 HDS 组件的 ABC 实现模块
- 结果：所有 `@hms:` 前缀的导入必然失败

### ets_runtime 中的三条解析路径

`ReplaceModuleThroughFeature()` 依次检查：
1. **Mock 模块**（`IsMockModule`）— 可将任意模块重定向到另一个模块
2. **HMS 模块**（`IsHmsModule`）— 通过 `system_kits_config.json` 映射（ArkUI-X 未用）
3. **Native module 回退** — 将 `@hms:xxx` 拆解为 `requireNapi("xxx")`

我们利用路径 3 来实现重定向。

## 最终方案：ReplaceModuleThroughFeature 重定向

### 核心 patch — module_resolver.cpp

```cpp
// HOA: redirect HDS HSP record references to NAPI stub.
// HAP bytecode compiled by the HMS SDK may embed direct references to
// records like "com.huawei.hmos.hdscomponent/HdsComponent/ets/pages/HdsActionBar".
// On Android there is no real HDS HSP, so we route these through the
// hds.hdsBaseComponent NAPI module which delegates every component to an
// ArkUI built-in (Column, Button, etc.).
if (requestName.find("com.huawei.hmos.hdscomponent") != CString::npos) {
    requestName = "@hms:hds.hdsBaseComponent";
}
```

位置：`ReplaceModuleThroughFeature()` 末尾（`module_resolver.cpp:158`）

### 为什么 `@hms:` 前缀可行

调用链：

```
ReplaceModuleThroughFeature: requestName = "@hms:hds.hdsBaseComponent"
  ↓
IsNativeModule("@hms:hds.hdsBaseComponent") → true (有 @、含 :)
  ↓
GetNativeModuleType("@hms:...") → INTERNAL_MODULE (不匹配 @ohos:/@app:/@native:)
  ↓
ResolveNativeModule → ParseNativeModule → 创建模块记录
  ↓
LoadNativeModuleImpl → GetStrippedModuleName("@hms:hds.hdsBaseComponent")
  ↓
  → "hds.hdsBaseComponent" (截取 : 之后)
  ↓
requireNapi("hds.hdsBaseComponent") → 找到 NAPI stub ✅
```

关键函数 `GetStrippedModuleName`（`path_helper.h:86`）：
```cpp
inline static CString GetStrippedModuleName(const CString &moduleRequestName) {
    size_t pos = moduleRequestName.find(COLON_TAG);
    return moduleRequestName.substr(pos + 1);
}
```

所以 NAPI stub 注册名无需带 `@hms:` 前缀，`GetStrippedModuleName` 会自动去掉。

### 注册的 NAPI 模块名

`hds_base_component_stub.cpp` 中注册了：
- `hds.hdsBaseComponent` — 通过 `@kit.UIDesignKit` / `@hms:hds.hdsBaseComponent` 加载
- `UIDesignKit` — 通过 `@kit.UIDesignKit` 直接加载

---

## HDS 组件清单——两个独立来源

### 来源 A：HMS SDK 声明文件（权威，运行时真实存在）

文件：`/apps/harmony/sdk/default/hms/ets/api/@hms.hds.hdsBaseComponent.d.ets`
版本：`@since 5.1.0(18)`，Kit: `UIDesignKit`

SDK 实际导出的 UI 组件：

| 组件 | 本质 | ArkUI 对应 |
|------|------|------------|
| `HdsNavigation` | Navigation 的 HDS 设计规范包装 | `Navigation` |
| `HdsNavDestination` | NavDestination 的 HDS 设计规范包装 | `NavDestination` |
| `HdsActionBar` | 顶部操作栏（`@ComponentV2`，6.0.0(20)） | 无直接等价 |
| `ActionBarButton` | 操作栏按钮 | 无直接等价 |

附加：枚举 2 个（`ScrollEffectType`、`HdsNavigationTitleMode`）、类型别名 5 个、接口 16 个、属性类 2 个。总计 30 个导出。

独立模块：
- `@hms.hds.hdsDrawable` — 6 个 HDS 图标处理函数
- `@hms.hds.analogclock` — 虚拟模块（无声明文件，仅 `AnalogClockAttribute`/`AnalogClockOnHourCallback` 两个类型引用）

### 来源 B：ArkUI-X 编译器扩展白名单（编译时接受，运行时未必存在）

文件：`third_party/typescript/src/compiler/ohApi.ts:1541-1555`

ArkUI-X 对 TypeScript 编译器的补丁，定义了 `extendComponentWhiteList`，让 ETS→ABC 编译器在编译阶段接受这些组件名（否则会报"未知组件"错误）。

白名单中比 SDK 声明文件多出的组件名：

| 组件 | ArkUI 对应 | SDK 状态 |
|------|------------|----------|
| `HdsTabs` | `Tabs` | SDK 未发布 |
| `HdsListItemCard` | `ListItem` | SDK 未发布 |
| `HdsVisualComponent` | — | SDK 未发布 |
| `DotMatrix` | — | SDK 未发布 |
| `Metaball` | — | SDK 未发布 |
| `AudioWave` | — | SDK 未发布 |
| `MultiWindowEntryInAPP` | — | SDK 未发布 |

编译器和 SDK 是两个独立系统，白名单比 SDK 声明更宽泛。如果未来有 HAP 在编译时使用了这些名称（编译器不会报错），但运行时找不到实现，stub 需提供占位。

### 编译器常量

`developtools/ace_ets2bundle/compiler/src/pre_define.ts:715-716`：
```typescript
export const HDSNAVIGATION: string = 'HdsNavigation';
export const HDSNAVDESTINATION: string = 'HdsNavDestination';
```
编译器只对这两个 HDS 组件名做了特殊处理（`process_component_build.ts` 中 `equalToHiddenNav` / `equalToHiddenNavDes` 判断）。

---

## Stub 组件委托策略

### 三级策略

| 级别 | 做法 | 适用场景 |
|------|------|----------|
| **一级：1:1 委托** | 映射到等价 ArkUI 全局类 | SDK 已发布的组件 |
| **二级：组合实现** | 用 Row/Column/Button 等组合构建 | 无直接等价但可近似 |
| **三级：空桩占位** | 返回 undefined | 纯视觉特效，无交互功能 |

### 当前委托映射

| HDS 导出 | 委托至 | 策略 |
|----------|--------|------|
| `HdsActionBar` | ViewPU 组合（Row + Button + Image） | **二级：组合实现** ✅ |
| `ActionBarButton` | JS class（属性容器） | 二级 ✅ |
| `ActionBarStyle` | JS class（属性容器） | 二级 ✅ |
| `PrefixImage` | global `Image` | 一级 ✅ |
| `SuffixButton` | global `Button` | 一级 ✅ |
| `SuffixArrowIconText` | global `Row` | 一级 ✅ |
| `HdsNavigation` | global `Navigation` | 一级 ✅ |
| `HdsNavDestination` | global `NavDestination` | 一级 ✅ |
| `HdsTabs` | global `Tabs` | 一级 ✅ |
| `HdsListItemCard` | global `ListItem` | 一级 ✅ |
| `HdsListItem` | global `ListItem` | 一级 ✅ |

### 枚举

| 导出 | 值 | 状态 |
|------|-----|------|
| `ScrollEffectType` | `{ COMMON_BLUR: 0 }` | ✅ |
| `HdsNavigationTitleMode` | `{ FREE: 0, FULL: 1, MINI: 2 }` | ✅ |
| `DividerMode` | `{ AUTO: 0, ALWAYS: 1, NONE: 2 }` | ✅ |
| `HdsNavDestinationTitleMode` | `{ FREE: 0, FULL: 1, MINI: 2 }` | ✅ |

### Instance / Attribute 桩函数

返回 `undefined`（`StubCallback`），框架对此容忍度尚可：

```
HdsNavigationInstance, HdsNavDestinationInstance,
HdsNavigationAttribute, HdsNavDestinationAttribute,
HdsTabsInstance, HdsTabsAttribute,
HdsListItemCardInstance, HdsListItemCardAttribute
```

### SDK 类型/接口（不需要运行时实现）

30 个导出中有 23 个是类型声明（type alias + interface + enum）— 这些只在编译期存在，HAP 的 ABC 字节码中不包含它们，运行时无需实现。

---

## HdsActionBar 分析：ArkUI-X 无直接等价组件

### SDK 声明（`@hms.hds.HdsActionBar.d.ets`）

`HdsActionBar` 是 `@ComponentV2` struct（`@bundle com.huawei.hmos.hdscomponent/HdsComponent/ets/pages/HdsActionBar 6.0.0(20)`），属性包括：
- `primaryButton?: ActionBarButton` — 主按钮
- `startButtons?: Array<ActionBarButton>` — 左侧按钮组
- `endButtons?: Array<ActionBarButton>` — 右侧按钮组
- `actionBarStyle?: ActionBarStyle` — STANDARD / EMPHASIS
- `isExpand?: boolean` — 展开/折叠
- `$isExpand?: Callback<boolean>` — 展开状态变化事件
- `blurStrategy?: BlurStrategy` — 滚动毛玻璃

### ArkUI-X 已注册的全局组件（无直接等价）

ArkUI-X 注册了 180+ JS 全局组件（`jsi_view_register_impl_ng.cpp`），其中最接近的是：
- `JSAppBar` — 原子化服务专用（`RequestAtomicServiceTerminate`、`OnCreateServicePanel`），**非**通用 ActionBar
- `JSButton` — 基础按钮，缺少 `baseIcon`/`altIcon`/`shadowStyle`/`iconFillColor` 等 HDS 属性
- `Navigation` + `NavDestination` — toolbar 在 Navigation 上下文内，不能独立使用

**没有等价的通用顶部操作栏组件。**

### 升级路径：组合实现 ActionBar

可以用 ArkUI 基础组件组合构建功能等价的 ActionBar：

```
HdsActionBar → 容器组合:
  Stack
    ├─ Row(titleBar)     — 标题行
    │   ├─ Button(start)  — startButtons
    │   ├─ Text(title)    — 标题区域
    │   └─ Button(end)    — endButtons
    └─ Button(primary)   — 浮动主按钮
```

---

## 组合式 UI 实现（已完成 2026-05-25）

### 问题回顾：Column 委托为什么不行（已解决）

最初 `HdsActionBar` 被委托给全局 `Column`（NAPI `napi_get_named_property(env, global, "Column", &comp)`）。HAP 执行 `HdsActionBar({ primaryButton: ..., startButtons: ... })` 时，Column 不认识这些属性，静默丢弃，渲染为空白。

**解决方案**：改用 JS 源码编写 ViewPU 组合组件（`src/hds_component_mock.js`），通过 `es2abc --module` 编译为 ABC，嵌入 .so 中以 ABC-only 模式加载。ABC 中的 JS 可以使用 `observeComponentCreation2()` API 构建组件树，NAPI 层无法使用此 API。

### 为什么 NAPI C++ 无法创建组合式组件

ArkUI 组件（Column、Row、Button 等）不是普通 JS 函数，它们是通过 `JSBind` 注册的 C++ 类，有固定的内部实现。`napi_create_function` 创建的只是普通 JS 函数，**ArkUI 框架不会把它当作组件构造器来实例化**。

`@ComponentV2 struct` 在编译时被 es2panda 转为 PANDA 字节码，内部调用 `observeComponentCreation2()` API 来构建组件树。这个 API 是从 ABC 字节码中调用的，NAPI 层无法直接使用。

**结论：要提供真正的组合式组件，必须走 ETS 源码 → ABC 编译 → 运行时加载的路径。**

### ArkUI-X 现有模式：插件 ABC 嵌入

所有带 UI 的 ArkUI-X 插件（popup、dialog、chip、toolbar 等 25+ 个）都使用同一套三阶段构建管线：

```
ETS/JS 源码  →  es2abc  →  .abc 文件  →  llvm-objcopy 嵌入  →  .o 对象文件
                                                                    ↓
                                                  链接进 .so，通过 _binary_*_start/end 符号访问
                                                                    ↓
                                                  napi_module_with_js_register() 注册
```

#### Phase 1: es2abc 编译（BUILD.gn）

```gn
import("//build/config/components/ets_frontend/es2abc_config.gni")

es2abc_gen_abc("gen_hds_abc") {
  src_js = rebase_path("src/hds_component.js")
  dst_file = rebase_path(target_out_dir + "/hds.abc")
  extra_args = [ "--module" ]
}
```

#### Phase 2: 二进制嵌入（平台区分）

- **Android**: `gen_js_obj` → `llvm-objcopy -I binary` → `.o` 文件
- **iOS**: `gen_obj` → `build_resource_to_bytecode.py` → `.c` 文件

#### Phase 3: 链接 + C++ 注册（最终实现）

```cpp
// 链接器提供的符号（file scope，C linkage）
extern const char _binary_hds_mock_abc_start[];
extern const char _binary_hds_mock_abc_end[];

// ABC 回调
extern "C" __attribute__((visibility("default")))
void NAPI_hds_GetABCCode(const char **buf, int *buflen) {
    if (buf) *buf = _binary_hds_mock_abc_start;
    if (buflen) *buflen = _binary_hds_mock_abc_end - _binary_hds_mock_abc_start;
}

// ABC-only 模式：nm_register_func = nullptr
static napi_module_with_js g_hdsBaseComponentModule = {
    .nm_version  = 1,
    .nm_flags    = 0,
    .nm_filename = nullptr,
    .nm_register_func = nullptr,     // ABC-only，无 NAPI 导出
    .nm_modname  = "hds.hdsBaseComponent",
    .nm_priv     = nullptr,
    .nm_get_abc_code = NAPI_hds_GetABCCode,
    .nm_get_js_code = nullptr,
};

extern "C" __attribute__((constructor)) void RegisterHdsBaseComponent() {
    napi_module_with_js_register(&g_hdsBaseComponentModule);
}

// 同一 ABC，第二个模块名（@kit.UIDesignKit 路径）
static napi_module_with_js g_uidKitModule = { /* ... same ABC ... */ };
```

`napi_module_with_js` 结构定义（`native_node_api.h:35`）：

```cpp
typedef struct napi_module_with_js {
    int nm_version;
    unsigned int nm_flags;
    const char* nm_filename;
    napi_addon_register_func nm_register_func;  // NAPI 导出函数（可为 nullptr）
    const char* nm_modname;
    void* nm_priv;
    NAPIGetJSCode nm_get_abc_code;              // ABC 字节码回调
    NAPIGetJSCode nm_get_js_code;               // JS 源码回调（可选）
} napi_module_with_js;
```

运行时行为（`LoadNativeModule` in `ark_native_engine.cpp`）：
- **ABC-only 模式**（`nm_register_func = nullptr`）：仅执行 ABC 字节码，`GetExportObjectFromBuffer(fileName, "default")` 获取 default export 作为模块导出
- **NAPI-only 模式**（`nm_get_abc_code = nullptr`）：仅调用 `nm_register_func` 设置 exports
- **混合模式**（两者非 null）：ABC 和 NAPI 是 if-else 互斥；有 ABC 时不执行 NAPI

HDS stub 使用 ABC-only 模式，所有导出在 JS 源码中定义。

### 关键挑战

#### 1. 源码格式

ArkUI-X 现有插件的 JS 源码是**预编译产物**（ETS → transpiler → JS），使用了内部 ViewPU API：

```javascript
export class d1 extends ViewPU {
  constructor(parent, params, __localStorage, elmtId, ...) {
    super(parent, __localStorage, elmtId, extraInfo);
    this.__title = new SynchedPropertyObjectOneWayPU(...);
  }
  initialRender() {
    this.observeComponentCreation2(...);  // 创建子组件
  }
}
```

这套 API 未公开文档，且随版本变化。直接用此格式手写复杂组件不现实。

#### 2. ETS 编译

es2panda 可以编译 `@ComponentV2` 语法的 ETS 源码直接生成 PANDA ABC。但需要：
- ArkUI SDK 声明文件（`@ComponentV2`、`Row`、`Button` 等类型定义）
- 正确的编译参数（`--ets-sdk`、`--arkts-identifiers` 等）
- 确认 hds 插件的 GN 构建上下文能访问这些依赖

#### 3. @ObservedV2 class

`ActionBarButton` 和 `ActionBarStyle` 是 `@ObservedV2 class`（非 `@ComponentV2 struct`）。HAP 代码用 `new ActionBarButton({...})` 创建实例后作为参数传入。我们的 ABC 必须提供这两个类的定义，否则 `new` 调用会失败。

#### 4. 模块命名空间（已解决）

采用 ABC-only 模式（`nm_register_func = nullptr`），所有导出统一在 JS 源码中通过 `export default { ... }` 提供。不存在 NAPI/ABC 合并冲突问题。

### 实施路线（已完成）

| 步骤 | 内容 | 状态 |
|------|------|------|
| 1. 写 JS 源码 | `src/hds_component_mock.js`：ViewPU 组合 HdsActionBar + ActionBarButton/ActionBarStyle class + 所有导出 | ✅ |
| 2. 改 BUILD.gn | `es2abc_gen_abc` + `gen_js_obj` + `ohos_source_set` 三步流水线 | ✅ |
| 3. 改 C++ stub | ABC-only 模式（`nm_register_func = nullptr`），注册 hds.hdsBaseComponent + UIDesignKit 双模块名 | ✅ |
| 4. 编译验证 | ABC 44100 bytes，`Evaluate done, hasException=0` | ✅ |
| 5. 功能验证 | hw_base_calendar ActionBar 区域可见，按钮渲染 | ✅ |

**关键发现**：`export default { ... }` 是必须的——缺失导致 `GetExportObjectFromBuffer("default")` 走 index=0 返回错误值，所有 named export 不可见。

### 参考文件

| 文件 | 作用 |
|------|------|
| `plugins/hms/hds/src/hds_component_mock.js` | JS mock 源码（ViewPU + 所有导出） |
| `plugins/hms/hds/hds_base_component_stub.cpp` | C++ 注册（ABC-only，双模块名） |
| `plugins/hms/hds/BUILD.gn` | 三步构建流水线 |
| `plugins/arkui/advanced/popup/BUILD.gn` | 插件构建参考 |
| `plugins/arkui/advanced/popup/interfaces/popup.js` | ViewPU + export default 模式参考 |
| `foundation/arkui/napi/interfaces/inner_api/napi/native_node_api.h:35` | `napi_module_with_js` 结构定义 |
| `build/config/components/ets_frontend/es2abc_config.gni` | `es2abc_gen_abc` GN 模板 |
| `foundation/arkui/ace_engine/build/ace_gen_obj.gni` | `gen_js_obj` GN 模板 |

---

## ArkUI-X 构建系统参考（from `build/CLAUDE.md`）

### GN 模板体系

ArkUI-X 使用 GN 构建系统，核心模板：

| 模板文件 | 模板名 | 作用 |
|----------|--------|------|
| `build/config/components/ets_frontend/es2abc_config.gni` | `es2abc_gen_abc` | ETS/JS → ABC 编译 |
| `foundation/arkui/ace_engine/build/ace_gen_obj.gni` | `gen_js_obj` | ABC → `.o` 嵌入（Android/OHOS） |
| `foundation/arkui/ace_engine/build/ace_gen_obj.gni` | `gen_obj` | ABC → `.c` 嵌入（preview/iOS） |

### gen_obj / gen_js_obj 模板详解

**`gen_js_obj`**（Android/OHOS 用）：调用 `llvm-objcopy -I binary -B <arch> -O elf64-<arch> input.abc output.o`，生成 `.o` 对象文件，链接器自动产出 `_binary_<name>_start` / `_binary_<name>_end` 符号。

**`gen_obj`**（Preview/iOS 用）：调用 `build_resource_to_bytecode.py`，将二进制文件转为 C 数组：
```c
extern const uint8_t _binary_toolbar_abc_start[];
extern const uint8_t _binary_toolbar_abc_end[];
const uint8_t _binary_toolbar_abc_start[] = { 0x50, 0x41, 0x4e, ... };
const uint8_t _binary_toolbar_abc_end[] = {};
```

### 平台 objcopy 工具选择

| 平台 | 工具路径 |
|------|----------|
| Android arm64 | `prebuilts/clang/.../llvm-objcopy` |
| OHOS arm64 | 同上 |
| Linux x86_64 | `prebuilts/clang/.../llvm-objcopy` |
| macOS / iOS | 使用 `gen_obj` 走 `.c` 路径（macOS objcopy 不支持 Mach-O binary input） |
| Windows | 使用 `gen_obj` 走 `.c` 路径 |

### BUILD.gn 完整示例（toolbar 插件）

```gn
import("//build/config/components/ets_frontend/es2abc_config.gni")
import("//foundation/arkui/ace_engine/build/ace_gen_obj.gni")

es2abc_gen_abc("gen_toolbar_abc") {
  src_js = rebase_path("toolbar.js")
  dst_file = rebase_path(target_out_dir + "/toolbar.abc")
  in_puts = [ "toolbar.js" ]
  out_puts = [ target_out_dir + "/toolbar.abc" ]
  extra_args = [ "--module" ]
}

gen_js_obj("toolbar_abc") {
  input = get_label_info(":gen_toolbar_abc", "target_out_dir") + "/toolbar.abc"
  output = target_out_dir + "/toolbar_abc.o"
  dep = ":gen_toolbar_abc"
}

ohos_shared_library("toolbar") {
  sources = [ "toolbar.cpp" ]
  deps = [ ":toolbar_abc" ]
  external_deps = [ "hilog:libhilog", "napi:ace_napi" ]
  relative_install_dir = "module/arkui/advanced"
  subsystem_name = ace_engine_subsystem
  part_name = ace_engine_part
}
```

---

## advanced_ui_component JS 组合模式参考

`foundation/arkui/ace_engine/advanced_ui_component/` 包含 25+ 个 ArkUI 高级组件，全部使用 ViewPU + JS 组合实现。以下是对 HDS mock 最有参考价值的模式：

### 通用脚手架（所有组件共用）

```javascript
// 1. finalizeConstruction polyfill（兼容老版本 ViewPU）
if (!("finalizeConstruction" in ViewPU.prototype)) {
    Reflect.set(ViewPU.prototype, "finalizeConstruction", () => { });
}

// 2. requireNapi 引入运行时依赖
const LengthMetrics = requireNapi('arkui.node').LengthMetrics;
const SymbolGlyphModifier = requireNapi('arkui.modifier').SymbolGlyphModifier;

// 3. @Observed class（数据模型，配合 @ObservedV2 in ETS）
let ToolBarOption = class ToolBarOption { /* ... */ };
ToolBarOption = i([Observed], ToolBarOption);  // decorator 应用
export { ToolBarOption };
```

### Toolbar（最接近 ActionBar 的布局）

**布局**：Row 容器 + ForEach 遍历 items → 每个 item 是 `Button.createWithChild({ type: ButtonType.Normal })` 包裹 `Column(Image + Text)`。

**关键模式**：
- 使用 `ForEach` 遍历动态列表（比手动 for 循环更符合 ArkUI 范式）
- 图标渲染 3 分支：SymbolGlyph（矢量符号）/ Image（图片资源）/ 空（无图标）
- 状态感知：`ItemState.ENABLE` / `DISABLE` / `ACTIVATE` 影响颜色和交互

```javascript
// toolbar.js button rendering pattern
Button.createWithChild({ type: ButtonType.Normal, stateEffect: false });
Button.focusable(true);
Button.onClick(() => { item.action?.(); });
// ↓ 内部子组件
Column.create();
  Image.create(item.icon);
  Image.width('24vp');
  Image.height('24vp');
  Image.fillColor(isActive ? theme.iconEmphasize : theme.iconPrimary);
  Text(item.content);
  Text.fontSize(fontRes);
  Text.fontColor(isActive ? theme.fontEmphasize : theme.fontPrimary);
Column.pop();
Button.pop();
```

### Popup（export default 模式原型）

**关键发现**：popup.js 是第一个验证 `export default { ... }` 必须存在的参考组件——缺失 default export 会导致 `GetExportObjectFromBuffer("default")` 失败。

**模式**：
- 大量系统资源引用：`{ "id": -1, "type": 10001, params: ['sys.color.xxx'], ... }`
- 使用 `requireNapi('display')` 获取屏幕尺寸做自适应布局
- 使用 `requireNapi('mediaquery')` 做响应式断点

### Chip（紧凑组合模式）

**布局**：Button 包裹 Row(Image + Text + optional suffix)——适合需要图标+文字的紧凑控件。

### Counter（Stack + 相对定位）

**布局**：RelativeContainer 内 Stack(Image + Button) 对称放置在 Text 两侧——展示复杂定位的 JS 实现方式。

### C++ 注册两种方式

| 方式 | 结构体 | 特征 | 使用场景 |
|------|--------|------|----------|
| **旧方式** | `napi_module` | `NAPI_xxx_GetABCCode` 函数命名约定 | toolbar, chip 等 |
| **新方式** | `napi_module_with_js` | `nm_get_abc_code` 回调字段 | HDS stub, popup 等 |

旧方式 C++ 示例（toolbar.cpp）：
```cpp
extern const char _binary_toolbar_abc_start[];
extern const char _binary_toolbar_abc_end[];

extern "C" void NAPI_arkui_advanced_ToolBar_GetABCCode(const char **buf, int *buflen) {
    if (buf) *buf = _binary_toolbar_abc_start;
    if (buflen) *buflen = _binary_toolbar_abc_end - _binary_toolbar_abc_start;
}

static napi_module ToolBarModule = {
    .nm_version = 1,
    .nm_modname = "arkui.advanced.ToolBar",
};
extern "C" __attribute__((constructor)) void ToolBarRegisterModule() {
    napi_module_register(&ToolBarModule);
}
```

新方式（HDS stub 使用的）多了 `nm_get_abc_code` 字段，不依赖命名约定。

### 对 HDS mock 的启示

1. **Row+Image 优于 Button 包裹**：toolbar 用 `Button.createWithChild` 包裹 Column(Image+Text)，但在 ArkUI-X 上 ButtonType 枚举缺失。HDS mock 改用 Row + borderRadius 模拟圆形按钮更安全。

2. **ForEach vs 手动 for**：当前 HDS mock 用 `for (let i = 0; ...)` 遍历 startButtons/endButtons。如果需要支持列表变更时的差量更新，应改用 `ForEach`。但对 HDS ActionBar 这种静态按钮组，手动 for 足够。

3. **requireNapi 可用于获取运行时工具**：`requireNapi('arkui.node').LengthMetrics` 等可在 JS mock 中使用，但 HDS mock 场景下暂不需要（不做自适应）。

4. **@Observed 装饰器**：toolbar 使用 `i([Observed], ToolBarOption)` 模式将普通 class 变为可观察对象。HDS mock 的 ActionBarButton/ActionBarStyle 是纯数据容器，暂不需要响应式（HAP 传入后不会在 mock 内修改）。

---

## 相关文件

| 文件 | 作用 |
|------|------|
| `arkcompiler/ets_runtime/ecmascript/module/module_resolver.cpp:158` | `ReplaceModuleThroughFeature` — HDS HSP record 重定向 |
| `arkcompiler/ets_runtime/ecmascript/base/path_helper.h:86` | `GetStrippedModuleName` — 前缀去除 |
| `plugins/hms/hds/src/hds_component_mock.js` | JS mock 源码 — ViewPU HdsActionBar + 所有导出 |
| `plugins/hms/hds/hds_base_component_stub.cpp` | C++ 注册 — ABC-only 双模块名 |
| `plugins/hms/hds/BUILD.gn` | GN 构建 — es2abc + gen_js_obj + source_set 三步流水线 |
| `plugins/arkui/advanced/popup/BUILD.gn` | 插件三阶段构建参考 |
| `plugins/arkui/advanced/popup/interfaces/popup.js` | popup 插件 ViewPU + export default 模式参考 |
| `advanced_ui_component/toolbar/interfaces/toolbar.js` | Toolbar ViewPU 组合模式（最接近 ActionBar） |
| `advanced_ui_component/toolbar/interfaces/toolbar.cpp` | Toolbar C++ 注册（旧方式 napi_module） |
| `advanced_ui_component/toolbar/BUILD.gn` | Toolbar 构建（三阶段标准模式） |
| `advanced_ui_component/chip/interfaces/chip.js` | Chip 紧凑组合模式 |
| `advanced_ui_component/counter/interfaces/counter.js` | Counter 复杂定位模式 |
| `foundation/arkui/ace_engine/build/CLAUDE.md` | 构建系统文档（gen_obj、平台差异、ABC 嵌入） |
| `foundation/arkui/ace_engine/build/ace_gen_obj.gni` | gen_js_obj / gen_obj GN 模板 |
| `/apps/harmony/sdk/default/hms/ets/api/@hms.hds.hdsBaseComponent.d.ets` | HMS SDK HDS 组件声明 |
| `/apps/harmony/sdk/default/hms/ets/api/@hms.hds.HdsActionBar.d.ets` | HdsActionBar SDK 声明 |

## 清理工作

移除的预览器 HSP 方案：
- `HoaApplication.kt` — 删除 `deployHspFromAssets()` 方法及调用
- `app/src/main/assets/mock_hsp/` — 已删除（2.68MB SDK previewer HSP ABC）

## 运行时日志验证

```
D/ArkCompiler: [LoadNativeModuleImpl] Request module is @hms:hds.hdsBaseComponent
D/NAPI: [LoadNativeModule] moduleName is hds.hdsBaseComponent, path is (null), relativePath is hms
D/NAPI: [LoadNativeModule] load native module success
D/NAPI: [SetNativeEngine] module:'hds.hdsBaseComponent'
D/ArkCompiler: [LoadNativeModule] ABC buf size: 44100
D/ArkCompiler: Evaluate done, hasException=0
```

确认：HDS HSP record → `@hms:hds.hdsBaseComponent` → `GetStrippedModuleName` → `hds.hdsBaseComponent` → ABC-only 模块加载 → ViewPU 组件构造成功 ✅
