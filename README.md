# 🌐 SPP 蓝牙自动化控制

[![Version](https://img.shields.io/badge/version-2.0.0-blue.svg)](https://github.com/shj56166/kongzhi2)
[![Platform](https://img.shields.io/badge/platform-Android-green.svg)](https://developer.android.com)
[![Language](https://img.shields.io/badge/language-HTML%20%7C%20Kotlin-orange.svg)]()
[![License: MIT](https://img.shields.io/badge/license-MIT-lightgrey.svg)](LICENSE)

> 💡 一款基于 **SPP 蓝牙** 通信协议的多功能自动化控制平台，集成继电器控制、定时任务与底层调试功能。  
> 采用 **HTML + Kotlin 前后端分离架构**，轻量、高效、现代。

---

## 🧭 项目简介

**SPP 蓝牙自动化控制** 是一款为电子爱好者与开发者打造的跨层蓝牙控制 App。  
通过简洁的 Material You 风格界面，用户可实现蓝牙设备的智能控制与自动化任务管理。

它可用作：
- 蓝牙继电器控制器  
- 蓝牙模块（HC-05 / HC-06 / ESP32 SPP 模式）调试终端  
- 自动化实验平台或物联网原型系统 轻量项目控制 

---

## ✨ 核心功能

| 模块 | 描述 |
|------|------|
| 🧩 **多路动态控制** | 动态添加、编辑、删除继电器卡片；独立配置指令，自动保存本地。 |
| ⏰ **精准后台定时** | 采用 `AlarmManager` 精确唤醒机制，即使息屏/休眠仍能执行任务。 |
| 🧠 **底层指令调试台** | 支持 HEX 与 文本格式互转，实时记录收发信息与响应日志。 |
| 🧾 **日志记录系统** | 清晰展示通信记录与任务状态，支持类型筛选与时间标注。 |
| ⚙️ **原生后台服务** | Kotlin 前台服务维持稳定蓝牙连接，配合广播接收器实现任务分发。 |

---

## 🧱 技术架构

```
📱 Android (Kotlin)
│
├── 🔧 蓝牙服务层：SPP (Serial Port Profile)
│   ├─ 前台服务 (ForegroundService)
│   ├─ 后台任务触发 (AlarmManager)
│   └─ 状态广播管理 (BroadcastReceiver)
│
├── 🌐 前端界面层：HTML + CSS + JS
│   ├─ Material Web Components 实现 Material You 风格
│   ├─ 动态继电器卡片与任务配置界面
│   └─ 指令调试与日志模块
│
└── 🔗 通信桥梁：
    ├─ JS 调用原生：`JavascriptInterface`
    └─ 原生回调 JS：`evaluateJavascript`
```

---

## 📂 文件结构

```
project-root/
├── index.html              # 主界面入口
├── /fonts/                 # 字体文件 (Roboto / Material Symbols)
├── /js/material-web.js     # Material Web 组件
└── /android/               # Kotlin 后端逻辑 (蓝牙控制服务)
```

---

## ⚙️ 部署与运行

1. 将前端文件放入 Android 项目：
   ```
   app/src/main/assets/index.html
   ```
2. 启用 WebView 与 JS：
   ```kotlin
   webView.settings.javaScriptEnabled = true
   webView.addJavascriptInterface(BluetoothBridge(this), "NativeBridge")
   webView.loadUrl("file:///android_asset/index.html")
   ```
3. 运行应用后，即可在 WebView 中启动控制界面。

---

## 🧠 开发目标

- ✅ 极简 Material You 风格 UI  
- ✅ 稳定后台运行与断线重连  
- ✅ 低编译错误率与快速预览  
- ✅ 支持后期迁移至 Jetpack Compose  

---

## 🧰 技术栈

| 分类 | 技术 |
|------|------|
| 语言 | **HTML / CSS / JavaScript / Kotlin** |
| 框架 | **Material Web Components** |
| 平台 | **Android 10+ (WebView)** |
| 通信 | **SPP 蓝牙 (RFCOMM)** |
| 架构 | **前后端分离 + JS ↔ Native 桥接通信** |

---

## 👨‍💻 作者信息

| 作者 | 简介 |
|------|------|
| **拿铁咖啡不加奶**
| **协作** | Gemini (AI 技术支持) |
| **联系方式** | QQ: `984388724` / ✉️ `shj561661@outlook.com` |

---

## 🤝 支持与开源

📦 **GitHub：** [https://github.com/shj56166/kongzhi2](https://github.com/shj56166/kongzhi2)  
☕ **支持作者：** 如果这个项目帮助到了你，欢迎 Star ⭐ 或“请我喝杯咖啡”！

---

## 📜 许可协议

本项目采用 [MIT License](LICENSE) 开源。  
可自由使用、修改、分发，但需保留作者署名。

---


