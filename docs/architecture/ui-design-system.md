# 基点前端视觉设计系统

## 当前目标：预览风格迁移

2026-09-29 用户确定采用指定 `stock-workspace-demo-v1.html` 的视觉风格。2026-09-30 进一步要求预览的功能内容与当前系统一致，并将名称改为“基点”；随后用户否定源码生成预览，已恢复原 V2 设计；后续补功能需保留其构图，不能以现有页面套主题替换。正式源码已完成公共视觉、空间外壳及选择页（UI-01）、共享自选（UI-02）、行情页／初始观点／共用图表（UI-03）、研究详情（UI-04）、空间概览（UI-05）、成员与设置（UI-06）、公开首页（UI-07）、认证与个人资料（UI-08）、治理后台（UI-09）；UI-10 负责全站一致性与旧实现退出。实施范围与边界见 [SPEC-003 需求](../specs/003-ui-migration/requirements.md)，参考基线、字段对应及预览机制见 [迁移设计](../specs/003-ui-migration/design.md)，顺序和证据见 [任务](../specs/003-ui-migration/tasks.md)。

以下是新迁移页面的视觉正文；不再要求用户重选审美。下方“旧视觉参考”中的 YAML 和正文是尚未迁移代码的参数／历史说明，不能用来否决本节已确认的新方向。

### 色彩与表面

- 以预览的暖白背景 `#FAF8F4`、面板 `#FFFDF9`、次级表面 `#F3EFE7`、墨色 `#191510`、细线 `#E3DDD1`、雾蓝强调 `#6E9FC4` 与深蓝 `#4E6E88` 为视觉基准；将其接入项目语义变量，不把每页复制一套变量。
- 保留预览的低对比坐标纸与轻噪点表达、细线分区、轻圆角和留白层级；图表和长正文应清楚可读，装饰不遮挡控件、不拦截操作、不跟随鼠标抖动。
- 上涨／下跌颜色服务行情语义，观点立场使用中性标签；错误、危险操作和其他状态另有语义，不能让装饰色冒充涨跌。
- 辅助正文至少满足 4.5:1 对比度，不机械复制预览偏淡的 `--dim`；边界和装饰的淡色不能直接当小字号正文色。

### 排版与控件

- 采用预览的中文衬线展示标题、无衬线正文、等宽数字与时间的层级方向，参考字体为 Noto Serif SC、Noto Sans SC／Space Grotesk、IBM Plex Mono。提供宋体、系统无衬线和等宽回退，外部字体失败时内容仍可读，禁止靠字体加载隐藏正文。
- 普通业务正文和控件保持易读；使用真实长名称、观点和多行反馈适配宽度，窄屏由多列转单列。不把预览固定文本长度当布局约束。
- 按预览统一按钮、输入框、标签、工具栏、表格和提示。补齐 focus、disabled、loading、error 状态；保留语义标签、表单 label 与现有校验反馈，不复制内联 onclick 或全局 DOM 路由脚本。
- 首页可以保留预览的构图和装饰节奏；业务内容默认可见，动效不得让快速切页、未触发观察器或后台标签页的内容永久透明。减少动态偏好下停用持续旋转、跑马灯与滚动显现，并保留静态内容。
- 首页走马灯不设暂停按钮，鼠标悬浮或焦点进入时继续滚动；减少动态偏好仍按上一条保留静态内容。此要求已应用于内容补齐副本和正式首页，原始设计备份保留。
- 原 V2 缺少的完整登录、注册、个人资料及管理表单已在内容补齐副本提供设计对应；正式页面用同一控件和分段语言接入真实校验、提交与反馈。具体位置与业务文案适配只在 SPEC-003 design 维护。

### 验收与迭代

保留键盘操作、明确焦点、状态不只依赖颜色和桌面／手机无页面横向溢出的要求；数据表需要横向阅读时使用局部可操作滚动区。字体、噪点和图表应检查实际浏览器加载与性能，不能凭源码宣称视觉效果已经验证。

每块先复用已确定的视觉和组件规则，再对照真实内容调整局部尺寸及文案；实际完成证据只写对应 tasks。迁移期间原布局只服务尚未迁移页面，不建设长期双主题产品；全站收口时移除旧参数与无引用旧视觉依赖，并更新本文为单一现行规范。

## 旧视觉参考（仅用于尚未迁移页面及历史解释）

以下截至文末描述轨道视觉的旧实现，包括原网格禁令、轨道组件、字体、配色及认证构图。这些不是 SPEC-003 的新页面约束；新旧冲突由上方“当前目标”取代。旧组件是否仍有引用须以实施时源码核对，不能因目标变化就提前删除。

