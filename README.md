# 别错过站（Checkin Assistant）

一个基于位置的通勤提醒 Android App：在地图上设置提醒点（比如宿舍、公司、某个车站），到达范围内时自动响铃、震动并弹出通知，防止坐过站或忘记打卡。

## 功能

- 多个独立提醒点，每个可设置触发半径（100 米–5000米）和生效时间段
- 重复模式：每天、按星期几、仅一次，支持临时跳过
- 使用 Google 地图选点和搜索地点
- 生效时间段内主动定位确认（约 25 秒一次），不完全依赖系统地理围栏，减少提醒延迟
- 时间段外自动休眠，节省电量
- 高优先级通知 + 响铃 + 震动，可重复提醒
- 日历形式的历史记录和每个提醒点的统计
- 调试日志页面，支持导出，30 天自动清理

## 系统要求

- Android 10（API 29）及以上
- 需要 Google Play 服务（地图功能依赖它）

## 自己编译

本项目不提供编译好的安装包，需要使用自己的 Google Maps API Key 自行编译。

1. 在 [Google Cloud Console](https://console.cloud.google.com/) 申请一个 API Key，并为它启用 **Maps SDK for Android** 和 **Places API (New)**（地点搜索用的是新版 Places API，不启用搜索会失败）。建议把 Key 限制为 Android 应用：包名 `com.checkinassistant` + 你自己签名证书的 SHA-1。
2. 在项目根目录创建 `local.properties`，写入：

   ```properties
   MAPS_API_KEY=你的Key
   ```

   用 Android Studio 打开项目时会自动补上 `sdk.dir`；用命令行编译需要自己加上 `sdk.dir=你的Android SDK路径`
3. 编译：

   ```bash
   ./gradlew assembleDebug
   ```

   生成的安装包在 `app/build/outputs/apk/debug/`。

`local.properties` 已被 `.gitignore` 忽略，不会被提交。缺少 Key 时编译会直接报错并提示。

## 使用提示

国产系统（ColorOS、MIUI、HarmonyOS 等）后台管理很严格，锁屏后提醒可能延迟。建议给本应用开启：

- 位置权限“始终允许”并开启精确位置
- 允许后台行为、自启动、关联启动
- 在最近任务里锁定本应用

## 许可证

本项目基于 [GPL-3.0](LICENSE) 许可证开源。
