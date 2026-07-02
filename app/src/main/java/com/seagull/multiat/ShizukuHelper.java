package com.seagull.multiat;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.widget.Toast;

/**
 * Shizuku / ADB 激活助手
 *
 * 提供三种激活方式：
 * 1. 无障碍服务 - 手动在设置里开启（最简单）
 * 2. ADB命令 - 电脑执行一行命令
 * 3. Shizuku - 通过Shizuku终端执行命令（不需要电脑）
 *
 * 注意：不依赖Shizuku API库，通过Intent和剪贴板交互
 *
 * @author 海鸥
 */
public class ShizukuHelper {

    /**
     * 获取激活无障碍服务的ADB命令
     */
    public static String getActivationCommand(Context context) {
        String serviceName = context.getPackageName()
                + "/" + MultiAtService.class.getCanonicalName();
        return "adb shell settings put secure enabled_accessibility_services "
                + serviceName
                + " && adb shell settings put secure accessibility_enabled 1";
    }

    /**
     * 检查是否安装了 Shizuku
     */
    public static boolean isShizukuInstalled(Context context) {
        try {
            context.getPackageManager()
                    .getPackageInfo("moe.shizuku.privileged.api", 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * 检查无障碍服务是否已激活
     */
    public static boolean isServiceEnabled(Context context) {
        String service = context.getPackageName()
                + "/" + MultiAtService.class.getCanonicalName();
        try {
            String enabled = Settings.Secure.getString(
                    context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return enabled != null && enabled.contains(service);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 打开Shizuku并提示用户运行激活命令
     */
    public static void showShizukuGuide(Context context) {
        String cmd = getActivationCommand(context)
                .replace("adb shell ", "");  // Shizuku终端不用加adb shell前缀

        // 复制到剪贴板
        ClipboardManager clip = (ClipboardManager)
                context.getSystemService(Context.CLIPBOARD_SERVICE);
        clip.setPrimaryClip(ClipData.newPlainText("shizuku_cmd", cmd));

        // 打开Shizuku
        try {
            Intent intent = context.getPackageManager()
                    .getLaunchIntentForPackage("moe.shizuku.privileged.api");
            if (intent != null) {
                context.startActivity(intent);
            }
        } catch (Exception ignored) {}

        // 提示
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Shizuku 激活步骤");
        builder.setMessage(
                "1. 确保 Shizuku 已在运行中\n"
                + "2. 打开 Shizuku 的「终端」\n"
                + "3. 粘贴命令并执行\n\n"
                + "命令已复制到剪贴板:\n" + cmd);
        builder.setPositiveButton("已复制，去粘贴", null);
        builder.setNegativeButton("算了，用其他方式", null);
        builder.show();
    }

    /**
     * 判断是否应该使用Shizuku方式（有安装且在运行）
     * 简单判断：有安装就算
     */
    public static boolean canUseShizuku(Context context) {
        return isShizukuInstalled(context);
    }
}
