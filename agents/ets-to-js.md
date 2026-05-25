# ETS → JS 转译模式参考

来自 `advanced_ui_component/*/source/*.ets` 与 `interfaces/*.js` 的对照研读，
用于指导 HDS stub 的 JS 手写代码，确保与 ETS 编译产物的 API 使用一致。

---

## 1. 组件结构体

| ETS | JS |
|-----|-----|
| `@Component export struct Foo` | `export class Foo extends ViewPU` |
| `@CustomDialog struct Bar` | `class Bar extends ViewPU` |
| `@Builder export function Baz(opts)` | `export function Baz(opts, parent = null)` |

每个 ViewPU 子类必须实现：

```js
rerender() { this.updateDirtyElements(); }
```

---

## 2. 属性装饰器 → SynchedProperty 映射

### @Prop

| ETS 类型 | JS |
|----------|-----|
| 原始类型 (`number`, `boolean`, `string`) | `new SynchedPropertySimpleOneWayPU(params.p, this, 'p')` |
| 对象类型 (`Resource`, `DividerModifier`, 接口) | `new SynchedPropertyObjectOneWayPU(params.p, this, 'p')` |

### @State

| ETS 默认值 | JS |
|------------|-----|
| 原始类型 (`number`, `boolean`) | `new ObservedPropertySimplePU(defaultVal, this, 'p')` |
| 对象/数组 (`[]`, `{}`) | `new ObservedPropertyObjectPU(defaultVal, this, 'p')` |

### @Link, @ObjectLink

| ETS | JS |
|-----|-----|
| `@Link arr: number[]` | `new SynchedPropertyObjectTwoWayPU(params.arr, this, "arr")` |
| `@Link val: number` | `new SynchedPropertySimpleTwoWayPU(params.val, this, "val")` |
| `@ObjectLink obj: T` | `new SynchedPropertyNesedObjectPU(params.obj, this, 'obj')` |

### @Consume / @Provide

```js
// @Consume name: Type
this.initializeConsume("name", "name");

// @Provide
this.addProvidedVar("name", this.__prop, false);
```

### @Watch

```js
// @Watch('onChange') propName
this.declareWatch("propName", this.onChange);
```

### Getter/Setter 生成

每个响应式属性生成一对访问器：

```js
get fontSize() { return this.__fontSize.get(); }
set fontSize(v) { this.__fontSize.set(v); }
```

---

## 3. 构造函数模板

```js
constructor(parent, params, __localStorage, elmtId = -1, paramsLambda = undefined, extraInfo) {
    super(parent, __localStorage, elmtId, extraInfo);

    if (typeof paramsLambda === 'function') {
        this.paramsGenerator_ = paramsLambda;
    }

    // 1. 非响应式字段直接赋值
    this.controller = new TabsController();

    // 2. 响应式属性 → SynchedProperty
    this.__prop1 = new SynchedPropertySimpleOneWayPU(params.prop1, this, 'prop1');
    this.__prop2 = new ObservedPropertySimplePU(defaultVal, this, 'prop2');

    // 3. 默认值处理
    this.setInitiallyProvidedValue(params);

    // 4. @Watch 绑定
    this.declareWatch("prop1", this.onProp1Change);

    // 5. 完成
    this.finalizeConstruction();
}
```

### setInitiallyProvidedValue — 默认值回填

```js
setInitiallyProvidedValue(params) {
    if (params.activateIndex === undefined) {
        this.__activateIndex.set(-1);   // @Prop 默认值
    }
    if (params.moreText === undefined) {
        this.__moreText.set({ 'id': -1, 'type': 10003, params: ['sys.string.xxx'], ... });
    }
}
```

### updateStateVars — 父组件驱动更新

```js
updateStateVars(params) {
    this.__toolBarList.set(params.toolBarList);       // 嵌套对象用 .set()
    this.__activateIndex.reset(params.activateIndex);  // 单向绑定用 .reset()
}
```

### purgeVariableDependenciesOnElmtId + aboutToBeDeleted