```yaml
name: "同研自选（暂定）"
description: "旧视觉参数快照；新迁移目标见正文开头，旧参数不作为新页面准入规则"
colors:
  optical-white: "#FCFCFC"
  optical-ink: "#202224"
  optical-muted: "#62676B"
  optical-faint: "#AEB3B6"
  optical-line: "#D5D8DA"
  optical-focus: "#166D67"
  danger: "#B42318"
typography:
  display:
    fontFamily: "Microsoft YaHei UI, PingFang SC, Segoe UI Variable, sans-serif"
    fontSize: "clamp(2.7rem, 5.5vw, 5.5rem)"
    fontWeight: 560
    lineHeight: 1.04
    letterSpacing: "-0.04em"
  headline:
    fontFamily: "Microsoft YaHei UI, PingFang SC, Segoe UI Variable, sans-serif"
    fontSize: "1.875rem"
    fontWeight: 600
    lineHeight: 1.2
    letterSpacing: "-0.025em"
  body:
    fontFamily: "Microsoft YaHei UI, PingFang SC, Segoe UI Variable, sans-serif"
    fontSize: "0.9375rem"
    fontWeight: 400
    lineHeight: 1.72
  label:
    fontFamily: "Microsoft YaHei UI, PingFang SC, Segoe UI Variable, sans-serif"
    fontSize: "0.8125rem"
    fontWeight: 600
    lineHeight: 1.4
rounded:
  control: "10px"
  action: "999px"
spacing:
  xs: "4px"
  sm: "8px"
  md: "16px"
  lg: "24px"
  xl: "32px"
components:
  button-primary:
    backgroundColor: "{colors.optical-ink}"
    textColor: "{colors.optical-white}"
    rounded: "{rounded.action}"
    padding: "13px 18px"
    height: "50px"
  input:
    backgroundColor: "{colors.optical-white}"
    textColor: "{colors.optical-ink}"
    rounded: "{rounded.control}"
    height: "50px"
```

## Overview

**Creative North Star: “安静的光学观察室”**

界面借鉴用户提供的 Optical Motion Lab 参考页，以近黑、浅色平面和大面积留白构成基础。设计预览中确认的轨道图形与柔和色彩提供品牌记忆；正式页面不再使用会持续跳动的玻璃条视频，背景不读取鼠标位置，也不叠加等高线、网格或密集黑线。

公开首页使用整屏预览风格构图，并通过顶部导航进入登录或注册。认证页采用不对称双区结构：左侧是浅色平面与轨道视觉区，右侧是平面、稳定的账号操作区；进入空间、自选股列表和观点编辑后，继续沿用操作区的控件语言。

**Key Characteristics:**

- 大标题只用于入口、空状态和关键转折。
- 信息主要通过字号、留白与细线分组，避免卡片套卡片。
- 黑色主操作与极少量青绿色焦点色共同构成状态语言。
- 轨道图形只做缓慢、低干扰的CSS呼吸动效，不响应鼠标，并为减少动态偏好提供静态版本。

## Colors

色彩以无彩光学中性色为主，青绿色仅承担键盘焦点和少量系统状态。

### Primary

- **Optical Ink** (`#202224`): 标题、正文重点和主操作背景。
- **Optical Focus** (`#166D67`): 焦点环、链接和少量确认状态，不作大面积装饰。

### Neutral

- **Optical White** (`#FCFCFC`): 页面与表面背景。
- **Optical Muted** (`#62676B`): 辅助说明，保持正文级可读对比度。
- **Optical Faint** (`#AEB3B6`): 非正文装饰线与禁用视觉。
- **Optical Line** (`#D5D8DA`): 分隔线与输入边界。

**The Quiet Spectrum Rule.** 单个业务页面至少 90% 的面积保持中性色，彩色只表达焦点、状态或数据语义。

## Typography

**Display Font:** Microsoft YaHei UI（PingFang SC 与 Segoe UI Variable 回退）  
**Body Font:** Microsoft YaHei UI（PingFang SC 与系统无衬线回退）

**Character:** 中文排版清晰、理性，标题有适度紧凑感，正文保持舒展行距。产品控件不使用展示字体。

### Hierarchy

- **Display**（560，`clamp(2.7rem, 5.5vw, 5.5rem)`，1.04）：仅用于入口页与关键空状态。
- **Headline**（600，30px，1.2）：页面或表单主要标题。
- **Body**（400，15px，1.72）：说明文本，长文限制在约 70 个字符宽度。
- **Label**（600，13px，1.4）：表单标签、时间与状态。

**The One Large Voice Rule.** 一个视口只允许一个超大标题，业务数据和操作标签使用固定字号。

## Elevation

系统默认无阴影，以浅色表面、细线、透明度和层级排版表达深度。正式入口、认证区和空状态统一使用轨道图形；表单与业务容器保持平面。焦点使用清晰的 3px 外圈，不使用宽而虚的投影。

