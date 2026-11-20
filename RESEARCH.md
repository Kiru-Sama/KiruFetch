# 原包能力研究笔记（Clean-room）

## 目标

记录从参考 APK 静态分析中确认过的“能力边界”和“实现思路”，用于独立实现 KiruFetch。本文只保留功能性结论，不保留原包代码、资源、品牌、SDK 身份或包名身份。

## 已确认能力

### 浏览器嗅探

参考 App 使用内置 WebView 作为入口。WebView 在页面加载和请求阶段采集候选媒体地址，同时通过 JS 注入读取页面播放器和资源列表。

可复现的功能点：

- 拦截页面网络请求
- 收集 URL、path、host、query、请求头
- JS 扫描页面中的 `flashvars_*` / `mediaDefinitions` 一类播放器数据
- JS 扫描 `<video>` / `<source>` 标签
- 回传去重后的媒体 URL 列表

### 请求头策略

参考 App 会复制部分网页请求头用于下载，但会过滤 hop-by-hop 和敏感头。

可复现的功能点：

- 下载时携带 Referer
- 下载时携带与页面一致的 User-Agent
- 用户明确允许时携带 Cookie
- authorization/origin 只在同源时使用
- 去掉 connection/host/content-length/accept-encoding/range 等不应复制的头

### URL 去重

参考 App 会去掉 query/fragment 和末尾斜杠后做 hash，用 hash 作为任务文件标识。

可复现的功能点：

- URL 规整化
- 基于规整 URL 生成稳定 ID
- 同一媒体的不同 query 视为同一个候选

### 直链下载

参考 App 将非 HLS URL 当作普通文件下载。

可复现的功能点：

- OkHttp GET
- HEAD / Range 探测
- 多线程分片
- 分片进度统计
- 下载失败重试
- 合并输出文件

### HLS 下载

参考 App 有完整 VOD HLS 解析能力。

可确认支持的 HLS 标签/概念：

- `#EXTM3U`
- `#EXT-X-STREAM-INF`
- `#EXT-X-MEDIA`
- `#EXTINF`
- `#EXT-X-BYTERANGE`
- `#EXT-X-MAP`
- `#EXT-X-KEY` AES-128
- `#EXT-X-ENDLIST`

可复现的功能点：

- master playlist 选择清晰度
- media playlist 分片下载
- AES-128 key/iv 解密
- init map 下载
- byte range 分段
- 分片合并

### 平台排除

参考 App 明确排除：

- youtube.com / youtu.be
- facebook.com
- instagram.com

KiruFetch MVP 沿用同样排除策略，避免平台对抗。

### 不做的部分

从参考包中未看到明确实现：

- DRM 解除
- 登录墙绕过
- 付费墙绕过
- 地理限制绕过
- 平台反爬绕过

KiruFetch 不实现这些能力。

## UI 信息架构参考

参考 App 的信息架构：

- 主界面为内置浏览器
- 嗅探结果以浮层/卡片展示
- 下载任务独立页面
- 设置独立页面
- 底部导航切换主模块
- 通知栏显示下载进度

KiruFetch 保留这套信息架构，但视觉语言完全独立：现代科技简约风、深色背景、蓝紫/青色强调、圆角卡片、低饱和层次。

## 技术约束

KiruFetch 必须独立实现：

- 独立包名
- 独立签名
- 独立代码
- 独立资源
- 独立 UI
- 不引入原 App 的第三方广告/Firebase/授权 SDK

推荐技术栈：

- Kotlin
- View 系统
- WebView
- OkHttp
- Room
- WorkManager/Foreground Service
- MediaStore/SAF