```js
purgeVariableDependenciesOnElmtId(rmElmtId) {
    this.__prop1.purgeDependencyOnElmtId(rmElmtId);
    this.__prop2.purgeDependencyOnElmtId(rmElmtId);
}

aboutToBeDeleted() {
    this.__prop1.aboutToBeDeleted();
    this.__prop2.aboutToBeDeleted();
    SubscriberManager.Get().delete(this.id__());
    this.aboutToBeDeletedInternal();
}
```

---

## 4. build() → initialRender()

ETS 声明式嵌套 → JS 命令式 `.create()` / `.pop()` 对，每层包裹在 `observeComponentCreation2()` 中。

```js
initialRender() {
    this.observeComponentCreation2((elmtId, isInitialRender) => {
        Column.create();
        Column.width('100%');
    }, Column);

    this.observeComponentCreation2((elmtId, isInitialRender) => {
        Row.create();
    }, Row);
    Row.pop();

    Column.pop();
}
```

**关键规则**：`observeComponentCreation2` 的第二个参数是组件构造函数（如 `Column`、`Row`、`Button`、`Image`），用于脏检查和视图形状匹配。

---

## 5. 子组件创建

两种模式：

### 模式 A — 新实例（初始渲染）

```js
this.observeComponentCreation2((elmtId, isInitialRender) => {
    if (isInitialRender) {
        let comp = new ChildComponent(this, { prop: val }, undefined, elmtId, () => {}, {});
        ViewPU.create(comp);
        let paramsLambda = () => { return { prop: val }; };
        comp.paramsGenerator_ = paramsLambda;
    } else {
        this.updateStateVarsOfChildByElmtId(elmtId, { prop: val });
    }
}, { name: "ChildComponent" });
```

### 模式 B — @Builder 方法

```js
this.myBuilderMethod.bind(this)(arg1, arg2);
```

---

## 6. 条件语句 If/Else

```js
this.observeComponentCreation2((elmtId, isInitialRender) => {
    If.create();
    if (condition1) {
        this.ifElseBranchUpdateFunction(0, () => {
            this.observeComponentCreation2((elmtId, isInitialRender) => {
                Image.create(icon);
            }, Image);
        });
    } else if (condition2) {
        this.ifElseBranchUpdateFunction(1, () => {
            // ...
        });
    } else {
        this.ifElseBranchUpdateFunction(2, () => { });  // 空分支
    }
}, If);
If.pop();
```

**规则**：
- 分支索引从 0 开始递增
- 空 else 分支不能省略
- 每个分支内部组件的 `observeComponentCreation2` 必须嵌套在 `ifElseBranchUpdateFunction` 回调中

---

## 7. ForEach 循环

```js
this.observeComponentCreation2((elmtId, isInitialRender) => {
    ForEach.create();
    const forEachItemGenFunction = (_item, index) => {
        const item = _item;
        this.observeComponentCreation2((elmtId, isInitialRender) => {
            Row.create();
        }, Row);
        this.itemBuilder.bind(this)(index);
        Row.pop();
    };
    this.forEachUpdateFunction(elmtId, this.list, forEachItemGenFunction,
        (item, index) => `${this.getUniqueId()}__${index}`,  // key 生成
        true,    // createComponent
        true     // migration
    );
}, ForEach);
ForEach.pop();
```

---

## 8. 事件处理器

直接转译，链式调用保留：

```js
Row.onClick(() => { this.handleClick(index); });
Image.onSizeChange((oldV, newV) => { ... });
Button.onHover((isHover) => { ... });
Button.focusable(true);
Button.accessibilityText('label');
```

### stateStyles 需要特殊处理

```js
ViewStackProcessor.visualState('pressed');
Button.backgroundColor(color);
ViewStackProcessor.visualState();
```

### Gesture 是独立块

```js
globalThis.Gesture.create(GesturePriority.Low);
TapGesture.create();
TapGesture.onAction(() => { ... });
TapGesture.pop();
globalThis.Gesture.pop();
```

---

## 9. 资源引用 $r()

