# BukaMusic

面向 **Android 6.0（API 23）及以上** 的“背景音乐播放 + AirPlay 接收 + 多房间同步”应用。

设备开机后自动在后台运行并在通知栏常驻播放状态；主界面左侧为专辑封面，右侧为歌曲信息与播放控制；
同一播放界面同时承接 **本地音乐（含 USB 设备）**、**AirPlay 投送** 与 **多房间同步** 三种播放任务。
应用还可以被设置为设备默认“桌面”，开机直接进入播放界面。

## 功能

### 后台常驻

- 前台服务 + 媒体通知（播放 / 暂停 / 上一首 / 下一首）；
- 开机自启（`BOOT_COMPLETED`），播放时持有 WakeLock 防止休眠中断；
- 可被系统识别为 **HOME（桌面）应用**，设置中一键跳转“默认主页”选择。

### AirPlay 接收与遥控

- **AirPlay 1（RAOP / AirTunes 2）**：纯 Java 实现，iOS / iPadOS / Mac 控制中心
  即可发现并投送，Apple Lossless（ALAC）44.1kHz / 16bit / 双声道解码播放；
- 局域网 mDNS/Bonjour 自动广播，AirPlay 会话中自动获取投送端 `DACP-ID` /
  `Active-Remote`，通过 **DACP 协议遥控投送端**：
  - AirPlay 模式下“上一首 / 下一首”直接切换 iPhone 上的歌曲，**不中断**投送会话；
  - “暂停 / 播放”直接遥控 iPhone 真正暂停/继续。

### 多房间同步

- 主控播放本地音乐时，可将同一局域网内安装了本应用的其他设备加入同步组，
  多台设备与主控**同步播放同一音频**（NTP 时钟对时 + 重采样纠偏，误差毫秒级）；
- 设备选择框保持勾选状态，取消勾选即断开对应设备；接收端也可主动“断开连接”；
- 主控被 AirPlay 抢占时自动断开多房间设备，AirPlay 退出后自动重连并恢复播放（不在线的设备跳过）；
- 接收端被 AirPlay 抢占时平滑过渡（多房间 2500ms 缓出 → AirPlay 2500ms 缓入），
  AirPlay 退出后自动恢复多房间播放；
- 接收端可远程控制主控的播放 / 暂停 / 上一曲 / 下一曲 / 拖动进度条；
- 切歌 / 播放后 3 秒自动进行一次延迟校准。

### 智能源切换

- 本地音乐播放中收到 AirPlay 投送 → 本地音量约 2000ms 平滑降到 0 再暂停，
  转为 AirPlay 播放（AirPlay 音量从 0 约 2500ms 渐强）；
- AirPlay 暂停 / 结束 → 若之前是本地音乐被抢占，本地音量从 0 约 2500ms 渐强恢复；
- 若之前本地未播放或本就暂停 → AirPlay 结束后保持原状态，不自动开始本地播放；
- 应用内手动暂停 AirPlay 时保持 AirPlay 暂停状态（不会误切回本地）。

### 本地播放

- **内置文件管理器**选择音乐文件夹：直接浏览内部存储与 **USB 存储卡**，
  不依赖系统文件管理器（特殊设备无文件管理器也能用）；
- 支持 MP3 / FLAC / M4A / AAC / OGG / WAV / OPUS / APE / WMA / AIFF 等格式；
- 指定文件夹后曲库仅显示该文件夹内容；拔插 USB 自动重新扫描；
- 四种播放方式：顺序播放、单曲循环、随机播放、文件夹内循环；
- 左右声道音量平衡滑块 + “恢复默认”按钮，本地与 AirPlay 同时生效，
  可纠正部分设备的左右声道音量差；
- 进入软件自动恢复上次播放曲目与进度。

### 均衡器与本地切歌过渡

- **10 段图形均衡器**（31.5Hz – 16kHz，±12dB）：标准双二阶峰值滤波，
  曲线增益真实生效；带软限幅，高增益不硬削波、无“电流声”失真；
- 支持预设**保存 / 命名 / 导入 / 导出**、一键恢复默认，滑块精度 0.1dB；
- 本地 / AirPlay / 多房间三条播放路径各用独立均衡器实例，切换互不干扰；
- 本地切歌（含曲库选歌）采用 **1000ms 淡出 + 1000ms 淡入**（非交叉），
  快速连点直接切到目标歌曲；
- 曲库打开时自动重新扫描，新增文件（含 USB 拔插）即时可见；
- 切歌时封面随目标歌曲刷新，不再残留旧封面。

### 界面

- 天空蓝主题，左侧专辑封面，右侧歌曲信息与播放控制；
- 底栏：多房间 + 系统音量滑条 + 曲库 + 设置 + “应用”（可像桌面一样打开本机应用）；
- AirPlay 模式下自动隐藏多房间按钮；多房间接收中显示“断开连接”按钮；

## 目录结构

