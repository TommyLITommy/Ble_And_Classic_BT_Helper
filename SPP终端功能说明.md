# SPP 终端功能与问题说明

> 便于后续查阅。对应模块：`spp/`（Simple Bluetooth Terminal）。

---

## 1. 软键盘遮挡命令输入框

**现象：** 在 SPP 数据发送界面手动输入时，键盘弹出后看不到输入框。

**原因：** `MainActivity` 开启了 edge-to-edge（`setDecorFitsSystemWindows(false)`），只处理了导航栏 inset，未处理 IME（键盘）inset。

**处理：** 在 `setupWindowInsets()` 中对 fragment 区域使用：

```kotlin
maxOf(ime.bottom, navBars.bottom)
```

作为底部 padding，键盘弹出时输入区上移。

**相关文件：** `spp/.../MainActivity.kt`

---

## 2. 发送输入框优化

**改动：**

- 右侧增加 **清空** 按钮
- HEX 模式下增加 **按字节编辑**（铅笔图标）：点选某一字节单独修改
- 修复 HEX 格式化后光标跳到末尾的问题，方便改中间字节
- 输入框最多约 4 行、等宽字体

**相关文件：**

- `fragment_terminal.xml`
- `TerminalFragment.kt`
- `TextUtil.HexWatcher`

---

## 3. 接收显示缓存上限 + 同步保存到文件

### 3.1 显示卡顿

**现象：** 设备约 10ms / 100 字节上报，长时间接收后界面卡顿。

**处理：**

- 界面接收区字符数上限约 **20 万**，超出丢弃最旧内容
- 自动滚动节流

### 3.2 同步保存

- 勾选「同步保存接收数据」后，**界面显示什么就写什么到文件**（含 HEX 帧、`sleep_upload_data_t` 解析、IMU/Touch 等协议打印、发送回显、状态行）
- 路径：`Download/SPP_RX/`
- 命名：
  - 有文件名前缀：`前缀_yyyyMMdd_HHmmss.txt`
  - 无前缀：`spp_rx_yyyyMMdd_HHmmss.txt`
- 单文件超过 **50MB** 自动分卷：`…_2.txt`、`…_3.txt`…
- 已去掉与 checkbox 重复的「保存文件」按钮
- 顶部文件名输入框作为同步保存前缀，支持历史记录（最近约 10 条）
- **Update** 按钮：改名前缀后点 Update，关闭当前文件并新建继续写
- 离开 Terminal 页面但勾选仍开启时：Service 侧会用兜底格式继续写（尽量含 Sleep 解析）；回到 Terminal 后仍以界面文本为准

### 3.3 后台 / 息屏

- 写文件在 `SerialService` 中执行，退后台/息屏仍可继续写（需保持前台服务通知）
- 请开启菜单中的 **Notification if App in background**（Android 13+ 还需通知权限）

### 3.4 电脑用 USB 看文件一直是 0 字节

**原因：** MTP 下文件句柄一直打开时，电脑端常显示 size=0；取消勾选关闭文件后才看到真实大小。

**处理：** 约每 2 秒或每 64KB：`flush` → `fsync` → **关闭文件** → MediaScanner → **追加模式重新打开**。

手机状态栏会显示：`保存中: xxx.txt (12.3KB)`。

**相关文件：**

- `ReceiveLogWriter.kt`
- `SerialService.kt`
- `TerminalFragment.kt`
- `SppPreferences.kt`（文件名历史）

---

## 4. 接收拆包显示 + Payload String 打印

### 4.1 拆包

**现象：** 高速上报时多条命令粘在同一行。

**处理（需开启菜单 HEX 模式）：**

- 使用 `PrivateProtocolStreamDecoder` 流式拼包
- **一个完整帧一行 HEX**
- 每包前保留时间戳：`---RX---HH:mm:ss---`
- 包与包之间空行分隔
- 不完整数据留在缓冲区，收齐再显示

### 4.2 Payload String 打印

- Checkbox：**Payload String打印**
- 勾选后：只要该帧 `payload` 长度 **> 0**，即提取 payload，以 UTF-8 显示为：

  `Payload String: ...`

- 界面与同步保存文件逻辑一致
- 不再限制 group/subId 或固定 66 字节

**相关文件：** `TerminalFragment.kt`、`SerialService.kt`、`PrivateProtocol*.kt`

---

## 5. 安装 APK 时附带 “Leaks” 应用

**原因：** debug 依赖了 LeakCanary（内存泄漏检测），安装 debug 包时会附带 **Leaks** 应用。

**配置位置：** `app/build.gradle`

```gradle
// LeakCanary：需要查内存泄漏时再打开（debug 安装会附带 Leaks App）
// debugImplementation 'com.squareup.leakcanary:leakcanary-android:2.12'
```

当前已 **注释屏蔽**，未删除。需要排查泄漏时去掉 `//` 重新编译即可。手机上已装的 Leaks 可手动卸载。`release` 包本身不会带该依赖。

---

## 6. 使用提示速查

| 场景 | 建议 |
|------|------|
| 手动输入被键盘挡住 | 已修；仍有问题检查 IME inset |
| 长时间高速收数不卡顿 | 依赖显示上限；重要数据请勾选同步保存 |
| 后台继续落盘 | 勾选同步保存 + 保持通知权限 |
| USB 连电脑看文件大小 | 等约 2 秒刷新目录；或看手机状态栏大小 |
| 拆包 / Payload String | 打开 **HEX** 模式后再测 |
| 文件名前缀 | 输入框填写 → 勾选保存；改名点 **Update** |
| 不要 Leaks App | 保持 LeakCanary 注释状态 |

---

## 7. 主要相关路径

```
spp/src/main/java/de/kai_morich/simple_bluetooth_terminal/
  MainActivity.kt
  TerminalFragment.kt
  SerialService.kt
  ReceiveLogWriter.kt
  TextUtil.kt
  PrivateProtocol.kt
  PrivateProtocolStreamDecoder.kt
  SppPreferences.kt
  SleepUploadDataParser.kt

spp/src/main/res/layout/fragment_terminal.xml
app/build.gradle
```
