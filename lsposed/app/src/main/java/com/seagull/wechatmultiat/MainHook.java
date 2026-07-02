package com.seagull.wechatmultiat;

import com.seagull.wechatmultiat.hooks.ContactPickerHook;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {
    private static final String WECHAT_PACKAGE = "com.tencent.mm";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!lpparam.packageName.equals(WECHAT_PACKAGE)) return;
        ContactPickerHook.init(lpparam.classLoader);
    }
}
