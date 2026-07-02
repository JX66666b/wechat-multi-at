#!/bin/bash
# ============================================
# 海鸥 WeChat Multi-@ ADB 一键激活脚本
# ============================================
# 用法:
#   1. 手机连接电脑, 开启USB调试
#   2. 运行: sh adb-activate.sh
# ============================================

PACKAGE="com.seagull.multiat"
SERVICE="${PACKAGE}/.MultiAtService"

echo "🕊️ 海鸥 Multi-@ ADB 激活"
echo "=========================="
echo ""

# 检查adb
if ! command -v adb &> /dev/null; then
    echo "❌ 没有找到 adb 命令"
    echo "请安装 Android Platform Tools"
    echo "下载: https://developer.android.com/studio/releases/platform-tools"
    exit 1
fi

# 检查设备
DEVICES=$(adb devices | grep -v "List" | grep -v "^$" | wc -l)
if [ "$DEVICES" -eq 0 ]; then
    echo "❌ 没有连接到设备"
    echo "请用USB连接手机并开启USB调试"
    exit 1
fi

echo "📱 已连接设备:"
adb devices | grep -v "List"

echo ""
echo "🔧 正在激活无障碍服务..."

# 激活无障碍服务
adb shell settings put secure enabled_accessibility_services "$SERVICE"

# 开启无障碍功能
adb shell settings put secure accessibility_enabled 1

echo ""
echo "✅ 服务已激活!"
echo ""
echo "查看服务状态:"
adb shell settings get secure enabled_accessibility_services | grep "$PACKAGE" && echo "  状态: 🟢 已开启" || echo "  状态: 🔴 未开启"
echo ""
echo "📱 现在打开微信群聊"
echo "输入 @ + 关键词 搜索联系人"
echo "选完一个人后自动续接搜索!"
echo ""
echo "要关闭服务: adb shell settings put secure enabled_accessibility_services ''"
