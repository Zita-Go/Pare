# Step for Windows

Windows 10/11 x64 的 Step 桌面应用，沿用四象限、想法孵化、今日规划、步骤完成与同账号同步。

四象限与今日清单支持拖动排序，时间轴可新建、移动、调整时长和跨日安排。任务行和随手记可直接启动/暂停专注、完成下一步，卡住时可写下更小的行动并启动5分钟。资料库统一搜索当前与历史内容，支持归档、回收站及恢复；回顾展示已有投入记录、8周趋势和估时对比。

AI由你预览、发送和采纳；本地轻提示不会后台调用模型。设置中可管理背景、确认偏好、反馈与本机请求历史。备份支持选择文件、导入前预览、导入前恢复点和损坏资料恢复。

默认随手记快捷键为 Ctrl+Shift+Space，可在“设置 → 外观与快捷键”修改；这里也可打开紧凑桌面小窗。关闭主窗口可保留托盘，退出前保护未保存草稿。Windows设置提供可选登录自启、可操作通知、退出后已安排事项提醒，以及更新检查、校验下载和确认安装。这些系统功能默认关闭；电脑关机或未登录时不承诺准点通知，完全退出后不进行内容同步。

Windows账号token、AI密钥、反馈与原始历史使用当前Windows用户的DPAPI保护；原始历史和密钥不随任务同步。应用数据位于`%APPDATA%\Step`，安装更新不应删除此目录。注意力提醒与浏览器增强分别开启；浏览器标题仅在内存转为临时加盐摘要，不保存原文、网址或浏览历史，也不判断用户的实际工作内容。

其他设备状态为有时效的参考，不能远程暂停、结束或接管计时。桌面小窗属于 Step 自身，不是 Windows Widgets 面板插件。升级后的客户端使用presence v2；旧版 Mac/Android 仍正常使用原有状态接口，但不会显示 Windows 设备，任务内容同步不受影响。

## 构建

需要JDK17（打包推荐Temurin）和Gradle8.13；无需Android SDK。共享核心直接编译现有Android纯JVM源码的明确白名单，修改后同时回归安卓。

```sh
bash desktop/build.sh :core:test :app:test
```

Windows使用仓库已有wrapper：

```powershell
python desktop/configure.py --required
.\android\gradlew.bat -p desktop :core:test :app:test :app:createDistributable :app:packageExe :app:packageMsi
```

同步公开配置由`STEP_SYNC_URL`/`STEP_SYNC_PUBLIC_KEY`提供，或本机被Git忽略的`backend/supabase/project.local.json`。生成器仅接受HTTPS地址与publishable客户端键；不允许管理员密钥进入客户端。不要把实际模型配置、任务、凭据或AI原始记录放进仓库。

GitHub Windows流水线使用Temurin17/WiX构建exe和msi，验证上一版安装后升级保留虚构资料、已安装程序自测，以及原生系统注册与清理，再上传校验文件与portable包。源代码编译、离屏交互、安装器和系统注册测试均不能代替个人电脑上的中文输入法、DPI切换、真实通知点击/锁屏交付与多显示器实机验收。

当前安装包没有商业代码签名，Windows可能显示未识别发布者。请只使用本仓库产物并核对SHA-256。
