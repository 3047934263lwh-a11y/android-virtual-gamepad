# minify + shrinkResources 已启用（2026-11-04 复审 M-19）。
# 只保留 XML inflate 反射所需的自定义 View 构造函数，其余业务代码正常参与压缩与混淆。
# （Material/AppCompat 自带 consumer rules，无需额外配置。）
-keep class com.vgamepad.GamepadView { public <init>(...); }
-keep class com.vgamepad.ExpandTriangleView { public <init>(...); }
