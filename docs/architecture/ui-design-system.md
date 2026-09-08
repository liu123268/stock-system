---
name: "同研自选（暂定）"
description: "为家庭与小型研究组记录共同关注和个人判断"
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
---

# 同研自选前端视觉设计系统

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

预览轨道由 DesignOrbit.vue 的 SVG、柔和色彩和低频CSS形变组成。组件不注册 `pointermove`，也不读取鼠标坐标。动画必须响应 `prefers-reduced-motion`，此时保留静态构图。LiquidBackdrop.vue 仍保留在项目中作为旧参考素材，但当前正式首页、登录、注册和工作台均不渲染它。

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
