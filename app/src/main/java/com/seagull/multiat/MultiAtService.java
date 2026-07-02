package com.seagull.multiat;

import android.accessibilityservice.AccessibilityService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 海鸥 Multi-@ v2 - 彻底重写
 *
 * == 原理 ==
 * 微信 @ 选择器每次选人就关闭，这个服务自动续接。
 *
 * 检测方式：靠数输入框里的@符号数量 + 检测选择器是否打开
 * 注入方式：用 SET_TEXT 直接操作输入框（兼容性比粘贴好）
 *
 * 流程：
 * 1. 用户在输入框打 @ → 检测到 → 标记选择器可能开了
 * 2. 选择器开了 → 找到搜索框 → 记下搜索词
 * 3. 选择器关了 → 重新填入 @ + 搜索词
 * 4. 用户继续选人... 循环
 *
 * @author 海鸥
 */
public class MultiAtService extends AccessibilityService {

    private static final String TAG = "海鸥MA";
    private static final String WECHAT_PKG = "com.tencent.mm";
    private static final String CHANNEL_ID = "ma_status";
    private static final int NOTIFY_ID = 1001;

    // 微信输入框可能的ID
    private static final String[] INPUT_IDS = {
            "com.tencent.mm:id/editor",
            "com.tencent.mm:id/chat_edit_text",
            "com.tencent.mm:id/input",
            "com.tencent.mm:id/et_input",
            "com.tencent.mm:id/chatting_input_edit_text"
    };

    // 选择器搜索框可能的ID
    private static final String[] SEARCH_IDS = {
            "com.tencent.mm:id/editor",
            "com.tencent.mm:id/search",
            "com.tencent.mm:id/search_src_text",
            "com.tencent.mm:id/search_view",
            "com.tencent.mm:id/filter_edit"
    };

    private boolean mActive = false;
    private String mSearchKeyword = "";
    private boolean mPickerOpened = false;
    private int mLastAtCount = 0;

    // 防递归锁
    private boolean mLocked = false;
    private long mLockUntil = 0;

    private Handler mHandler = new Handler(Looper.getMainLooper());
    private NotificationManager mNotifyManager;
    private StringBuilder mLog = new StringBuilder();

    private Runnable mSearchScanner = new Runnable() {
        @Override
        public void run() {
            if (!mActive || !mPickerOpened) return;
            scanSearchKeyword();
            mHandler.postDelayed(this, 400);
        }
    };

    private Runnable mRetrigger = new Runnable() {
        @Override
        public void run() {
            if (!mActive) return;
            doRetrigger();
        }
    };

    // ============================================================
    @Override
    public void onCreate() {
        super.onCreate();
        mNotifyManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createNotificationChannel();
        mActive = true;
        log("服务启动");
        updateNotify("等待微信输入...");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "toggle".equals(intent.getAction())) {
            mActive = !mActive;
            if (!mActive) {
                mHandler.removeCallbacks(mSearchScanner);
                mHandler.removeCallbacks(mRetrigger);
                mPickerOpened = false;
                mLog = new StringBuilder();
            }
            updateNotify(mActive ? "运行中" : "已暂停");
            toast("Multi-@ " + (mActive ? "开启" : "关闭"));
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
        if (!mActive) return;
        String pkg = event.getPackageName() != null ? event.getPackageName().toString() : "";
        if (!WECHAT_PKG.equals(pkg)) return;

        switch (event.getEventType()) {
            case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED:
                onWindowChanged();
                break;
            case AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED:
                onContentChanged();
                break;
        }
    }

    @Override
    public void onInterrupt() {}

    // ============================================================
    // 窗口切换 → 选择器打开/关闭
    // ============================================================

