# README 图像来源

- `mac-matrix.png`、`mac-today.png`：2026-11-23 在本机使用独立 `Step Screenshots` 演示程序拍摄。直接加载当前生产视图，使用临时资料库与虚构任务，窗口为 1060 × 800 点，原图为 2120 × 1600 像素（原生 2×）。包含完整窗口标题栏、四周边界及底部入口；没有裁切、插值放大或重绘界面。
- README 中的 Android 图片沿用对应版本隔离 QA 资料的完整应用页面；不含系统状态栏，保留各自原始宽高比。
- README 中的 Windows 图片沿用 `windows/v0.2` 的真实组件 2× 离屏渲染，不把它称为系统窗口截图。

重新拍摄 Mac：

```sh
bash Scripts/screenshot-demo.sh --readme-matrix
# 或 --readme-today；演示程序不连接实际账号、不开启提醒或全局快捷键。
swiftc -swift-version 5 Scripts/capture-window.swift -o /private/tmp/step-readme-capture-window
/private/tmp/step-readme-capture-window --list
/private/tmp/step-readme-capture-window WINDOW_ID OUTPUT.png
```

截图工具只允许独立演示 App，导出需要 macOS 14 或更新版本；Step 本身的系统要求不变。先让演示窗口完整渲染后再导出。不要在导出前开启该窗口的交互控制会话，以免系统共享控件进入画面；不要通过裁掉标题栏来隐藏控件。

生产 App 的窗口、任务、账号和模型历史不可用于公开截图。
