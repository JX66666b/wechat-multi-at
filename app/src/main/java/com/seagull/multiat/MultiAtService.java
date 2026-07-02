package com.seagull.multiat;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.List;

/**
 * 海鸥 WeChat Multi-@ 无障碍服务
 *
 * 核心逻辑：
 * 1. 用户在群里输入 @ + 关键词 → 选择器打开
 * 2. 用户选了一个联系人 → 选择器关闭，@某人 插入到输入框
 * 3. 本服务检测到输入框变化 → 读取选择器里的搜索词 → 自动重新填 @ + 搜索词
 * 4. 选择器再次打开并且搜索词已填入 → 用户继续选下一个人
 * 5. 如果选择器搜索框内容是空的（用户直接选人）→ 自动填 @ 但不带搜索词
 *
 * 激活方式：
 * - 无障碍服务（在设置里手动开启）
 * - ADB命令：adb shell settings put secure enabled_accessibility_services ...
 * - Shizuku + 本app内置激活功能
 *
 * @author 海鸥
 */
public class MultiAtService extends AccessibilityService {

    private static final String TAG = "海鸥MultiAt";
    private static final String WECHAT_PKG = "com.tencent.mm";
    private static final String CHANNEL_ID = "multiat_channel";
    private static final int NOTIFY_ID = 1001;

    // 通知动作
    private static final String ACTION_TOGGLE = "com.seagull.multiat.TOGGLE";
    private static final String ACTION_STOP = "com.seagull.multiat.STOP";

    // 状态
    private boolean mEnabled = false;

    // === 选择器监控状态 ===
    private boolean mInPicker = false;        // 当前是否在@选择器中
    private boolean mWasInPicker = false;     // 上一帧是否在@选择器中
    private String mSavedSearchText = "";     // 选择器里最后记下的搜索词
    private int mChatTextLenBefore = -1;      // 进入选择器前的输入框长度
    private boolean mIsRetriggering = false;  // 正在自动触发中（防递归）

    // 聊天界面Activity的特征类名
    private static final String[] CHAT_CLASSES = {
            ".ui.chatting.ChattingUI",
            ".ui.chatting."
    };

    private Handler mHandler = new Handler(Looper.getMainLooper());
    private NotificationManager mNotifyManager;

