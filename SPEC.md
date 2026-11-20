# KiruFetch 功能规格

## 1. 项目定位

KiruFetch 是一个独立的 Android 视频下载器原型。目标是实现原参考 App 的核心下载能力，但采用完全独立实现、独立包名、独立 UI 和独立架构。

本项目不复用原 App 的 dex、代码、品牌、图标、布局资源和签名身份。产品形态参考原 App 的信息架构：内置浏览器、嗅探结果、下载任务、设置页、播放器。

## 2. 边界

只处理以下媒体：

- 用户主动打开页面中可见的公开媒体
- 标准 HTTP/HTTPS 直链 MP4/WebM
- 标准 HLS m3u8
- 用户有权访问且浏览器可直接播放的媒体

不实现以下能力：

- 绕过登录墙
- 绕过付费墙
- 绕过 DRM/Widevine/FairPlay/PlayReady
- 绕过地理限制
- 绕过平台风控或访问控制
- 专门适配 YouTube、Instagram、Facebook 等原 App 明确排除的平台

遇到 401/403、需要登录、DRM、加密非标准 HLS 时停止并提示用户。

## 3. MVP 范围

### 3.1 内置浏览器

- WebView 地址栏
- 桌面模式切换
- JavaScript / DOM Storage 开启
- 第三方 Cookie 默认关闭，允许用户设置
- 请求拦截入口，收集候选 URL 与请求头

### 3.2 URL 嗅探

来源：

- `shouldInterceptRequest` 请求拦截
- JS 注入扫描 `<video>` / `<source>`
- JS 注入扫描 `performance.getEntriesByType("resource")`
- JS Bridge 回调 `onVideoUrls`

规则：

- URL 规整：去 query、fragment、末尾斜杠
- SHA-256 生成 mediaId
- 按 URL + 清晰度 + 容器类型去重
- 只接受 http/https
- 只接受常见扩展名或 m3u8/mpd 之外的标准媒体 MIME

排除：

- YouTube / youtu.be
- Facebook
- Instagram
- 原 App 明确排除的平台
- 明显非媒体资源

### 3.3 候选视频展示

UI 展示：

- 缩略图
- 页面标题 / 来源域名
- 时长
- 清晰度
- 格式
- 文件大小（如可得）

操作：

- 选择清晰度
- 复制直链
- 下载

### 3.4 直链下载器

支持：

- GET 下载
- HEAD / Range 探测
- Referer / UA / Cookie 请求头
- 单线程下载
- 多线程分片下载
- 暂停 / 继续
- 重试
- 文件合并
- 下载完成通知

限制：

- 默认每任务最多 8 线程
- 默认每个分片失败重试 3 次
- 单文件最大分片数可配置

### 3.5 HLS 下载器

支持：

- `#EXTM3U`
- master playlist
- `#EXT-X-STREAM-INF`
- `#EXT-X-MEDIA`
- `#EXT-X-KEY` AES-128
- `#EXT-X-MAP`
- `#EXT-X-BYTERANGE`
- `#EXTINF`
- `#EXT-X-ENDLIST`

流程：

1. 解析 master playlist
2. 用户选择清晰度 / 音轨
3. 下载 init segment
4. 下载 ts/fmp4 分片
5. AES-128 解密
6. 顺序合并
7. 可选音轨合并（MVP 后）

不支持：

- DRM
- 非标准加密
- 直播无限 m3u8

### 3.6 下载管理

- 前台服务
- 通知栏进度
- 任务列表
- 任务状态：排队、下载中、暂停、完成、失败、已取消
- 本地数据库保存任务
- 下载目录可配置

## 4. UI 设计

风格：极简留白（Minimal）。

视觉：

- 纯白底、墨色文字，只用黑白灰
- 发丝级 1dp 边框，直角或极小圆角
- 大留白，靠字距与层级说话；标题细字重、宽字距
- 小标签用大写英文 + 宽字距（DOWNLOADS / SETTINGS）
- 强调色使用墨色本身，不引入彩色

被动交互背景：

- 细网格缓慢漂移
- 低透明度粒子缓慢游走，近邻之间连线
- 少量漂浮多边形缓慢旋转
- 手指按下/滑动时粒子被牵引，并与触点连线
- 全部用墨色极低透明度绘制，只在不遮挡内容处若隐若现，约 30fps

信息架构：

- 底部导航：浏览器 / 下载 / 设置
- 浏览器页：标签条 + 地址栏 + WebView + 嗅探浮层
- 下载页：进行中 / 已完成 Tab
- 设置页：线程、UA、Cookie、嗅探、剪贴板、下载目录、边界说明
- 播放页：全屏纯黑，左上角返回

与原 App 差异：

- 不套免责声明强引导
- 不做隐私报告营销页
- 不做会员体系
- 不做广告
- 不做应用锁
- 不做 Firebase 埋点
- 全部中文 UI

## 5. 初始文件结构

```text
KiruFetch/
├── SPEC.md
├── README.md
├── app/
│   └── src/main/
│       ├── java/com/kirufetch/app/
│       │   ├── ui/screens/
│       │   ├── ui/theme/
│       │   ├── data/
│       │   ├── sniff/
│       │   ├── download/
│       │   ├── player/
│       │   └── settings/
│       └── res/
│           ├── values/
│           ├── drawable/
│           └── xml/
```

## 6. 里程碑

M0：规格与项目骨架  
M1：浏览器 + URL 嗅探  
M2：直链下载器  
M3：HLS 解析和下载  
M4：完整 UI 与设置  
M5：稳定性与中文体验优化
