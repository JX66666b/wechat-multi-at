package com.seagull.multiat;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.List;

/**
 * 海鸥 WeChat Multi-@ 无障碍服务
 *
 * 核心原理：
 * 微信的 @ 选择器每次选完人就关闭，要@多个人就得反复输入@重新打开选择器。
 * 这服务就是帮你自动干这个事的：
 *
 * 1. 检测到你在微信群里@了一个人（通过监控输入框变化）
 * 2. 自动帮你再打一个@符号，重新打开选择器
 * 3. 你可以继续搜索和选择下一个人
 * 4. 选完了点"停止"，服务停止自动触发
 *
 * 非root，无侵入，纯无障碍API操作。
 *
 * @author 海鸥
 */
public class MultiAtService extends AccessibilityService {

    private static final String TAG = "海鸥MultiAt";
    private static final String WECHAT_PKG = "com.tencent.mm";

    // 微信聊天界面的Activity类名特征
    private static final String[] CHAT_ACTIVITY_PATTERNS = {
            "ChattingUI", "LauncherUI", ".ui.chatting."
    };

    private boolean mIsActive = false;          // 多选模式是否开启
    private int mLastTextLength = -1;           // 上次截获的文本长度
    private int mCurrentAtCount = 0;            // 当前 @ 个数
    private boolean mIsWeChatFocused = false;    // 微信是否在前台
    private boolean mIsInChatScreen = false;     // 是否在聊天界面

    private Handler mHandler = new Handler(Looper.getMainLooper());
    private OverlayService mOverlayService;
    private boolean mOverlayBound = false;