    // ============================================================
    // 服务生命周期
    // ============================================================

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "海鸥 Multi-@ 服务创建！");

        mNotifyManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createNotificationChannel();

        // 默认开启
        mEnabled = true;
        updateNotification();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_TOGGLE.equals(intent.getAction())) {
            // 从通知按钮过来的切换指令
            toggleEnabled();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        mHandler.removeCallbacksAndMessages(null);
        mNotifyManager.cancel(NOTIFY_ID);
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!mEnabled) return;

        String pkg = event.getPackageName() != null ? event.getPackageName().toString() : "";
        if (!WECHAT_PKG.equals(pkg)) return;

        switch (event.getEventType()) {
            case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED:
                onWindowStateChanged(event);
                break;
            case AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED:
                if (mInPicker) {
                    onPickerContentChanged(event);
                }
                break;
            case AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED:
                if (mInPicker) {
                    onPickerTextChanged(event);
                } else if (!mIsRetriggering) {
                    onChatTextChanged(event);
                }
                break;
        }
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "服务被中断");
    }

    @Override
    public void onServiceChanged() {
        AccessibilityServiceInfo info = getServiceInfo();
        Log.d(TAG, "服务配置: " +
                "eventTypes=" + info.eventTypes +
                ", feedbackType=" + info.feedbackType +
                ", flags=" + info.flags);
    }

    // ============================================================
    // 核心：窗口状态变化
    // ============================================================

    private void onWindowStateChanged(AccessibilityEvent event) {
        String className = event.getClassName() != null ? event.getClassName().toString() : "";
        if (className.isEmpty()) return;

        boolean isPicker = isContactPicker(className);
        boolean isChat = isChatScreen(className);

        // === 检测：进入选择器 ===
        if (isPicker && !mInPicker) {
            mInPicker = true;
            mSavedSearchText = "";
            mChatTextLenBefore = getChatInputLength();
            Log.d(TAG, "📋 @选择器打开，当前输入框字数: " + mChatTextLenBefore);
        }

        // === 检测：离开选择器（可能是选完了人，也可能是取消了） ===
        if (!isPicker && mInPicker) {
            mWasInPicker = true;
            mInPicker = false;
            Log.d(TAG, "📋 @选择器关闭，记录搜索词: [" + mSavedSearchText + "]");

            // 延迟检查是否选中了联系人
            mHandler.removeCallbacks(mCheckSelectionRunnable);
            mHandler.postDelayed(mCheckSelectionRunnable, 400);
        }

        // 回到聊天界面时的重新检测
        if (isChat && mWasInPicker) {
            // 在某些情况下，selection checker可能没触发，这里作为后备
            mHandler.removeCallbacks(mCheckSelectionRunnable);
            mHandler.postDelayed(mCheckSelectionRunnable, 600);
        }
    }

    // ============================================================
    // 核心：监控选择器搜索框
    // ============================================================

    private void onPickerContentChanged(AccessibilityEvent event) {
        // 内容变化时看看能不能找到搜索框
        if (mSavedSearchText.isEmpty()) {
            findAndSaveSearchText();
        }
    }

    private void onPickerTextChanged(AccessibilityEvent event) {
        // 文本变化时更新搜索词
        AccessibilityNodeInfo source = event.getSource();
        if (source != null && source.isEditable()) {
            CharSequence text = source.getText();
            if (text != null && text.length() > 0) {
                mSavedSearchText = text.toString();
                Log.d(TAG, "搜索词更新: [" + mSavedSearchText + "]");
            }
        }
    }

    /**
     * 在选择器界面中找到搜索框并记录搜索词
     */
    private void findAndSaveSearchText() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        // 在微信的选择器中，搜索框通常是第一个可编辑的EditText
        List<AccessibilityNodeInfo> editTexts = root.findAccessibilityNodeInfosByViewId(
                "com.tencent.mm:id/editor");
        if (editTexts == null || editTexts.isEmpty()) {
            // 尝试其他可能的ID
            editTexts = root.findAccessibilityNodeInfosByViewId(
                    "com.tencent.mm:id/search");
        }
        if (editTexts == null || editTexts.isEmpty()) {
            // 递归查找任何EditText
            AccessibilityNodeInfo edit = findFirstEditText(root);
            if (edit != null) {
                CharSequence t = edit.getText();
                if (t != null && t.length() > 0) {
                    mSavedSearchText = t.toString();
                    Log.d(TAG, "从搜索框读到词: [" + mSavedSearchText + "]");
                }
                edit.recycle();
            }
        } else {
            AccessibilityNodeInfo edit = editTexts.get(0);
            CharSequence t = edit.getText();
            if (t != null && t.length() > 0) {
                mSavedSearchText = t.toString();
                Log.d(TAG, "从搜索框读到词: [" + mSavedSearchText + "]");
            }
            edit.recycle();
        }
        root.recycle();
    }

    // ============================================================
    // 核心：监控聊天输入框 - 检测@被插入
    // ============================================================

    private void onChatTextChanged(AccessibilityEvent event) {
        if (!mWasInPicker) return;

        AccessibilityNodeInfo source = event.getSource();
        if (source == null || !source.isEditable()) return;

        CharSequence text = source.getText();
        if (text == null) return;

        String currentText = text.toString();
        int currentLen = currentText.length();

        // 如果输入框变长了，说明可能插入了@mention
        if (mChatTextLenBefore >= 0 && currentLen > mChatTextLenBefore) {
            String inserted = currentText.substring(Math.min(mChatTextLenBefore, currentLen));
            Log.d(TAG, "输入框新增内容: [" + inserted + "]");

            // 检测是否包含@（说明联系人被插入了）
            if (inserted.contains("@")) {
                Log.d(TAG, "✅ 检测到@被插入，准备触发下一轮搜索");
                mHandler.removeCallbacks(mRetriggerRunnable);
                mHandler.postDelayed(mRetriggerRunnable, 300);
            }
        }
    }

    // ============================================================
    // 核心：检测是否选中了联系人
    // ============================================================

    private final Runnable mCheckSelectionRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mEnabled) return;

            // 检测当前是否在微信中（选择器关闭后应当回到聊天界面）
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                CharSequence pkg = root.getPackageName();
                boolean inWeChat = pkg != null && WECHAT_PKG.equals(pkg.toString());
                root.recycle();

                if (inWeChat && mWasInPicker) {
                    mWasInPicker = false;
                    Log.d(TAG, "回到微信界面，准备重触发");

                    // 立即触发下一轮 @ + 搜索词
                    mHandler.removeCallbacks(mRetriggerRunnable);
                    mHandler.postDelayed(mRetriggerRunnable, 400);
                }
            }

            mWasInPicker = false;
        }
    };

    // ============================================================
    // 核心：自动重新触发 @ + 搜索词
    // ============================================================

    private final Runnable mRetriggerRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mEnabled) return;
            doRetrigger();
        }
    };

    /**
     * 重新触发 @ + 记忆的搜索词
     *
     * 流程：
     * 1. 找到聊天输入框
     * 2. 用剪贴板粘贴 @ + 搜索词
     * 3. 微信检测到@ → 打开选择器 → 搜索词自动填入
     * 4. 用户继续选人
     */
    private void doRetrigger() {
        if (mIsRetriggering) return;
        mIsRetriggering = true;

        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) {
                mIsRetriggering = false;
                return;
            }

            AccessibilityNodeInfo input = findChatInput(root);
            if (input == null) {
                Log.d(TAG, "找不到聊天输入框");
                root.recycle();
                mIsRetriggering = false;
                return;
            }

            // 要注入的内容
            String injectText = "@" + mSavedSearchText;
            Log.d(TAG, "🔁 自动注入: [" + injectText + "]");

            // === 方案A：剪贴板粘贴 ===
            boolean success = pasteText(input, injectText);

            if (!success) {
                // === 方案B：直接setText ===
                success = appendText(input, injectText);
            }

            if (success) {
                showToast("🔄 已续接 @" + mSavedSearchText + "，继续选人");
            } else {
                Log.d(TAG, "重触发失败");
                showToast("自动续接失败，请手动输入@");
            }

            input.recycle();
            root.recycle();

        } catch (Exception e) {
            Log.e(TAG, "重触发异常: " + e.getMessage());
        }

        // 延迟重置状态，等微信处理完
        mHandler.postDelayed(() -> {
            mIsRetriggering = false;
            mChatTextLenBefore = getChatInputLength();
        }, 1000);
    }

    /**
     * 剪贴板粘贴方案
     */
    private boolean pasteText(AccessibilityNodeInfo input, String text) {
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            ClipData saved = clipboard.getPrimaryClip();
            ClipData inject = ClipData.newPlainText("multiat", text);
            clipboard.setPrimaryClip(inject);

            // 聚焦 + 移到末尾
            if (input.getText() != null) {
                Bundle args = new Bundle();
                int len = input.getText().length();
                args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, len);
                args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, len);
                input.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args);
            }

            input.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            boolean result = input.performAction(AccessibilityNodeInfo.ACTION_PASTE);

            // 1秒后恢复剪贴板
            final ClipData savedClip = saved;
            mHandler.postDelayed(() -> {
                try {
                    if (savedClip != null) clipboard.setPrimaryClip(savedClip);
                } catch (Exception ignored) {}
            }, 1500);

            return result;

        } catch (SecurityException e) {
            Log.e(TAG, "剪贴板权限不足: " + e.getMessage());
            return false;
        } catch (Exception e) {
            Log.e(TAG, "粘贴失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 直接追加文本（备用方案）
     */
    private boolean appendText(AccessibilityNodeInfo input, String text) {
        try {
            CharSequence current = input.getText();
            String newText = (current != null ? current.toString() : "") + text;
            Bundle args = new Bundle();
            args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    newText);
            return input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        } catch (Exception e) {
            Log.e(TAG, "appendText失败: " + e.getMessage());
            return false;
        }
    }

    // ============================================================
    // 工具方法
    // ============================================================

    private boolean isContactPicker(String className) {
        if (className == null) return false;
        String lower = className.toLowerCase();
        // @选择器的类名特征
        return (lower.contains("contact") || lower.contains("select"))
                && (lower.contains(".ui.") || lower.contains("com.tencent.mm"))
                && !lower.contains("chattingui")
                && !lower.contains("launcherui");
    }

    private boolean isChatScreen(String className) {
        if (className == null) return false;
        for (String pattern : CHAT_CLASSES) {
            if (className.contains(pattern)) return true;
        }
        return false;
    }

    private int getChatInputLength() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return -1;
            AccessibilityNodeInfo input = findChatInput(root);
            if (input != null) {
                CharSequence t = input.getText();
                int len = t != null ? t.length() : -1;
                input.recycle();
                root.recycle();
                return len;
            }
            root.recycle();
        } catch (Exception ignored) {}
        return -1;
    }

    private AccessibilityNodeInfo findChatInput(AccessibilityNodeInfo root) {
        if (root == null) return null;

        // 用常用ID找
        String[] ids = {"editor", "chat_edit_text", "input", "et_input",
                "chatting_input_edit_text"};
        for (String id : ids) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(
                    WECHAT_PKG + ":id/" + id);
            if (nodes != null && !nodes.isEmpty()) {
                for (AccessibilityNodeInfo node : nodes) {
                    if (node.isEnabled() && node.isVisibleToUser()) return node;
                }
            }
        }

        // 递归找EditText
        return findEditText(root);
    }

    private AccessibilityNodeInfo findEditText(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isEditable() && node.isEnabled() && node.isVisibleToUser()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            AccessibilityNodeInfo result = findEditText(child);
            if (child != null && child != result) child.recycle();
            if (result != null) return result;
        }
        return null;
    }

    private AccessibilityNodeInfo findFirstEditText(AccessibilityNodeInfo root) {
        if (root == null) return null;
        if ("android.widget.EditText".equals(root.getClassName() != null ?
                root.getClassName().toString() : "")
                && root.isVisibleToUser()) {
            return root;
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            AccessibilityNodeInfo result = findFirstEditText(child);
            if (child != null && child != result) child.recycle();
            if (result != null) return result;
        }
        return null;
    }

    private void showToast(String msg) {
        mHandler.post(() ->
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    // ============================================================
    // 开关控制
    // ============================================================

    public void toggleEnabled() {
        setEnabled(!mEnabled);
    }

    public void setEnabled(boolean enabled) {
        mEnabled = enabled;
        if (!mEnabled) {
            mInPicker = false;
            mWasInPicker = false;
            mIsRetriggering = false;
            mHandler.removeCallbacksAndMessages(null);
        }
        updateNotification();
        showToast("Multi-@ " + (mEnabled ? "已开启 🟢" : "已关闭 🔴"));
        Log.d(TAG, "Multi-@ " + (mEnabled ? "开启" : "关闭"));
    }

    public boolean isEnabled() {
        return mEnabled;
    }

    // ============================================================
    // 状态栏通知
    // ============================================================

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "海鸥 Multi-@",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("微信群聊 @ 多选增强后台服务");
            channel.setShowBadge(false);
            mNotifyManager.createNotificationChannel(channel);
        }
    }

    private void updateNotification() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        String title = mEnabled ? "🟢 Multi-@ 运行中" : "🔴 Multi-@ 已停止";
        String content = mEnabled
                ? "选人后自动续接 @ 搜索词"
                : "点此开启多选模式";

        // 开关动作 - 直接启动服务传递命令
        Intent toggleIntent = new Intent(this, MultiAtService.class);
        toggleIntent.setAction(ACTION_TOGGLE);
        PendingIntent togglePending = PendingIntent.getService(
                this, 0, toggleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // 点击通知打开App
        Intent appIntent = new Intent(this, MainActivity.class);
        PendingIntent appPending = PendingIntent.getActivity(
                this, 0, appIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(R.drawable.ic_notification)
                .setOngoing(true)
                .setContentIntent(appPending)
                .addAction(0, mEnabled ? "关闭" : "开启", togglePending)
                .build();

        mNotifyManager.notify(NOTIFY_ID, notification);
    }

    // 通知接收器结束
}
