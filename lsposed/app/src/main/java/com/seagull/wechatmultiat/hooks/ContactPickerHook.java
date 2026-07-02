package com.seagull.wechatmultiat.hooks;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 海鸥 Multi-@ LSPosed Hook
 *
 * 直接Hook微信内部的联系人选择器流程：
 * 1. 检测到联系人选择器Activity打开后激活多选
 * 2. 拦截setResult，把选择结果积累起来不关界面
 * 3. 拦截finish，防止选择后自动关闭
 * 4. 用户点返回时把所有选中的联系人一起回传
 */
public class ContactPickerHook {

    private static final String TAG = "[海鸥LSPosed]";
    private static ClassLoader sCL;

    // 状态
    private static Activity sPickerActivity;
    private static final Set<String> sSelectedIds = new LinkedHashSet<>();
    private static final List<String> sSelectedNames = new ArrayList<>();
    private static boolean sActive = false;
    private static boolean sConfirming = false;

    // Intent Extra 常量
    private static final String EXTRA_MULTI_IDS = "multi_at_ids";
    private static final String EXTRA_MULTI_NAMES = "multi_at_names";
    private static final String[] PICKER_KEYS = {
            "select_type", "Select_Conv_Type", "scene",
            "Contact_Select_Type", "ContactSelectType"
    };

    public static void init(ClassLoader classLoader) {
        sCL = classLoader;
        XposedBridge.log(TAG + " 海鸥Multi-@模块加载！");

        hookSetResult();
        hookFinish();
        hookOnCreate();
    }

    /** 检测Activity.onCreate，发现联系人选择器 */
    private static void hookOnCreate() {
        XposedHelpers.findAndHookMethod(Activity.class, "onCreate",
                Bundle.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) {
                        Activity activity = (Activity) param.thisObject;
                        String name = activity.getClass().getName();
                        if (!name.startsWith("com.tencent.mm")) return;

                        Intent intent = activity.getIntent();
                        if (intent == null || intent.getExtras() == null) return;

                        Bundle extras = intent.getExtras();
                        boolean isPicker = false;
                        for (String key : PICKER_KEYS) {
                            if (extras.containsKey(key)) {
                                isPicker = true;
                                XposedBridge.log(TAG + " 选择器: " + name
                                        + " | " + key + "=" + extras.get(key));
                                break;
                            }
                        }

                        if (isPicker) {
                            sPickerActivity = activity;
                            sSelectedIds.clear();
                            sSelectedNames.clear();
                            sActive = true;
                            sConfirming = false;
                            XposedBridge.log(TAG + " 多选模式激活！");

                            activity.runOnUiThread(() ->
                                    Toast.makeText(activity,
                                            "🕊️ 多选模式: 选人后继续搜索，点返回确认",
                                            Toast.LENGTH_LONG).show());
                        }
                    }
                });
    }

    /** 拦截setResult，积累选择，不让Activity关闭 */
    private static void hookSetResult() {
        XposedHelpers.findAndHookMethod(Activity.class, "setResult",
                int.class, Intent.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(XC_MethodHook.MethodHookParam param) {
                        if (!sActive || sConfirming) return;
                        if (param.thisObject != sPickerActivity) return;

                        Intent data = (Intent) param.args[1];
                        if (data == null || data.getExtras() == null) return;

                        // 跳过我们自己发的结果
                        if (data.hasExtra(EXTRA_MULTI_IDS)) return;

                        // 提取联系人信息
                        String id = getExtra(data, "contact_id", "talker",
                                "username", "Contact_User");
                        String name = getExtra(data, "contact_nick", "nickname",
                                "Contact_Nick", "Contact_NickName");

                        if (id == null) return;
                        if (sSelectedIds.contains(id)) return;

                        sSelectedIds.add(id);
                        sSelectedNames.add(name != null ? name : id);

                        XposedBridge.log(TAG + " ✅ 已选: " + name
                                + " (" + sSelectedIds.size() + "人)");

                        // 清空结果，不让Activity关闭
                        param.args[0] = 0;
                        param.args[1] = null;

                        // Toast提示
                        final String toastMsg = "已选 " + sSelectedIds.size() + " 人，继续选或返回确认";
                        sPickerActivity.runOnUiThread(() ->
                                Toast.makeText(sPickerActivity, toastMsg, Toast.LENGTH_SHORT).show());
                    }
                });
    }

    /** 拦截finish，触发确认逻辑 */
    private static void hookFinish() {
        XposedHelpers.findAndHookMethod(Activity.class, "finish",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(XC_MethodHook.MethodHookParam param) {
                        if (!sActive || sConfirming) return;
                        if (param.thisObject != sPickerActivity) return;
                        if (sSelectedIds.isEmpty()) return;

                        // 用户按了返回 → 确认所有选择
                        XposedBridge.log(TAG + " 确认选择: " + sSelectedIds.size() + "人");
                        doConfirm();
                    }
                });
    }

    /** 确认选择，把多选结果回传给聊天界面 */
    private static void doConfirm() {
        sConfirming = true;

        try {
            Intent result = new Intent();

            // 打包多选结果
            ArrayList<String> ids = new ArrayList<>(sSelectedIds);
            ArrayList<String> names = new ArrayList<>(sSelectedNames);

            result.putStringArrayListExtra(EXTRA_MULTI_IDS, ids);
            result.putStringArrayListExtra(EXTRA_MULTI_NAMES, names);
            result.putExtra("is_multi_at", true);

            // 同时保留第一个联系人数据（兼容微信原有逻辑）
            // 微信原本只处理单个联系人，多选增强由hook onActivityResult完成

            sPickerActivity.setResult(Activity.RESULT_OK, result);
            sPickerActivity.finish();

        } catch (Exception e) {
            XposedBridge.log(TAG + " 确认失败: " + e);
        }

        sActive = false;
        sConfirming = false;
    }

    private static String getExtra(Intent data, String... keys) {
        Bundle extras = data.getExtras();
        if (extras == null) return null;
        for (String key : keys) {
            if (extras.containsKey(key)) {
                Object val = extras.get(key);
                return val != null ? val.toString() : null;
            }
        }
        return null;
    }
}