```
BukaMusic/
├── app/src/main/
│   ├── java/com/airmusic/player/          # 应用代码
│   │   ├── airplay/                      # AirPlay 控制层
│   │   │   ├── AirPlayController.java    # 引擎生命周期与事件转发
│   │   │   └── DacpClient.java           # DACP 遥控客户端（切歌/暂停/播放）
│   │   ├── library/                      # 曲库扫描（系统媒体库/文件夹/USB）
│   │   ├── multicast/                    # 多房间同步（发现/主从/时钟/重采样）
│   │   ├── playback/                     # 本地播放与播放方式
│   │   ├── receiver/                     # 开机自启、USB 拔插监听
│   │   ├── service/                      # 前台服务、源切换状态机、通知
│   │   ├── ui/                           # 列表适配器
│   │   ├── util/                         # 偏好设置、状态总线
│   │   ├── MainActivity.java             # 主播放界面
│   │   ├── FolderPickerActivity.java     # 内置文件管理器
│   │   ├── LibraryActivity.java          # 曲库
│   │   ├── SettingsActivity.java         # 设置
│   │   └── AppsActivity.java             # 应用列表
│   ├── java/nz/co/iswe/android/airplay/  # AirPlay 引擎（GPL-3.0，源自 DroidAirPlay）
│   ├── java/org/phlo/AirReceiver/        # RAOP 协议核心（GPL-3.0，源自 AirReceiver）
│   ├── java/com/beatofthedrum/alacdecoder/# 纯 Java ALAC 解码器（BSD）
│   └── res/                              # 布局、图标、字符串
├── app/build.gradle                      # 构建配置（含 release 签名）
├── build.gradle / settings.gradle / gradle/
├── LICENSE                               # GPL-3.0
├── CHANGELOG.md                          # 更新日志
└── README.md
```

## 构建

环境要求：
- JDK 17+（本项目在 JDK 25 下验证）、Android SDK；
- `compileSdk 34`、`minSdk 23`、`targetSdk 34`；
- Gradle 发行版走国内镜像（见 `gradle/wrapper/gradle-wrapper.properties`）。

命令行构建：

```powershell
$env:JAVA_HOME = "你的 JDK 路径"
$env:ANDROID_HOME = "你的 Android SDK 路径"
.\gradlew.bat assembleDebug     # 调试包
.\gradlew.bat assembleRelease   # 正式包
```

输出：
- 调试包：`app/build/outputs/apk/debug/app-debug.apk`
- 正式包：`app/build/outputs/apk/release/app-release.apk`

> release 构建默认使用本机 Android 调试证书签名（见 `app/build.gradle`），
> 可直接覆盖安装调试版；正式分发请替换为自有签名证书。

## 安装与使用

1. 安装 APK 并打开应用，按提示授权：
   - Android 6–10：存储权限；
   - Android 11+：使用内置文件夹选择器时授予“所有文件访问权限”
     （选择器内可一键跳转授权）；
   - Android 13+：通知权限。
2. 右上角设置：
   - **设备名称**：iPhone/其他设备上看到的名称（默认使用系统设置内的设备名）；
   - **本地音乐路径**：内置文件管理器选择内部存储或 USB 中的音乐目录，
     不选则扫描系统媒体库；
   - **播放方式**：顺序 / 单曲循环 / 随机 / 文件夹内循环；
   - **自动播放**：启动后自动继续上次的本地音乐；
   - **声道平衡**：调节左右声道音量差，可一键恢复默认；
   - **设为桌面**：把本应用设为设备默认主页。
3. 主界面左侧为专辑封面，右侧为歌曲信息与上一首 / 播放暂停 / 下一首。
4. iPhone 下拉控制中心 → 隔空播放（AirPlay）→ 选择你的设备名即可投送；
   AirPlay 模式下同样可以用应用内的上一首 / 下一首 / 暂停遥控手机播放。
5. 多房间：主控播放本地音乐 → 底栏多房间按钮 → 勾选其他设备 → 确认，
   所有设备同步播放；取消勾选断开，接收端也可主动断开。

USB 音乐：U 盘插入后，在 设置 → 本地音乐路径 → 内置文件管理器 中选中 U 盘目录
（选择器会单独列出 USB 存储卡）；拔插 U 盘会自动触发重新扫描。

## AirPlay 说明与限制

- 本项目实现的是 **AirPlay 1（RAOP / AirTunes 2）**，iOS / iPadOS / Mac 以
  Apple Lossless（ALAC）44.1kHz / 16bit / 双声道无损格式传输，由内置纯 Java
  ALAC 解码器解码播放；
- 需要手机与设备处于**同一局域网**，且设备 WiFi 已连接（mDNS 组播需要）；
- **不支持** AirPlay 2（配对、多房间、高解析度）、屏幕镜像、视频投送与
  DRM 内容；
- DACP 遥控依赖发送端在局域网广播 `_dacp._tcp` 服务（AirPlay 1 投送时默认
  广播）；个别设备不广播时遥控不可用，但音频接收不受影响；
- 部分第三方发送端若强制发送 AAC 而非 ALAC，可能无法解码，属预期行为。

## 许可与致谢

- 应用代码与界面：本项目整体以 **GPL-3.0** 发布（因集成了 GPL 的 AirPlay
  引擎），见 `LICENSE`；
- AirPlay 引擎：改编自 [AirReceiver](https://github.com/phlo/airreceiver)
  （Florian G. Pflug）与 [DroidAirPlay](https://github.com/digideskio/DroidAirPlay)
  （Rafael Almeida），均为 GPL-3.0；
- DACP 遥控：参考 [shairplay](https://github.com/jkcoxson/shairplay)（MIT）与
  [shairport-sync](https://github.com/mikebrady/shairport-sync)（MIT）的协议实现；
- ALAC 解码器：Peter McQuillan / David Hammerton 的 Java 移植，BSD 3-Clause，
  见 `app/src/main/java/com/beatofthedrum/alacdecoder/license.txt`；
- 依赖：Netty 3（Apache-2.0）、JmDNS（Apache-2.0）、BouncyCastle（MIT 风格）、
  AndroidX（Apache-2.0）。

本项目与 Apple Inc. 无关；“AirPlay”为 Apple 的商标，仅用于描述协议兼容性。