    private void onWindowChanged() {
        if (isLocked()) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        boolean pickerOpen = hasSearchBox(root);
        boolean hadPicker = mPickerOpened;
        mPickerOpened = pickerOpen;

        // 选择器刚打开
        if (pickerOpen && !hadPicker) {
            mSearchKeyword = "";
            log("选择器打开");
            updateNotify("选择器中 @" + mLastAtCount);
            mHandler.removeCallbacks(mSearchScanner);
            mHandler.postDelayed(mSearchScanner, 600);
        }
        // 选择器刚关闭 → 有人被选了
        else if (!pickerOpen && hadPicker) {
            mHandler.removeCallbacks(mSearchScanner);
            log("选择器关闭 关键词:[" + mSearchKeyword + "]");
            updateNotify("已选 @" + mLastAtCount + " 续接中...");
            mHandler.removeCallbacks(mRetrigger);
            mHandler.postDelayed(mRetrigger, 500);
        }

        root.recycle();
    }

    // ============================================================
    // 内容变化 → 检查输入框状态
    // ============================================================

    private long mLastContentCheck = 0;

    private void onContentChanged() {
        if (isLocked()) return;
        long now = System.currentTimeMillis();
        if (now - mLastContentCheck < 250) return;
        mLastContentCheck = now;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        AccessibilityNodeInfo input = findChatInput(root);
        if (input == null || input.getText() == null) {
            if (input != null) input.recycle();
            root.recycle();
            return;
        }

        String text = input.getText().toString();
        int atCount = countAt(text);

        // 检测 @ 被插入（用户选了人）
        if (atCount > mLastAtCount && mPickerOpened) {
            log("@数: " + mLastAtCount + "->" + atCount);
            mPickerOpened = false;
            mHandler.removeCallbacks(mSearchScanner);
            mHandler.removeCallbacks(mRetrigger);
            mHandler.postDelayed(mRetrigger, 500);
        }

        mLastAtCount = atCount;
        input.recycle();
        root.recycle();
    }

    // ============================================================
    // 检测选择器搜索框
    // ============================================================

