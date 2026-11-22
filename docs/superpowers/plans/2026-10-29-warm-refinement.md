# Pare Warm Refinement Implementation Plan

> **For agentic workers:** Use the agreed visual spec; root integrates common styles and delegates independent view files. Preserve existing behavior and file ownership.

**Goal:** 将用户确认的「暖感精修」对比稿应用到本机 Pare。
**Architecture:** SwiftUI 共享绘制层提供柔和暖色/象限表面与导航选中样式，页面只选择相应表面；不更改模型和操作规则。
**Tech Stack:** SwiftUI/AppKit、SwiftPM，macOS 13+。
**Spec:** `docs/superpowers/specs/2026-10-29-warm-refinement-design.md`

## Global Constraints

- 四象限最低高度修复、36×32详情命中区域、草稿/采纳保护保持。
- 所有装饰overlay不接收点击；悬停不插入/删除控件。
- 动态文字及辅助功能设置保持；不添加第三方依赖。
- 可逆外观改动不增加镜像单测；完整现有回归、构建、真实UI验收作为验证。

## Review Focus

- 880×620窗口和待整理展开时不挤掉首条任务动作。
- 长任务标题、主操作和信息层级可读，不增加主屏信息。
- 键盘焦点、按钮边缘和背景装饰不相互拦截。
- 高对比/减少透明度模式没有低对比文本或透明内容层。
- 真实用户数据、计时和AI请求授权不变。

## Tasks

- [x] root：Theme.swift新增 `warmPanel(emphasized: Bool = true)`、`quadrantPanel(accent: Color, highlighted: Bool)`、`selectionSurface(selected: Bool)`；InterfaceComponents.swift新增 `WarmActionStyle` 并更新共享分段和按钮绘制；Components.swift减轻估时描边但保留入口。
- [x] workspace agent：仅OrganizeView.swift。四象限使用新表面、24点编号色标、安静空态、导航标记和暖色AI入口；保持几何分配与行为。
- [x] focus agent：仅PlanningViews.swift和IntelligentAssistantView.swift。今日重点和AI首卡用warmPanel，主标题清晰、其余卡片中性，不改变数据/授权/选择。
- [x] root：集成、独立只读复核，完整 `swift test --scratch-path /private/tmp/pare-warm-tests` 与 `./build_app.sh`；版本2.11.1 build38。
- [x] root：隔离演示与本机假服务检查四象限、今日重点、AI首屏/方案/错误；确认真实无草稿/计时后备份安装，核对JSON不变并清理演示；本地提交，不推送。

## 验证记录

- 240项测试通过，0失败；release构建、Info.plist和应用签名校验通过；独立只读复核无明确必修回归。
- 隔离演示实测880×620：待整理展开、键盘1归位后两个底部空象限、再加入今日任务触发偏多横幅，空态自动横排且添加框完整。
- 今日重点的中英文长标题能展示两行，开始/帮助/详情按钮与列表仍可用。
- 本机假模型测试生成、取消/恢复勾选、采纳成功和后续HTTP503错误，重点卡与固定底栏显示正常。无真实模型请求。
- 主题静态计算：正文最低9.52:1、辅助文字6.20:1、最弱正文颜色4.52:1。系统大字号/高对比模式本轮由代码复核覆盖，未修改用户系统设置。
- 本机已更新2.11.1 build38并查看实际界面；更新前后已保存JSON相等。备份路径 `/private/tmp/pare-2.11.1-upgrade.SXc5I8`。演示实例和假服务已关闭，未发布远端。
