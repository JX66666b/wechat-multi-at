package com.seagull.multiat;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.widget.Toast;

/**
 * Shizuku 激活助手
 *
 * 通过 Shizuku API 在app内直接激活无障碍服务
 * 无需跳转到系统设置，无需连接电脑
 *
 * 使用前提：
 * 1. 已安装 Shizuku App (moe.shizuku.privileged.api)
 * 2. Shizuku 已在运行中
 *
 * @author 海鸥
 */
public class ShizukuHelper {

    /**
     * 通过Shizuku一键激活无障碍服务
     */
    public static boolean activate(Context context) {
        try {
            // 检测Shizuku是否在运行
            Class<?> shizukuClass = Class.forName("moe.shizuku.api.Shizuku");

            int result = (int) shizukuClass.getMethod("pingBinder").invoke(null);
            if (result != 0) {
                Toast.makeText(context, "Shizuku 未在运行，请先启动Shizuku", Toast.LENGTH_LONG).show();
                return false;
            }

            // 构建我们要执行的命令
            String serviceName = context.getPackageName()
                    + "/" + MultiAtService.class.getCanonicalName();
            String[] cmds = {
                    "settings put secure enabled_accessibility_services " + serviceName,
                    "settings put secure accessibility_enabled 1"
            };

            // 通过Shizuku执行命令
            Class<?> processClass = Class.forName("moe.shizuku.api.Shizuku$Process");
            Object process = shizukuClass
                    .getMethod("newProcess", String[].class, String.class, String[].class)
                    .invoke(null, cmds, null, null);

            if (process != null) {
                // 等待命令执行完毕
                processClass.getMethod("waitFor").invoke(process);
            }

            // 验证是否激活成功
            boolean success = false;
            try {
                String enabledServices = Settings.Secure.getString(
                        context.getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                success = enabledServices != null && enabledServices.contains(serviceName);
            } catch (Exception ignored) {}

            if (success) {
                Toast.makeText(context, "✅ 海鸥 Multi-@ 已通过Shizuku激活！", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(context, "激活完成，请检查无障碍设置", Toast.LENGTH_LONG).show();
            }

            return success;

        } catch (ClassNotFoundException e) {
            // Shizuku API 不在类路径中（用 compileOnly 时）
            Toast.makeText(context, "Shizuku API 未找到，请使用ADB方式", Toast.LENGTH_LONG).show();
            return false;
        } catch (Exception e) {
            Toast.makeText(context, "Shizuku 激活失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 检测是否安装了 Shizuku App
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
     * 打开 Shizuku App
     */
    public static void openShizuku(Context context) {
        try {
            Intent intent = context.getPackageManager()
                    .getLaunchIntentForPackage("moe.shizuku.privileged.api");
            if (intent != null) {
                context.startActivity(intent);
            }
        } catch (Exception e) {
            Toast.makeText(context, "无法打开Shizuku", Toast.LENGTH_SHORT).show();
        }
    }
}
