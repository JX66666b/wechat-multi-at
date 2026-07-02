package com.seagull.multiat;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;

/**
 * 海鸥 WeChat Multi-@ 主界面
 *
 * 引导用户开启无障碍服务和悬浮窗权限
 *
 * @author 海鸥
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_OVERLAY_PERMISSION = 1001;

    private CardView cardAccessibility;
    private CardView cardOverlay;
    private CardView cardUsage;
    private Button btnOpenAccessibility;
    private Button btnOpenOverlay;
    private Button btnOpenWeChat;
    private TextView tvAccessibilityStatus;
    private TextView tvOverlayStatus;
    private TextView tvVersion;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(com.seagull.multiat.R.layout.activity_main);

        initViews();
        setupClickListeners();
        updatePermissionStatus();

        // 版本号
        try {
            String version = getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
            tvVersion.setText("v" + version);
        } catch (Exception e) {
            tvVersion.setText("v1.0.0");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePermissionStatus();
    }

    private void initViews() {
        cardAccessibility = findViewById(com.seagull.multiat.R.id.card_accessibility);
        cardOverlay = findViewById(com.seagull.multiat.R.id.card_overlay);
        cardUsage = findViewById(com.seagull.multiat.R.id.card_usage);

        btnOpenAccessibility = findViewById(com.seagull.multiat.R.id.btn_open_accessibility);
        btnOpenOverlay = findViewById(com.seagull.multiat.R.id.btn_open_overlay);
        btnOpenWeChat = findViewById(com.seagull.multiat.R.id.btn_open_wechat);

        tvAccessibilityStatus = findViewById(com.seagull.multiat.R.id.tv_accessibility_status);
        tvOverlayStatus = findViewById(com.seagull.multiat.R.id.tv_overlay_status);
        tvVersion = findViewById(com.seagull.multiat.R.id.tv_version);
    }

    private void setupClickListeners() {
        // 打开无障碍设置
        btnOpenAccessibility.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            Toast.makeText(this,
                    "找到「海鸥 Multi-@」并开启服务",
                    Toast.LENGTH_LONG).show();
        });

        // 打开悬浮窗权限
        btnOpenOverlay.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Intent intent = new Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())
                );
                startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION);
            }
        });

        // 打开微信
        btnOpenWeChat.setOnClickListener(v -> {
            try {
                Intent intent = getPackageManager()
                        .getLaunchIntentForPackage("com.tencent.mm");
                if (intent != null) {
                    startActivity(intent);
                } else {
                    Toast.makeText(this, "未安装微信", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Toast.makeText(this, "无法打开微信: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void updatePermissionStatus() {
        // 检查无障碍服务
        boolean accessibilityEnabled = isAccessibilityServiceEnabled();
        tvAccessibilityStatus.setText(accessibilityEnabled ?
                "✅ 已开启" : "❌ 未开启");
        tvAccessibilityStatus.setTextColor(getColor(
                accessibilityEnabled ?
                        android.R.color.holo_green_dark :
                        android.R.color.holo_red_dark
        ));

        // 检查悬浮窗权限
        boolean overlayEnabled = Settings.canDrawOverlays(this);
        tvOverlayStatus.setText(overlayEnabled ?
                "✅ 已授权" : "❌ 未授权");
        tvOverlayStatus.setTextColor(getColor(
                overlayEnabled ?
                        android.R.color.holo_green_dark :
                        android.R.color.holo_red_dark
        ));

        // 更新卡片状态
        cardAccessibility.setAlpha(accessibilityEnabled ? 1.0f : 0.6f);
        cardOverlay.setAlpha(overlayEnabled ? 1.0f : 0.6f);
    }

    /**
     * 检查无障碍服务是否已开启
     */
    private boolean isAccessibilityServiceEnabled() {
        String service = getPackageName() + "/" + MultiAtService.class.getCanonicalName();
        try {
            String enabledServices = Settings.Secure.getString(
                    getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            );
            return enabledServices != null && enabledServices.contains(service);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 打开GitHub（以后开源用）
     */
    public void openGitHub(View view) {
        // TODO: 替换为实际的GitHub地址
        Intent intent = new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://github.com/seagull-hacker/wechat-multi-at"));
        startActivity(intent);
    }
}
