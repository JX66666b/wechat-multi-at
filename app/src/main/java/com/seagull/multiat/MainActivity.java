package com.seagull.multiat;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 海鸥 Multi-@ 设置界面
 *
 * 三种激活方式：
 * 1. 无障碍服务 - 手动在设置里开启
 * 2. ADB命令 - 一行命令激活
 * 3. Shizuku - 通过Shizuku激活（需安装Shizuku）
 */
public class MainActivity extends Activity {

    private TextView tvStatus;
    private TextView tvAdbCommand;
    private Button btnOpenSettings;
    private Button btnCopyAdb;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tv_status);
        tvAdbCommand = findViewById(R.id.tv_adb_command);
        btnOpenSettings = findViewById(R.id.btn_open_settings);
        btnCopyAdb = findViewById(R.id.btn_copy_adb);

        btnOpenSettings.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        });

        btnCopyAdb.setOnClickListener(v -> {
            String cmd = getAdbCommand();
            android.content.ClipboardManager clip =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            clip.setPrimaryClip(android.content.ClipData.newPlainText("adb", cmd));
            Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.btn_shizuku).setOnClickListener(v -> {
            if (ShizukuHelper.isShizukuInstalled(this)) {
                ShizukuHelper.activate(this);
            } else {
                Toast.makeText(this, "未安装Shizuku，先安装Shizuku App", Toast.LENGTH_LONG).show();
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://shizuku.rikka.app/download/"));
                    startActivity(intent);
                } catch (Exception ignored) {}
            }
        });

        findViewById(R.id.btn_open_wechat).setOnClickListener(v -> {
            try {
                Intent intent = getPackageManager()
                        .getLaunchIntentForPackage("com.tencent.mm");
                if (intent != null) startActivity(intent);
                else Toast.makeText(this, "未安装微信", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "打开失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });

        // 版本号
        try {
            String version = getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
            ((TextView) findViewById(R.id.tv_version)).setText("v" + version);
        } catch (Exception ignored) {}
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    private void updateStatus() {
        boolean enabled = isAccessibilityServiceEnabled();
        if (enabled) {
            tvStatus.setText("✅ 服务已开启 | 模式: " + (isWeChatInstalled() ? "微信已安装" : "微信未安装"));
            tvStatus.setTextColor(getColor(android.R.color.holo_green_dark));
            btnOpenSettings.setText("进入无障碍设置");
        } else {
            tvStatus.setText("❌ 服务未开启 - 请选择一种方式激活");
            tvStatus.setTextColor(getColor(android.R.color.holo_red_dark));
            btnOpenSettings.setText("去开启无障碍服务");
        }

        // 更新ADB命令
        tvAdbCommand.setText(getAdbCommand());
    }

    private boolean isAccessibilityServiceEnabled() {
        String service = getPackageName() + "/" + MultiAtService.class.getCanonicalName();
        try {
            String enabledServices = Settings.Secure.getString(
                    getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return enabledServices != null && enabledServices.contains(service);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isWeChatInstalled() {
        try {
            getPackageManager().getPackageInfo("com.tencent.mm", 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private String getAdbCommand() {
        String serviceName = getPackageName() + "/" + MultiAtService.class.getCanonicalName();
        return "adb shell settings put secure enabled_accessibility_services " + serviceName
                + "\nadb shell settings put secure accessibility_enabled 1";
    }

    public void openGitHub(View view) {
        Intent intent = new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://github.com/JX66666b/wechat-multi-at"));
        startActivity(intent);
    }
}
