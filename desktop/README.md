# Step for Windows

Windows 10/11 x64 日常使用预览，沿用 Step 的四象限、想法孵化、今日规划、步骤完成与同账号同步。

实现范围：本地原子保存和独占锁、任务/想法/目标编辑、今日重点和时间安排、下一步完成/撤销/历史、专注与等待记录、运行期间提醒、可选AI预览/采纳、账号同步与冲突处理。主窗口可留在托盘，默认随手记快捷键Ctrl+Shift+Space，可在“桌面选项”修改。退出前保护未保存草稿；完全退出后不承诺同步或提醒。

Windows账号token、AI密钥与原始历史使用当前Windows用户的DPAPI保护，不随任务同步；非Windows开发模式不降级成明文存储。应用数据位于`%APPDATA%\Step`，安装更新不应删除此目录。系统级注意力检测、跨设备计时接管和桌面小组件不在首版范围。

## 构建

需要JDK17（打包推荐Temurin）和Gradle8.13；无需Android SDK。共享核心直接编译现有Android纯JVM源码的明确白名单，修改后同时回归安卓。

```sh
bash desktop/build.sh :core:test :app:test
```

Windows使用仓库已有wrapper：

```powershell
python desktop/configure.py --required
.\android\gradlew.bat -p desktop :core:test :app:test :app:packageExe :app:packageMsi
```

同步公开配置由`STEP_SYNC_URL`/`STEP_SYNC_PUBLIC_KEY`提供，或本机被Git忽略的`backend/supabase/project.local.json`。生成器仅接受HTTPS地址与publishable客户端键；不允许管理员密钥进入客户端。不要把实际模型配置、任务、凭据或AI原始记录放进仓库。

GitHub Windows流水线使用Temurin17/WiX构建exe和msi、安装msi、运行独立虚构资料的程序自测，再上传校验文件与portable包。源代码编译、无窗口交互测试、安装器自测和用户实机体验是不同验证范围；只有Windows流水线成功后才分发安装包。

当前首包没有商业代码签名，Windows可能显示未识别发布者。请只使用本仓库产物并核对SHA-256。