**The Flat-by-Default Rule.** 静态容器不同时使用边框和宽阴影，悬浮状态也不通过大幅上移制造漂浮感。

## Components

### Buttons

- **Shape:** 主按钮为胶囊形（999px），普通图标按钮为 10px 圆角。
- **Primary:** Optical Ink 背景、白字、高 50px，动词加对象作为标签。
- **Hover / Focus:** 180ms 明度变化；键盘焦点使用 3px Optical Focus 外圈。
- **Secondary:** 透明背景配 1px Optical Ink 下划线或边框，不复制主按钮重量。

### Cards / Containers

- **Corner Style:** 默认不使用卡片；确有独立对象时最大 14px。
- **Background:** Optical White。
- **Shadow Strategy:** 无常驻阴影。
- **Border:** 使用 1px Optical Line 分隔。
- **Internal Padding:** 16px、24px 或 32px。

### Inputs / Fields

- **Style:** 高 50px、10px 圆角、白色背景与 1px Optical Line。
- **Focus:** 边线转为 Optical Ink，外层增加半透明 Optical Focus 焦点环。
- **Error / Disabled:** 错误使用 Danger 文字和边线；禁用降低对比并保持文字可读。

### Navigation

顶部导航使用细线下边界和 13px 标签。当前项通过文字重量或底线表达；移动端优先保留品牌、当前上下文和一个主要操作。

### Authentication Shell

- **Desktop:** 左侧约46%用于品牌叙事和轨道图形，右侧约54%用于最大420px的表单；表单不放进悬浮玻璃卡片。
- **Mobile:** 品牌区收缩为238px高的视觉横幅，表单在下方单列排列，页面允许自然纵向滚动。
- **Route Switch:** 顶栏始终提供登录与注册入口，当前路由使用2px底线和字重表达。
- **Boundary:** 品牌轨道视觉只存在于左侧观察区，输入区域使用不透明白色表面，避免装饰内容穿过文字和控件。

### Preview Orbit

预览轨道由 DesignOrbit.vue 的 SVG、柔和色彩和低频CSS形变组成。组件不注册 `pointermove`，也不读取鼠标坐标。动画必须响应 `prefers-reduced-motion`，此时保留静态构图。旧版 `LiquidBackdrop.vue` 与 `liquid-glass.mp4` 已随视觉改版删除，当前正式首页、登录、注册和工作台不依赖视频素材。

## Do's and Don'ts

### Do:

- **Do** 用 `#FCFCFC`、`#202224` 和 `#D5D8DA` 建立主要层级。
- **Do** 用真实作者、时间和行情来源形成信息节奏。
- **Do** 为每个按钮、输入和链接提供清楚的 `:focus-visible` 状态。
- **Do** 在新增页面前先复用全局颜色、间距和组件规则。
- **Do** 让公开首页保持整屏视觉，并把登录、注册放在独立路由中。
- **Do** 让认证页的品牌区与操作区共享同一条顶栏，但保持清楚的视觉边界。

### Don't:

- **Don't** 做传统证券终端式的深色高密度行情屏。
- **Don't** 用红绿涨跌色装饰所有区域，也不要制造刺激交易的氛围。
- **Don't** 堆叠大量圆角卡片、渐变背景、宽阴影和拟玻璃面板。
- **Don't** 在高频业务操作区放置实验性的光学动效。
- **Don't** 使用渐变文字、彩色侧边条或超过 16px 圆角的业务卡片。
- **Don't** 使用黑色等高线、网格或随鼠标抖动的背景效果。
- **Don't** 在公开首页固定放置占据半个视口的登录或注册表单。


## 迭代方法与视觉理由

先固定页面类型、全局令牌、骨架和组件接口，再随真实内容调整局部文案、表格密度、4～8px 间距及 150～250ms 反馈。等所有页面完成再统一视觉会牵涉大量 DOM、响应式结构和选择器；提前冻结每个像素也会浪费返工成本。

认证页采用双区，是为让品牌表达与账号操作各有清楚职责。AuthLayout 管构图，LoginView 和 RegisterView 管字段校验与提交，具体请求数据流见 [前端教学](../engineering/frontend.md)。

新增页面依次判断页面类型、复用骨架和令牌、检查新样式是否有业务语义、分别处理桌面与手机、验证真实加载及错误状态。只影响局部文案样式的可以迭代；改变全站结构、令牌或组件接口的，在批量使用前确认。验证范围按 [测试规则](../engineering/testing.md) 选择。

## 可访问性要求

以 WCAG 2.2 AA 为最低目标，正文对比度至少 4.5:1；交互支持键盘且焦点清楚，状态不能只靠红绿区分；减少动态偏好下停止持续动画并保留静态构图，桌面与手机不得横向溢出。
