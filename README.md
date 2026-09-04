# btprint-android（蓝牙打印 SDK）

基于 Android + Kotlin 的蓝牙热敏打印机 SDK，提供完整的 ESC/POS 打印能力，可通过 **Maven Central**、AAR 文件或 Module 依赖集成到任意第三方 Android 应用。

## 功能特性

- **连接方式**：经典蓝牙 SPP、BLE（低功耗蓝牙 GATT 透传）、Wi-Fi/以太网（ESC/POS over TCP，默认端口 9100）；连接/断开/重连/自动连接上次设备/连接状态检测
- **传输层抽象**：`PrintTransport` 接口隔离传输与 ESC/POS 指令编排，可自定义扩展其他通道
- **纸张宽度**：支持 58mm（32 字符/行）与 80mm（48 字符/行）小票机，可切换并持久化
- **文本打印**：支持字号（小/中/大）、对齐方式（左/中/右）、多行换行
- **排版打印**：两列 / 三列排版、分割线、标题（行宽随纸张与字号自动换算）；多列超宽可选表格模式（列内折行、多列均分空间）
- **二维码打印**：支持自定义模块尺寸、纠错等级与对齐，内容按 GBK 字节编码（中文可正常扫码）
- **条形码打印**：支持 CODE128、EAN13、EAN8、UPC-A、UPC-E、CODE39、ITF、ONE_CODE93、CODABAR 共 9 种类型，按类型校验内容格式
- **图片打印**：自动压缩 + Floyd-Steinberg 抖动点阵算法，超过 200 万像素拒绝打印并提示压缩
- **硬件指令**：切纸（全切/半切）、开钱箱
- **设备管理**：已配对设备列表、连接入口 UI（`BtPrintActivity` 蓝牙页 / `WifiPrintActivity` WiFi 页，可选集成）；局域网打印机扫描（`LanPrinterScanner`）




## Maven Central


**1. 在 `settings.gradle`（或根 `build.gradle`）的 `repositories` 中添加仓库（一般工程已有 `mavenCentral()`）：**

```groovy
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}
```

> 若使用 `repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)`，必须配置在 `dependencyResolutionManagement` 中；否则可直接写在根 `build.gradle` 的 `allprojects { repositories { ... } }`。

**2. 在模块 `build.gradle` 中声明依赖：**

```groovy
dependencies {
    implementation 'io.github.jicg:btprint-sdk:1.1.0'
}
```

## 权限说明

SDK 已在自身 `AndroidManifest.xml` 声明以下权限，manifest 合并时会自动注入宿主应用，**无需在宿主中重复声明**：

- `BLUETOOTH`、`BLUETOOTH_ADMIN`（Android 11 及以下）
- `BLUETOOTH_SCAN`（`neverForLocation`）、`BLUETOOTH_CONNECT`（Android 12+）
- `ACCESS_FINE_LOCATION`、`ACCESS_COARSE_LOCATION`（低版本扫描兼容）
- `INTERNET`、`ACCESS_NETWORK_STATE`（Wi-Fi/以太网打印与局域网扫描）

**运行时权限**：Android 12+ 需要宿主在跳转 `BtPrintActivity` 或调用连接 API 前申请 `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT`；`BtPrintActivity` 内置了权限申请逻辑，直接使用即可。

## 快速开始

### 1. 初始化（Application 中调用一次）

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PrintUtils.init(this)
        // 可选：切换纸张宽度（默认 58mm），设置后持久化，下次启动自动恢复
        PrintUtils.setPaperWidth(BtPrintManager.PaperWidth.MM_80)
        // 可选：多列排版超宽时按表格模式折行（默认关闭，保持"超宽列掉到下一行"的旧行为）
        PrintUtils.setColumnWrapEnabled(true)
    }
}
```

### 2. 连接打印机

```kotlin
// 方式一：自动连接上次使用的设备（自动复现上次的连接类型：SPP / BLE / TCP）
val ok = PrintUtils.autoConnectLastDevice()

// 方式二：跳转 SDK 内置的设备连接页面（按设备类型自动选择 SPP 或 BLE）
startActivity(Intent(this, BtPrintActivity::class.java))

// 方式三：手动连接经典蓝牙设备（SPP）
val device: BluetoothDevice = ... // 通过 BtDeviceManager.getInstance(this).bondedDevices 获取
lifecycleScope.launch {
    val ok = BtPrintManager.getInstance(this@YourActivity).connect(device)
}

// 方式四：连接 BLE 打印机（GATT 透传，自动适配常见透传 UUID）
PrintUtils.connectBleWait(device)

// 方式五：连接网络打印机（Wi-Fi / 以太网，ESC/POS over TCP）
PrintUtils.connectTcpWait("192.168.1.100")            // 默认端口 9100
PrintUtils.connectTcpWait("192.168.1.100", 9100)

