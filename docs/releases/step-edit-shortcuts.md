# Step 2.15.2：原生编辑快捷键

2026-11-06，本地Mac开发包 **2.15.2 / build44**。Android仍为0.14.1，本次不修改安卓。

## 问题与修复

用户确认随手记可打开并输入，但Command-A无法全选。生产程序未建立NSApplication.mainMenu，只有菜单栏图标的临时右键菜单；截图演示程序却额外安装了编辑菜单，掩盖了两者的差异。

正式启动现在建立原生应用与编辑菜单，恢复Command-A全选、Command-C复制、Command-X剪切、Command-V粘贴、Command-Z撤销和Shift-Command-Z重做。编辑动作不绑定固定控件，而由当前文本控件的responder chain处理。Demo复用同一构造器。本次没有改动焦点逻辑、全局快捷键、输入控件、存储或账号身份。

## 验证

- 缺少菜单的隔离基线：真实NSTextView和field editor均不能通过菜单接收Command-A，7项预期失败。
- 正式菜单：16项原生检查全部通过，包含中文/组合重音/emoji的UTF-16全文选择、编辑selector路由和未绑定修饰键；测试不激活应用、不显示窗口、不接触系统剪贴板。
- 最终Swift测试343项通过；release构建、plist与签名通过；独立审查无阻塞问题。
- 原生脚本显式指定隔离接收者，未动态验证真实非激活面板的完整responder chain。物理键盘与实际窗口验证需在新进程启动后进行。

复现命令：`bash Scripts/verify-edit-menu.sh`；`--baseline`用于保留缺少菜单时的失败对照。

## 交付

新版已原位安装到 `/Applications/Step.app`，旧版已私下备份。为保留用户正在输入的草稿，未退出或重启正在运行的2.15.1进程；**保存草稿后从菜单栏退出Step，再重新打开，修复才会生效**。安装过程没有读写任务资料或系统剪贴板，也没有启动第二个正式实例。未发布到GitHub或应用商店。