    private boolean hasSearchBox(AccessibilityNodeInfo root) {
        if (root == null) return false;
        for (String id : SEARCH_IDS) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
            if (nodes != null && !nodes.isEmpty()) {
                for (AccessibilityNodeInfo n : nodes) {
                    if (n.isVisibleToUser() && n.isEnabled()) {
                        // 确保这不是聊天输入框
                        String vid = n.getViewIdResourceName() != null
                                ? n.getViewIdResourceName() : "";
                        boolean isChat = vid.equals("com.tencent.mm:id/editor")
                                && mPickerOpened; // 第一次检测不能靠这个
                        if (!isChat) {
                            return true;
                        }
                    }
                    n.recycle();
                }
            }
        }
        return false;
    }

    private void scanSearchKeyword() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;

            for (String id : SEARCH_IDS) {
                List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
                if (nodes != null && !nodes.isEmpty()) {
                    AccessibilityNodeInfo search = nodes.get(0);
                    if (search.isVisibleToUser()) {
                        CharSequence t = search.getText();
                        if (t != null && t.length() > 0) {
                            String kw = t.toString();
                            if (!kw.equals(mSearchKeyword)) {
                                mSearchKeyword = kw;
                                log("搜索词:[" + mSearchKeyword + "]");
                                updateNotify("@" + mLastAtCount + " 搜:" + mSearchKeyword);
                            }
                        }
                    }
                    search.recycle();
                    break;
                }
            }
            root.recycle();
        } catch (Exception e) {
            log("扫描异常: " + e.getMessage());
        }
    }

    // ============================================================
    // 注入 @ + 搜索词
    // ============================================================

    private void doRetrigger() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) { log("重触发: 无根节点"); return; }

        AccessibilityNodeInfo input = findChatInput(root);
        if (input == null) {
            log("重触发: 找不到输入框");
            root.recycle();
            return;
        }

        CharSequence t = input.getText();
        if (t == null) { input.recycle(); root.recycle(); return; }

        String text = t.toString();

        // 末尾已经有@就不重复
        if (text.endsWith("@")) {
            log("末尾已有@，跳过");
            input.recycle();
            root.recycle();
            return;
        }

        // 构造要注入的内容
        String inject = mSearchKeyword.isEmpty() ? text + "@" : text + "@" + mSearchKeyword;
        log("注入: [" + text + "] -> [" + inject + "]");

        // 加锁防递归
        lock(3000);

        Bundle args = new Bundle();
        args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                inject);
        boolean ok = input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);

        if (ok) {
            log("注入成功");
            updateNotify("续接 @" + mSearchKeyword);
            // 等选择器重新打开后继续扫描
            mPickerOpened = true;
            mHandler.removeCallbacks(mSearchScanner);
            mHandler.postDelayed(mSearchScanner, 1000);
        } else {
            log("注入失败!");
            updateNotify("注入失败，手动输入@");
            mPickerOpened = false;
        }

        input.recycle();
        root.recycle();
    }

    // ============================================================
    // 找到聊天输入框
    // ============================================================

    private AccessibilityNodeInfo findChatInput(AccessibilityNodeInfo root) {
        if (root == null) return null;
        for (String id : INPUT_IDS) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
            if (nodes != null && !nodes.isEmpty()) {
                for (AccessibilityNodeInfo n : nodes) {
                    if (n.isEnabled() && n.isVisibleToUser() && n.isEditable()) {
                        return n;
                    }
                    n.recycle();
                }
            }
        }
        // 递归找第一个可编辑的
        return findEditable(root);
    }

    private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isEditable() && node.isEnabled() && node.isVisibleToUser()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            AccessibilityNodeInfo result = findEditable(child);
            if (child != null && child != result) child.recycle();
            if (result != null) return result;
        }
        return null;
    }

    private int countAt(String s) {
        if (s == null || s.isEmpty()) return 0;
        int c = 0, i = 0;
        while ((i = s.indexOf('@', i)) != -1) { c++; i++; }
        return c;
    }

    private boolean isLocked() {
        if (!mLocked) return false;
        if (System.currentTimeMillis() >= mLockUntil) {
            mLocked = false;
            return false;
        }
        return true;
    }

    private void lock(long ms) {
        mLocked = true;
        mLockUntil = System.currentTimeMillis() + ms;
    }

    // ============================================================
    // 通知 & 日志
    // ============================================================

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Multi-@", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("微信群聊 @ 多选增强");
            ch.setShowBadge(false);
            mNotifyManager.createNotificationChannel(ch);
        }
    }

    private void updateNotify(String status) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        Intent toggleIntent = new Intent(this, MultiAtService.class);
        toggleIntent.setAction("toggle");
        PendingIntent togglePending = PendingIntent.getService(
                this, 0, toggleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent appIntent = new Intent(this, MainActivity.class);
        PendingIntent appPending = PendingIntent.getActivity(
                this, 0, appIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String logText = mLog.length() > 500
                ? mLog.substring(mLog.length() - 500)
                : mLog.toString();

        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("海鸥 " + (mActive ? "🟢" : "🔴"))
                .setContentText(status)
                .setStyle(new Notification.BigTextStyle()
                        .bigText("状态: " + status + "\n\n日志:\n" + logText))
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setOngoing(true)
                .setContentIntent(appPending)
                .addAction(0, mActive ? "暂停" : "开启", togglePending)
                .build();
        mNotifyManager.notify(NOTIFY_ID, n);
    }

    private void log(String msg) {
        String time = SimpleDateFormat.getTimeInstance(
                SimpleDateFormat.SHORT, Locale.getDefault()).format(new Date());
        String line = time + " " + msg;
        mLog.append(line).append("\n");
        if (mLog.length() > 2000) mLog.delete(0, mLog.length() - 1500);
        Log.d(TAG, msg);
    }

    private void toast(String msg) {
        mHandler.post(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }
}
