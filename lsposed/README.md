# 海鸥 WeChat Multi-@ - LSPosed 方案（需要Root）

如果你手机已经 Root + 安装了 LSPosed Framework，可以用这个模块。
效果比无障碍方案更流畅，直接修改微信内部行为。

## 构建

用 Android Studio 打开 lsposed/ 目录，直接 Build → Build APK。

## 安装

1. 编译得到 APK
2. LSPosed 中激活模块
3. 勾选微信 (com.tencent.mm)
4. 重启微信

## Hook原理

拦截微信联系人选择器 Activity 的 setResult/finish 方法：
- 选择一个人后不关闭界面
- 自动积累选择列表
- 点确定后一次性回传所有选中的联系人

## 注意

微信版本更新后内部类名可能变化，需要更新 ContactPickerHook.java 中的类名。
