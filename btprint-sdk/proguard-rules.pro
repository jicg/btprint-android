# 蓝牙打印 SDK - 保留公共 API，供宿主应用开启混淆时消费
-keep public class com.jicg.btprint.BtPrintManager { *; }
-keep public class com.jicg.btprint.BtPrintManager$BarcodeType { *; }
# 纸张宽度枚举：fromName() 依赖枚举名与 SharedPreferences 存储值比对，混淆会导致纸宽设置失效
-keep public class com.jicg.btprint.BtPrintManager$PaperWidth { *; }
-keep public class com.jicg.btprint.PrintUtils { *; }
# 打印结果回调的公开类型
-keep public class com.jicg.btprint.PrintResult { *; }
-keep public class com.jicg.btprint.BtDeviceManager { *; }
-keep public class com.jicg.btprint.ImageUtils { *; }
-keep public class com.jicg.btprint.BtPrintActivity { *; }
-keep public class com.jicg.btprint.WifiPrintActivity { *; }
-keep public class com.jicg.btprint.LanPrinterScanner { *; }
# 传输层公开类型（ConnectionTarget 出现在 PrintUtils.connectionTarget 返回值中，
# 宿主可能实现 PrintTransport 自定义通道），混淆会导致宿主编译/运行断裂
-keep public class com.jicg.btprint.transport.PrintTransport { *; }
-keep public class com.jicg.btprint.transport.ConnectionTarget { *; }
-keep public class com.jicg.btprint.transport.ConnectionTarget$* { *; }
-keep public class com.jicg.btprint.transport.SppTransport { *; }
-keep public class com.jicg.btprint.transport.BleTransport { *; }
-keep public class com.jicg.btprint.transport.TcpTransport { *; }
