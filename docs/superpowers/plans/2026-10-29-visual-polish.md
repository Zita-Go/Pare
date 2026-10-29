# Pare 视觉与交互精修

用户要求着重优化圆角、字体、颜色、交互与反馈。本轮沿用深绿暖金品牌，改为更中性、层次清楚的原生桌面工具；不改变数据模型、AI权限和任务操作语义。

- [x] 基础视觉：背景/卡片/悬停/分隔/文字/语义状态token，控件8、卡片12、浮层18三档圆角；清晰排字，保留文字缩放/减少透明度与减少动态效果支持。
- [x] 公共控件：主/次/弱按钮、图标点击范围、输入框聚焦、分段导航、完成反馈；hover只改绘制，不插入/移除控制，不新增常驻原生Menu。
- [x] 工作区/随手记：减轻描边与阴影，协调任务行/象限/导航/状态色，文字基线和间距一致；保留最近简化的信息层级。
- [x] AI/想法/设置：统一输入与面板风格，主动作突出，保存/采纳/错误用语义图标和状态反馈，不因风格改变授权或联网规则。
- [ ] 视觉验收：隔离演示与本地假模型，主要页面截图/真实点击、键盘焦点、保存及错误反馈；完整测试/构建/备份本机更新。

文件边界：theme agent 拥有Theme/InterfaceComponents/AIControlStyles/Components/CompletionTimePicker/DailyAllocationPill；workspace agent 拥有Organize/Planning/Capture/PopoverController；root拥有IdeaWorkspace/IntelligentAssistant/Settings/其它入口与文档。共享组件合同先同步，避免同时改文件。

不发布远端，不发送真实任务到模型服务。更新前确认没有用户草稿或运行中的个人专注；保留应用和数据备份。

## 当前验证记录

- `swift test --scratch-path /private/tmp/pare-visual-integrated`：240 项通过，0 失败（包含 UIContractTests 10 项）。
- `./build_app.sh`：2.11.0 build37 release 构建、Info.plist 校验与临时签名完成；`git diff --check` 通过。
- 独立复核修正 AI 勾选控件重复叠加禁用透明度，其余未发现明确数据或交互回归。
- 文字对比度按主题色静态计算：正文 9.89:1，辅助文字最低 4.70:1；实际系统字号和高对比模式仍待界面核验。
- 桌面工具两次报告 Mac 锁屏，实际截图/点击验收尚未完成。没有替换或重启已安装的 Pare，没有修改用户任务；本地假模型请求数为 0。
- 解锁后继续：重建隔离演示，检查四象限/今日计划/随手记、长 AI 错误反馈、想法编辑与保存、设置切换；验收通过且无未保存草稿/活动计时后备份更新本机应用。