// 方式六：跳转 SDK 内置的 WiFi 连接页（手动输入 IP 或一键扫描局域网打印机）
startActivity(Intent(this, WifiPrintActivity::class.java))
```

当前连接目标（设备或网络地址）可通过 `PrintUtils.connectionTarget` 观察。

### 3. 打印（异步 fire-and-forget 版）

```kotlin
PrintUtils.printTitle("欢迎光临")                          // 标题（带分割线）
PrintUtils.printDivider()                                 // 分割线
PrintUtils.printText("你好，世界", align = PrintUtils.ALIGN_CENTER)  // 居中文本
PrintUtils.printTwo("商品", "100.0")                      // 两列排版
PrintUtils.printThree("商品", "1", "800.0")               // 三列排版
PrintUtils.printQrCode("https://example.com")             // 二维码
PrintUtils.printBarCode("1234567890128")                  // 条形码（默认 CODE128）
PrintUtils.printImage(bitmap)                             // 图片打印
PrintUtils.printEmptyLines(2)                             // 空行
PrintUtils.cutPaper()                                     // 走纸 3 行后半切（CUT_FULL 全切）
PrintUtils.openCashDrawer()                               // 开钱箱（需机型带钱箱接口）
```

### 4. 打印（挂起版，可等待完成）

```kotlin
lifecycleScope.launch {
    PrintUtils.printTextWait("等待完成", fontSize = PrintUtils.FONT_SIZE_LARGE)
    PrintUtils.printBarCodeWait("12312312312312")
}
```

### 5. 任务队列与结果监听

`PrintUtils` 内部维护严格 FIFO 的打印队列，异步版 API（`printText` 等）入队后立即返回 `Job`；
打印完成/失败通过监听器回调（运行在 IO 线程，UI 更新需自行切主线程）：

```kotlin
PrintUtils.setPrintResultListener { result ->
    if (!result.success) {
        Log.e("Print", "${result.taskType} 失败", result.error)
    }
}

PrintUtils.queueSize.collect { size -> /* 队列中待执行任务数 */ }
PrintUtils.setMaxQueueSize(200) // 队列容量上限（默认 100），满员后入队任务丢弃并回调失败
PrintUtils.clearQueue()        // 清空未执行任务；中断进行中的打印用 clearQueue() + disconnect()
PrintUtils.cancelPrint(job)    // 仅对尚未出队的任务有效
```

未连接打印机时任务会立即失败并回调 `success = false`（异常为 `IOException("打印机未连接")`），
不会出现"没打印却报成功"的情况。

### 6. 断开连接

```kotlin
PrintUtils.disconnect()
```

> 连接（`connect`/`reconnect`）与打印使用独立的锁：连接进行期间入队的打印任务会快速失败，
> 而不会阻塞等待 RFCOMM 连接完成。

## 常用常量

以下常量既可通过 `PrintUtils.XXX` 使用（门面重导出），也可通过 `BtPrintManager.XXX` 使用：

| 常量 | 说明 |
|---|---|
| `FONT_SIZE_SMALL / NORMAL / LARGE` | 小 / 中 / 大字号 |
| `ALIGN_LEFT / CENTER / RIGHT` | 左 / 中 / 右对齐 |
| `CUT_FULL / CUT_PARTIAL` | 切纸模式：全切 / 半切 |
| `BtPrintManager.PaperWidth.MM_58 / MM_80` | 纸张宽度：58mm（默认）/ 80mm |
| `BarcodeType.CODE128` 等 9 种 | 条形码类型 |

> 多列排版与分割线的行宽会随纸张宽度和字号自动换算：`printDivider()`、`printTitle()` 的
> 长度参数传 `-1`（默认）即自动铺满当前纸宽整行，也可显式传字符数覆盖。

## 混淆配置

宿主开启混淆时无需额外配置，SDK 已通过 `consumerProguardFiles` 自带保留规则（`proguard-rules.pro`），公共 API 会自动保留。

## 构建要求

- Gradle 8.7+ / JDK 17+
- AGP 8.5.2、Kotlin 1.9.24
- compileSdk 34 / minSdk 21 / targetSdk 34

## 注意事项

- SDK 使用 GBK 编码发送文本与二维码内容，适配国内主流 58mm / 80mm 热敏打印机（`setPaperWidth` 切换）
- 连接超时（`BtPrintManager.connectTimeoutMs`，默认 20s）与写入超时（`writeTimeoutMs`，默认 10s）可按业务调整；图片打印超过 200 万像素会拒绝打印并提示压缩（`BtPrintManager.MAX_PRINT_PIXELS`）
- 图片打印建议使用黑白或高对比度图片，效果最佳
- 各排版方法内部显式管理对齐方式，任务结束后恢复左对齐，连续调用不会因对齐状态残留而错位
- BLE 通道按 MTU 分包写入（默认 20 字节，协商后最大 509），大图打印比 SPP 慢属正常现象；
  已适配 FFE0/FFE1、FFE5/FFE9、Nordic UART、ISSC 等常见透传服务，未命中时兜底使用第一个可写 characteristic
- 网络打印机需与手机在同一局域网，ESC/POS 指令通过 TCP 9100 端口发送
- `BtPrintActivity` 的界面文案全部走字符串资源（`btp_` 前缀），宿主可通过同名资源覆写实现多语言
- `PrintUtils` 内部持有独立协程作用域，`release()` 可释放资源