| `$r` 参数 | type | 含义 |
|-----------|------|------|
| `$r('sys.color.xxx')` | `10001` | 颜色 |
| `$r('sys.float.xxx')` | `10002` | 尺寸/浮点 |
| `$r('sys.string.xxx')` | `10003` | 字符串 |
| `$r('sys.media.xxx')` | `20000` | 媒体资源 |
| `$r('sys.symbol.xxx')` | `40000` | SymbolGlyph |

JS 形式：

```js
{ 'id': -1, 'type': 10001, params: ['sys.color.icon_primary'],
  'bundleName': '__harDefaultBundleName__', 'moduleName': '__harDefaultModuleName__' }
```

> 注意：`__harDefaultBundleName__` 和 `__harDefaultModuleName__` 是构建时的占位符。ArkUI-X 运行时会替换这些值。HDS stub 中不需要使用资源引用（HAP 传入的是具体值而非资源 ID）。

---

## 10. @Observed 类

```js
let ToolBarOption = class ToolBarOption {
    constructor() {
        this.content = '';
        this.action = undefined;
        this.icon = undefined;
    }
};
ToolBarOption = __decorate([Observed], ToolBarOption);
```

- 普通类（无装饰器）保持不变，不经过 `__decorate`
- 继承 Array 的 @Observed 类同样走 `__decorate` 包装

> HDS stub 的 ActionBarButton/ActionBarStyle 是纯数据容器，不需要 `@Observed`（HAP 传入后不修改）。

---

## 11. 导入映射

| ETS import | JS |
|------------|-----|
| `import { X } from '@ohos.A.B'` | `const X = requireNapi('A.B').X` |
| `import X from '@ohos.A.B'` | `const X = requireNapi('A.B')` |
| `import { X, Y } from '@kit.ArkUI'` | 按子模块拆分为多个 `requireNapi` |
| `import { BusinessError } from '@ohos.base'` | 省略（仅类型，无运行时 JS） |

---

## 12. @Builder function 包装器（组件导出入口）

这是 HDS stub 可能需要的模式——当要导出一个可被外部实例化的组件时：

```js
export function Chip(options, parent = null) {
    (parent ? parent : this).observeComponentCreation2((elmtId, isInitialRender) => {
        if (isInitialRender) {
            let comp = new ChipComponent(parent ? parent : this, {
                chipSize: options.chipSize,
                allowClose: options.allowClose,
                // ...
            }, undefined, elmtId, () => {}, {});
            ViewPU.create(comp);
            let paramsLambda = () => { return { chipSize: options.chipSize, ... }; };
            comp.paramsGenerator_ = paramsLambda;
        } else {
            (parent ? parent : this).updateStateVarsOfChildByElmtId(elmtId, { ... });
        }
    }, { name: 'ChipComponent' });
}
```

---

## 对 HDS Stub 的适用建议

1. **当前 HDS mock 的 ViewPU 写法基本正确**。`observeComponentCreation2`、`If.create`/`ifElseBranchUpdateFunction`、`.create()`/`.pop()` 的使用与 ETS 编译产物一致。

2. **不需要 SynchedProperty**。HDS stub 的参数全部来自 HAP 传入的 `params`，不涉及状态变更。当前用普通 `this.__xxx = params?.xxx` 赋值即可。

3. **不需要 @Observed 装饰器**。ActionBarButton / ActionBarStyle 是纯数据传递，不需要响应式包裹。

4. **不需要 ForEach**。ActionBar 的按钮组是静态的（HAP 不动态增删按钮），用 `for` 循环遍历 `this.__startButtons` 即可。如需差量更新，才考虑迁移到 ForEach。

5. **子组件实例化模式**：如果未来需要将 `_renderButton` 独立为 ViewPU 子组件，参考模式 A（新实例 + `updateStateVarsOfChildByElmtId`）。

6. **Button.createWithChild 不可靠**。ArkUI-X 的 Button API 支持度差（ButtonType 枚举缺失、justifyContent 无效），继续使用 Row + borderRadius 模拟按钮。
