package com.seagull.multiat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 海鸥 Multi-@ 悬浮窗服务
 *
 * 显示一个半透明的浮动按钮，在微信聊天界面时展示。
 * 功能：
 * - 点击切换 开启/关闭 多选模式
 * - 显示已选中的 @ 数量
 * - 提供状态提示
 *
 * @author 海鸥
 */
public class OverlayService extends Service {

    private static final String TAG = "海鸥Overlay";
    private static final String CHANNEL_ID = "multiat_overlay";

    private WindowManager mWindowManager;
    private View mOverlayView;
    private boolean mIsOverlayShowing = false;

    private ImageView mSeagullIcon;
    private TextView mAtCountBadge;
    private View mActiveIndicator;

    private MultiAtService mMultiAtService;
    private boolean mIsActive = false;

    private Handler mHandler = new Handler(Looper.getMainLooper());

    // 悬浮窗参数
    private WindowManager.LayoutParams mParams;
    private int mInitialX, mInitialY;
    private float mTouchStartX, mTouchStartY;
    private boolean mIsDragging = false;

    // 自动隐藏
    private static final long AUTO_HIDE_DELAY = 10000;
    private Runnable mHideRunnable;

    public class LocalBinder extends Binder {
        public OverlayService getService() {
            return OverlayService.this;
        }
    }

    private final IBinder mBinder = new LocalBinder();

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "悬浮窗服务创建");

        createNotificationChannel();
        startForeground(1, createNotification());

        mWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (mOverlayView == null) {
            createOverlay();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        removeOverlay();
        mHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ======================== 悬浮窗管理 ========================

    private void createOverlay() {
        try {
            mOverlayView = LayoutInflater.from(this).inflate(
                    com.seagull.multiat.R.layout.overlay_layout, null);

            mSeagullIcon = mOverlayView.findViewById(com.seagull.multiat.R.id.seagull_icon);
            mAtCountBadge = mOverlayView.findViewById(com.seagull.multiat.R.id.at_count_badge);
            mActiveIndicator = mOverlayView.findViewById(com.seagull.multiat.R.id.active_indicator);

            // 初始化状态
            updateUiForState(false);

            // 点击切换模式
            mOverlayView.setOnClickListener(v -> {
                if (mMultiAtService != null) {
                    if (mIsActive) {
                        mMultiAtService.stopMultiAt();
                    } else {
                        if (!mMultiAtService.isInChatScreen()) {
                            showToast("请先进入微信群聊界面");
                            return;
                        }
                        mMultiAtService.startMultiAt();
                    }
                    toggleState();
                } else {
                    showToast("无障碍服务未连接");
                }
            });

            // 长按拖拽
            mOverlayView.setOnLongClickListener(v -> {
                mIsDragging = true;
                return true;
            });

            // 触摸事件
            mOverlayView.setOnTouchListener((v, event) -> {
                if (mIsDragging) {
                    handleDrag(event);
                    return true;
                }
                return false;
            });

            // 悬浮窗参数
            mParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    getOverlayType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT
            );
            mParams.gravity = Gravity.TOP | Gravity.START;
            mParams.x = 0;
            mParams.y = 200;
            mParams.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            mParams.height = ViewGroup.LayoutParams.WRAP_CONTENT;

            mWindowManager.addView(mOverlayView, mParams);
            mIsOverlayShowing = true;

            Log.d(TAG, "✅ 悬浮窗已显示");

        } catch (Exception e) {
            Log.e(TAG, "创建悬浮窗失败: " + e.getMessage());
        }
    }

    private void removeOverlay() {
        if (mOverlayView != null && mIsOverlayShowing) {
            try {
                mWindowManager.removeView(mOverlayView);
            } catch (Exception e) {
                Log.e(TAG, "移除悬浮窗失败: " + e.getMessage());
            }
            mOverlayView = null;
            mIsOverlayShowing = false;
        }
    }

    private int getOverlayType() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            return WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
        }
    }

    private void handleDrag(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                mTouchStartX = event.getRawX();
                mTouchStartY = event.getRawY();
                mInitialX = mParams.x;
                mInitialY = mParams.y;
                break;

            case MotionEvent.ACTION_MOVE:
                mParams.x = mInitialX + (int) (event.getRawX() - mTouchStartX);
                mParams.y = mInitialY + (int) (event.getRawY() - mTouchStartY);
                mWindowManager.updateViewLayout(mOverlayView, mParams);
                break;

            case MotionEvent.ACTION_UP:
                mIsDragging = false;
                // 贴边
                snapToEdge();
                break;
        }
    }

    private void snapToEdge() {
        try {
            int screenWidth = mWindowManager.getDefaultDisplay().getWidth();
            if (mParams.x + mOverlayView.getWidth() / 2 > screenWidth / 2) {
                mParams.x = screenWidth - mOverlayView.getWidth() - 10;
            } else {
                mParams.x = 10;
            }
            mWindowManager.updateViewLayout(mOverlayView, mParams);
        } catch (Exception ignored) {}
    }

    // ======================== UI更新 ========================

    private void toggleState() {
        mIsActive = !mIsActive;
        updateUiForState(mIsActive);
    }

    private void updateUiForState(boolean active) {
        if (mSeagullIcon != null) {
            mSeagullIcon.setAlpha(active ? 1.0f : 0.6f);
        }
        if (mActiveIndicator != null) {
            mActiveIndicator.setVisibility(active ? View.VISIBLE : View.GONE);
        }
        updateBadge(0);
    }

    private void updateBadge(int count) {
        if (mAtCountBadge != null) {
            if (count > 0) {
                mAtCountBadge.setVisibility(View.VISIBLE);
                mAtCountBadge.setText(String.valueOf(count));
            } else {
                mAtCountBadge.setVisibility(View.GONE);
            }
        }
    }

    // ======================== 对外接口 ========================

    public void setMultiAtService(MultiAtService service) {
        mMultiAtService = service;
    }

    /**
     * 更新@次数
     */
    public void updateAtCount(int count) {
        mHandler.post(() -> updateBadge(count));
    }

    /**
     * 检测到选择器打开
     */
    public void showPickerDetected() {
        // 可以做一个呼吸动画效果
        if (mSeagullIcon != null) {
            mSeagullIcon.animate()
                    .scaleX(1.2f).scaleY(1.2f)
                    .setDuration(200)
                    .withEndAction(() -> mSeagullIcon.animate()
                            .scaleX(1f).scaleY(1f)
                            .setDuration(200)
                            .start())
                    .start();
        }
    }

    /**
     * 显示Toast提示
     */
    public void showToast(String message) {
        mHandler.post(() ->
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }

    /**
     * 重置状态
     */
    public void reset() {
        mIsActive = false;
        mHandler.post(() -> updateUiForState(false));
    }

    // ======================== 通知（前台服务必需） ========================

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "海鸥 Multi-@",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Multi-@ 悬浮窗后台服务");
            channel.setShowBadge(false);

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification createNotification() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        return builder
                .setContentTitle("海鸥 Multi-@")
                .setContentText("微信群聊 @ 多选增强运行中")
                .setSmallIcon(com.seagull.multiat.R.drawable.ic_seagull)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_LOW)
                .build();
    }
}
