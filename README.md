# btprint-android（蓝牙打印 SDK）

基于 Android + Kotlin 的蓝牙热敏打印机 SDK，提供完整的 ESC/POS 打印能力，可通过 **Maven Central**、AAR 文件或 Module 依赖集成到任意第三方 Android 应用。

## 功能特性

- **蓝牙连接**：SPP 连接 / 断开 / 重连 / 自动连接上次设备 / 连接状态检测
- **文本打印**：支持字号（小/中/大）、对齐方式（左/中/右）、多行换行
- **排版打印**：两列 / 三列排版、分割线、标题
- **二维码打印**：支持自定义大小与对齐
- **条形码打印**：支持 CODE128、EAN13、EAN8、UPC-A、UPC-E、CODE39、ITF、ONE_CODE93、CODABAR 共 9 种类型
- **图片打印**：自动压缩 + Floyd-Steinberg 抖动点阵算法
- **设备管理**：已配对设备列表、连接入口 UI（`BtPrintActivity`，可选集成）

## 工程结构

```
├── btprint-sdk/                # SDK 模块（com.jicg.btprint）
│   └── build/outputs/aar/      # 构建产物 btprint-sdk-release.aar
└── app/                        # 演示（demo）应用，展示 SDK 集成方式
```

## 集成方式

### 方式一：Maven Central（推荐，无需拷贝源码）

SDK 已发布到 Maven Central（central.sonatype.com），直接引用坐标即可。

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
    implementation 'io.github.jicg:btprint-sdk:1.0.0'
}
```

**坐标信息**

| 项 | 值 |
|---|---|
| 坐标系 | `io.github.jicg:btprint-sdk:1.0.0` |
| 仓库地址 | `https://repo1.maven.org/maven2`（即 `mavenCentral()`） |
| 产物 | AAR（含资源/清单）、sources 源码包（IDE 可跳转源码）、POM（自动带传递依赖）、GPG 签名 |

**传递依赖**（POM 自动携带，消费方无需手动添加）：

- `androidx.core:core-ktx:1.12.0`
- `androidx.activity:activity-ktx:1.8.0`
- `androidx.lifecycle:lifecycle-runtime-ktx:2.7.0`
- `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3`

**消费方要求**：

- compileSdk ≥ 34、minSdk ≥ 21、targetSdk ≥ 34
- AGP 8.x、Gradle 8.7+、JDK 17+
- 需保留 `google()` / `mavenCentral()` 仓库用于解析上述传递依赖


### 方式二：AAR 文件引入

1. 构建 AAR：

```bash
gradlew :btprint-sdk:assembleRelease
```

2. 将 `btprint-sdk/build/outputs/aar/btprint-sdk-release.aar` 复制到宿主工程 `app/libs/` 目录

3. 在 `app/build.gradle` 中引入：

```groovy
dependencies {
    implementation files('libs/btprint-sdk-release.aar')
}
```

### 方式三：Module 依赖（源码级集成）

```groovy
// settings.gradle
include ':btprint-sdk'

// app/build.gradle
dependencies {
    implementation project(':btprint-sdk')
}
```

## 权限说明

SDK 已在自身 `AndroidManifest.xml` 声明以下权限，manifest 合并时会自动注入宿主应用，**无需在宿主中重复声明**：

- `BLUETOOTH`、`BLUETOOTH_ADMIN`（Android 11 及以下）
- `BLUETOOTH_SCAN`（`neverForLocation`）、`BLUETOOTH_CONNECT`（Android 12+）
- `ACCESS_FINE_LOCATION`、`ACCESS_COARSE_LOCATION`（低版本扫描兼容）

**运行时权限**：Android 12+ 需要宿主在跳转 `BtPrintActivity` 或调用连接 API 前申请 `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT`；`BtPrintActivity` 内置了权限申请逻辑，直接使用即可。

## 快速开始

### 1. 初始化（Application 中调用一次）

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PrintUtils.init(this)
    }
}
```

### 2. 连接打印机

```kotlin
// 方式一：自动连接上次使用的设备
val ok = PrintUtils.autoConnectLastDevice()

// 方式二：跳转 SDK 内置的设备连接页面
startActivity(Intent(this, BtPrintActivity::class.java))

// 方式三：手动连接指定设备
val device: BluetoothDevice = ... // 通过 BtDeviceManager.getInstance(this).bondedDevices 获取
lifecycleScope.launch {
    val ok = BtPrintManager.getInstance(this@YourActivity).connect(device)
}
```

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
```

### 4. 打印（挂起版，可等待完成）

```kotlin
lifecycleScope.launch {
    PrintUtils.printTextWait("等待完成", fontSize = PrintUtils.FONT_SIZE_LARGE)
    PrintUtils.printBarCodeWait("12312312312312")
}
```

### 5. 断开连接

```kotlin
PrintUtils.disconnect()
```

## 常用常量

| 常量 | 说明 |
|---|---|
| `FONT_SIZE_SMALL / NORMAL / LARGE` | 小 / 中 / 大字号 |
| `ALIGN_LEFT / CENTER / RIGHT` | 左 / 中 / 右对齐 |
| `BarcodeType.CODE128` 等 9 种 | 条形码类型 |

## 混淆配置

宿主开启混淆时无需额外配置，SDK 已通过 `consumerProguardFiles` 自带保留规则（`proguard-rules.pro`），公共 API 会自动保留。

## 构建要求

- Gradle 8.7+ / JDK 17+
- AGP 8.5.2、Kotlin 1.9.24
- compileSdk 34 / minSdk 21 / targetSdk 34

## 注意事项

- SDK 使用 GBK 编码发送文本，适配国内主流 58/80mm 热敏打印机
- 图片打印建议使用黑白或高对比度图片，效果最佳
- `PrintUtils` 内部持有独立协程作用域，`release()` 可释放资源