    // 服务连接
    private ServiceConnection mOverlayConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            OverlayService.LocalBinder binder = (OverlayService.LocalBinder) service;
            mOverlayService = binder.getService();
            mOverlayService.setMultiAtService(MultiAtService.this);
            mOverlayBound = true;
            Log.d(TAG, "悬浮窗服务已绑定");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mOverlayBound = false;
            Log.d(TAG, "悬浮窗服务已断开");
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "海鸥 Multi-@ 服务启动！操他妈的微信单选择器！");

        // 启动悬浮窗服务
        Intent overlayIntent = new Intent(this, OverlayService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(overlayIntent);
        } else {
            startService(overlayIntent);
        }
        bindService(overlayIntent, mOverlayConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onDestroy() {
        if (mOverlayBound) {
            unbindService(mOverlayConnection);
            mOverlayBound = false;
        }
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getPackageName() == null) return;
        if (!WECHAT_PKG.equals(event.getPackageName().toString())) return;

        switch (event.getEventType()) {
            case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED:
                handleWindowChange(event);
                break;

            case AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED:
                // 内容变化时检查是否需要重新找输入框
                if (mIsActive && mIsInChatScreen) {
                    checkAndMonitorEditText();
                }
                break;

            case AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED:
                // 输入框内容变化 - 检测@插入
                if (mIsActive && mIsInChatScreen) {
                    handleTextChanged(event);
                }
                break;

            case AccessibilityEvent.TYPE_VIEW_CLICKED:
                // 点击事件 - 检测是否点了发送、返回等
                if (mIsActive) {
                    handleViewClicked(event);
                }
                break;
        }
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "服务被中断");
    }

    // ======================== 窗口变化检测 ========================

    private void handleWindowChange(AccessibilityEvent event) {
        String className = event.getClassName() != null ? event.getClassName().toString() : "";

        // 检测是否在微信中
        if (className.isEmpty()) {
            mIsWeChatFocused = false;
            mIsInChatScreen = false;
            return;
        }

        mIsWeChatFocused = true;

        // 检测是否在聊天界面
        boolean isChat = false;
        for (String pattern : CHAT_ACTIVITY_PATTERNS) {
            if (className.contains(pattern)) {
                isChat = true;
                break;
            }
        }

        if (isChat) {
            if (!mIsInChatScreen) {
                Log.d(TAG, "进入聊天界面: " + className);
            }
            mIsInChatScreen = true;

            // 如果多选模式开启，立即开始监控输入框
            if (mIsActive) {
                mHandler.postDelayed(this::checkAndMonitorEditText, 500);
            }
        } else {
            mIsInChatScreen = false;
        }

        // 检测@选择器是否打开（用于更新UI状态）
        boolean isPicker = className.toLowerCase().contains("contact")
                || className.contains("SelectContact")
                || className.contains("select_contact");

        if (isPicker && mOverlayBound && mOverlayService != null && mIsActive) {
            mOverlayService.showPickerDetected();
        }
    }

    // ======================== 文本变化检测 - 核心逻辑 ========================

    private int mPreviousTextLength = -1;
    private int mPreviousAtCount = -1;
    private long mLastSelectionTime = 0;

    private void handleTextChanged(AccessibilityEvent event) {
        if (!mIsActive) return;

        AccessibilityNodeInfo source = event.getSource();
        if (source == null) return;
        if (!source.isFocused()) return;  // 只处理当前聚焦的输入框

        // 获取当前文本
        CharSequence currentText = source.getText();
        if (currentText == null) return;

        String text = currentText.toString();
        int currentLength = text.length();

        // 首次初始化
        if (mPreviousTextLength < 0) {
            mPreviousTextLength = currentLength;
            mPreviousAtCount = countAtSymbols(text);
            return;
        }

        // 检测是否是@mention插入
        // 特征: 文本变长了，并且@符号数量增加了，之前有个@符号被消耗
        int atCount = countAtSymbols(text);

        Log.d(TAG, String.format("文本变化: len=%d (was %d), @=%d (was %d), 内容=[%s]",
                currentLength, mPreviousTextLength, atCount, mPreviousAtCount,
                text.length() > 50 ? text.substring(0, 50) + "..." : text));

        // 关键判断: 是否用户刚刚选了一个联系人
        // 微信@mention的流程: 用户输入@ → 选择器打开 → 选中联系人 → "@张三 "被插入
        // 所以特征是: 文本长度增加超过1，且@的数量增加了
        // 但也可能用户手动删了@然后重新输入
        if (currentLength > mPreviousTextLength && atCount >= mPreviousAtCount) {
            // 用户做了选择或输入了新的@
            // 用防抖，避免短时间内重复触发
            long now = System.currentTimeMillis();
            if (now - mLastSelectionTime > 400) {
                // 检测是否有新的@mention被插入 (文本变长超过@符号本身)
                int lengthDiff = currentLength - mPreviousTextLength;
                int atDiff = atCount - mPreviousAtCount;

                if (lengthDiff > 2 || atDiff > 0) {
                    Log.d(TAG, "检测到@插入或文本变化，准备自动触发下一个@");
                    mLastSelectionTime = now;
                    mCurrentAtCount = atCount;

                    // 延迟一下等微信处理完，再触发下一个@
                    mHandler.removeCallbacks(mRetriggerRunnable);
                    mHandler.postDelayed(mRetriggerRunnable, 350);

                    // 更新悬浮窗
                    if (mOverlayBound && mOverlayService != null) {
                        mOverlayService.updateAtCount(atCount);
                    }
                }
            }
        } else if (currentLength < mPreviousTextLength) {
            // 文本缩短了（用户删除了内容）
            Log.d(TAG, "文本缩短，可能删除了@");
        }

        mPreviousTextLength = currentLength;
        mPreviousAtCount = atCount;
    }

    private final Runnable mRetriggerRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mIsActive || !mIsInChatScreen) return;
            retriggerAt();
        }
    };

    // ======================== 自动触发 @ 符号 - 关键操作 ========================

    /**
     * 自动触发@符号，重新打开联系人选择器
     * 这是整个功能的核心操作
     *
     * 方案：通过剪贴板实现
     * 1. 保存当前剪贴板内容
     * 2. 将"@"写入剪贴板
     * 3. 在输入框执行粘贴操作
     * 4. 恢复剪贴板
     */
    private void retriggerAt() {
        if (!mIsActive) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            Log.d(TAG, "无法获取窗口根节点");
            return;
        }

        AccessibilityNodeInfo editText = findChatInput(root);

        if (editText == null) {
            Log.d(TAG, "找不到聊天输入框");
            root.recycle();
            return;
        }

        Log.d(TAG, "找到输入框，准备粘贴@符号");
        CharSequence currentText = editText.getText();

        // 输入框里已经有内容了，确认末尾没有@我们就加一个
        if (currentText != null) {
            String text = currentText.toString();
            if (text.endsWith("@") || text.endsWith("@ ") || text.endsWith("​")) {
                Log.d(TAG, "末尾已经有@，跳过触发");
                root.recycle();
                return;
            }
        }

        // === 方案1: 剪贴板 + 粘贴（最通用） ===
        boolean success = pasteAtSymbol(editText);

        // === 方案2: 如果粘贴失败，用setText（备选） ===
        if (!success) {
            success = setTextFallback(editText);
        }

        if (success) {
            Log.d(TAG, "✅ @符号已触发");
            if (mOverlayBound && mOverlayService != null) {
                mOverlayService.showToast("已自动触发@，请选择下一个人");
            }
        } else {
            Log.d(TAG, "❌ 触发@失败");
            if (mOverlayBound && mOverlayService != null) {
                mOverlayService.showToast("自动触发失败，请手动输入@");
            }
        }

        editText.recycle();
        root.recycle();
    }

    /**
     * 方案1: 通过剪贴板粘贴@符号
     */
    private boolean pasteAtSymbol(AccessibilityNodeInfo editText) {
        try {
            // 保存当前剪贴板
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            ClipData savedClip = null;
            try {
                savedClip = clipboard.getPrimaryClip();
            } catch (Exception ignored) {}
            final ClipData clipToRestore = savedClip;

            // 设置@"到剪贴板
            ClipData atClip = ClipData.newPlainText("at", "@");
            clipboard.setPrimaryClip(atClip);

            // 确保输入框有焦点
            editText.performAction(AccessibilityNodeInfo.ACTION_FOCUS);

            // 尝试移动到文本末尾
            // 对于EditText，ACTION_SET_SELECTION可以设置光标位置
            if (editText.getText() != null) {
                Bundle selArgs = new Bundle();
                int len = editText.getText().length();
                selArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, len);
                selArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, len);
                editText.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs);
            }

            // 执行粘贴
            boolean pasted = editText.performAction(AccessibilityNodeInfo.ACTION_PASTE);

            // 恢复剪贴板
            mHandler.postDelayed(() -> {
                try {
                    if (clipToRestore != null) {
                        clipboard.setPrimaryClip(clipToRestore);
                    }
                } catch (Exception ignored) {}
            }, 1000);

            return pasted;

        } catch (SecurityException e) {
            Log.e(TAG, "剪贴板权限不足: " + e.getMessage());
            return false;
        } catch (Exception e) {
            Log.e(TAG, "粘贴失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 方案2: 直接设置文本（备用方案）
     * 某些ROM可能不允许程序化粘贴，这时直接用setText
     */
    private boolean setTextFallback(AccessibilityNodeInfo editText) {
        try {
            CharSequence currentText = editText.getText();
            String newText = (currentText != null ? currentText.toString() : "") + "@";

            Bundle args = new Bundle();
            args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    newText
            );
            return editText.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        } catch (Exception e) {
            Log.e(TAG, "setText备选方案失败: " + e.getMessage());
            return false;
        }
    }

    // ======================== UI交互检测 ========================

    private void handleViewClicked(AccessibilityEvent event) {
        // 检测用户是否点击了发送按钮（停止跟踪）
        if (mIsActive) {
            CharSequence contentDesc = event.getContentDescription();
            CharSequence className = event.getClassName();

            // 检测发送按钮点击
            if (className != null && className.toString().contains("Button")) {
                // 发送按钮被点击，重置状态
                Log.d(TAG, "检测到按钮点击，可能发送了消息");
                mHandler.postDelayed(() -> {
                    mPreviousTextLength = -1;
                    mPreviousAtCount = -1;
                }, 1000);
            }
        }
    }

    // ======================== 工具方法 ========================

    /**
     * 统计文本中@符号的个数
     */
    private int countAtSymbols(String text) {
        if (text == null || text.isEmpty()) return 0;
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf('@', idx)) != -1) {
            count++;
            idx++;
        }
        return count;
    }

    /**
     * 找到聊天输入框
     * 在微信的聊天界面中找到可编辑的文本输入框
     */
    private AccessibilityNodeInfo findChatInput(AccessibilityNodeInfo root) {
        if (root == null) return null;

        // 方法1: 找有焦点的EditText
        List<AccessibilityNodeInfo> editTexts = root.findAccessibilityNodeInfosByViewId(
                "com.tencent.mm:id/" + getEditTextIdForVersion()
        );

        if (editTexts != null && !editTexts.isEmpty()) {
            for (AccessibilityNodeInfo node : editTexts) {
                if (node.isEnabled() && node.isVisibleToUser()) {
                    return node;
                }
            }
        }

        // 方法2: 按节点类名找EditText
        List<AccessibilityNodeInfo> textNodes = root.findAccessibilityNodeInfosByText("");
        if (textNodes != null) {
            for (AccessibilityNodeInfo node : textNodes) {
                if (node.isEditable() && node.isEnabled() && node.isVisibleToUser()) {
                    return node;
                }
            }
        }

        // 方法3: 递归遍历查找
        return findEditTextRecursive(root);
    }

    private AccessibilityNodeInfo findEditTextRecursive(AccessibilityNodeInfo node) {
        if (node == null) return null;

        if (node.isEditable() && node.isEnabled() && node.isVisibleToUser()
                && "android.widget.EditText".equals(node.getClassName().toString())) {
            return node;
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            AccessibilityNodeInfo result = findEditTextRecursive(child);
            if (result != null) {
                if (node != child) {
                    // Don't recycle parent
                }
                return result;
            }
            if (child != null) {
                child.recycle();
            }
        }

        return null;
    }

    /**
     * 微信输入框的ID在不同版本可能不同
     * 常见的有: "editor", "chat_edit_text", "input", "et_input"
     */
    private String getEditTextIdForVersion() {
        // 尝试多个可能的ID
        String[] possibleIds = {
                "editor",
                "chat_edit_text",
                "input",
                "et_input",
                "chatting_input_edit_text",
                "message_edit_text"
        };

        // 可以用SharedPreferences缓存已经找到的ID
        SharedPreferences prefs = getSharedPreferences("multiat", MODE_PRIVATE);
        String cachedId = prefs.getString("edit_text_id", null);
        if (cachedId != null) return cachedId;

        return possibleIds[0]; // 默认第一个
    }

    private void checkAndMonitorEditText() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        AccessibilityNodeInfo editText = findChatInput(root);
        if (editText != null) {
            CharSequence text = editText.getText();
            if (text != null) {
                mPreviousTextLength = text.length();
                mPreviousAtCount = countAtSymbols(text.toString());
                Log.d(TAG, "初始化输入框状态: len=" + mPreviousTextLength
                        + ", @count=" + mPreviousAtCount);
            }
            editText.recycle();
        }
        root.recycle();
    }

    // ======================== 对外接口（被悬浮窗调用） ========================

    /**
     * 开启多选模式
     */
    public void startMultiAt() {
        mIsActive = true;
        mPreviousTextLength = -1;
        mPreviousAtCount = -1;
        mLastSelectionTime = 0;

        Log.d(TAG, "🚀 多选模式已开启！");

        // 立即检查当前输入框状态
        mHandler.postDelayed(this::checkAndMonitorEditText, 300);

        // 如果在聊天界面，触发第一个@（如果输入框为空）
        // 用户需要先自己输入@选第一个人
        Toast.makeText(this, "海鸥 Multi-@ 已开启，输入@选择联系人后自动续接", Toast.LENGTH_LONG).show();
    }

    /**
     * 停止多选模式
     */
    public void stopMultiAt() {
        mIsActive = false;
        mHandler.removeCallbacks(mRetriggerRunnable);
        mCurrentAtCount = 0;
        mPreviousTextLength = -1;
        mPreviousAtCount = -1;

        Log.d(TAG, "⏹️ 多选模式已关闭");
        Toast.makeText(this, "Multi-@ 已关闭", Toast.LENGTH_SHORT).show();

        if (mOverlayBound && mOverlayService != null) {
            mOverlayService.reset();
        }
    }

    public boolean isActive() {
        return mIsActive;
    }

    public boolean isInChatScreen() {
        return mIsInChatScreen;
    }

    public int getCurrentAtCount() {
        return mCurrentAtCount;
    }
}
