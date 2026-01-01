# AirMouse

AirMouse 是一款基于手势识别的 Android 虚拟鼠标应用。它通过摄像头捕捉手势，并利用 Shizuku/Sui 提供的系统级权限实现对屏幕的控制，无需开启传统的“辅助功能”权限。

## 功能特性

- **空中控制**：通过前置摄像头捕捉手势，实现非接触式屏幕操作。
- **精准映射**：将手势坐标实时映射到屏幕位置，并带有防抖处理。
- **多种手势支持**：
    - **移动**：移动手部控制光标。
    - **点击**：快速捏合手指。
    - **长按**：捏合手指并保持。
    - **滑动**：捏合后拖动手指。
- **高性能**：基于 CameraX 和 Mediapipe（Hand Landmarker）实现高效的图像处理。
- **系统级集成**：使用 Shizuku/Sui 执行注入操作，比辅助功能更高效、更强大。

## 运行要求

- **Android 版本**：Android 9.0 (API 28) 及以上。
- **Shizuku/Sui**：必须安装并启动 [Shizuku](https://shizuku.rikka.app/) 或 [Sui](https://github.com/RikkaApps/Sui)（Magisk 模块）。
- **权限需求**：
    - 相机权限（用于手势识别）。
    - 悬浮窗权限（用于显示鼠标光标）。
    - 通知权限（用于前台服务运行）。
    - Shizuku 授权。

## 使用说明

1. 确保 **Shizuku** 或 **Sui** 已在设备上正常运行。
2. 启动 AirMouse 应用。
3. 按照提示授予 **相机**、**悬浮窗** 和 **通知** 权限。
4. 点击“请求 Shizuku 权限”并允许。
5. 点击 **启动服务** 开始使用。
6. 点击 **停止服务** 结束使用。
7. 可以通过 **打开调试界面** 查看实时手势分析状态。

## 技术实现

- **图像分析**：使用 `androidx.camera.core.ImageAnalysis` 实时获取帧数据。
- **手势识别**：集成 Mediapipe Hand Landmarker 模型。
- **模拟操作**：通过 `Shizuku.newProcess` 执行 `input tap` 和 `input swipe` 命令。
- **UI 显示**：使用 `WindowManager` 实现全局悬浮光标。

## 免责声明

本应用仅供技术研究和学习使用。在使用过程中请确保摄像头权限的安全性。
