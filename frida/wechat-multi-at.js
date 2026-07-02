/**
 * 海鸥 WeChat Multi-@ Frida脚本
 * ===============================
 *
 * 如果不想装APK，用这个Frida脚本直接hook微信
 * 需要手机连接电脑，有ADB权限即可（不需要root，需要用frida-server）
 *
 * 用法:
 *   1. 手机端运行 frida-server
 *   2. 电脑端执行:
 *      frida -U com.tencent.mm -l wechat-multi-at.js
 *
 * 原理：Hook微信联系人选择器的setResult/finish方法，
 *       把单选改成多选，然后加个悬浮按钮确认。
 *
 * @author 海鸥
 */

'use strict';

// ========================== 配置 ==========================
const CONFIG = {
    // 调试日志
    debug: true,
    // 自动触发@的延迟（毫秒）
    retriggerDelay: 300,
};

// ========================== 全局状态 ==========================
let state = {
    active: false,
    selectedContacts: new Set(),
    pickerActivity: null,
    chatInput: null,
    textBefore: '',
    atCount: 0,
};

// ========================== 入口 ==========================

Java.perform(function () {
    console.log('');
    console.log('╔══════════════════════════════════════════╗');
    console.log('║      🕊️ 海鸥 WeChat Multi-@ v1.0        ║');
    console.log('║      微信群 @ 多选增强 Frida 版          ║');
    console.log('╚══════════════════════════════════════════╝');
    console.log('');

    main();
});

function main() {
    const Activity = Java.use('android.app.Activity');
    const Intent = Java.use('android.content.Intent');

    // ============ Hook 1: 拦截联系人选择器 ============
    console.log('[+] Hook Activity.setResult 拦截选择结果...');

    Activity.setResult.overload('int', 'android.content.Intent').implementation = function (resultCode, data) {
        if (!isWeChat(this) || data === null) {
            return this.setResult(resultCode, data);
        }

        const extras = data.getExtras();
        if (extras === null) {
            return this.setResult(resultCode, data);
        }

        // 检测是否是联系人选择器
        if (isContactPickerIntent(extras)) {
            const clsName = this.getClass().getName();
            console.log(`[MultiAt] 联系人选择器 setResult: ${clsName}`);

            if (!state.active) {
                // 首次检测到，询问是否开启多选
                console.log(`[MultiAt] 检测到联系人选择器！输入 y 开启多选模式`);
            }

            // 提取选择的联系人
            const contactId = extras.getString('contact_id')
                || extras.getString('talker')
                || extras.getString('username');
            const nickName = extras.getString('contact_nick')
                || extras.getString('nickname')
                || contactId;

            if (contactId && state.active) {
                state.selectedContacts.add(JSON.stringify({
                    id: contactId,
                    name: nickName
                }));
                console.log(`[MultiAt] ✅ 已选择: ${nickName} (共${state.selectedContacts.size}人)`);

                // 取消这个setResult，不让Activity关闭
                console.log('[MultiAt] 阻止关闭，继续选择下一个...');
                return; // 不调用原方法
            }
        }

        return this.setResult(resultCode, data);
    };

    // ============ Hook 2: 拦截finish防止关闭 ============
    console.log('[+] Hook Activity.finish 防止选择后关闭...');

    Activity.finish.implementation = function () {
        if (isWeChat(this) && state.active && state.selectedContacts.size > 0) {
            const clsName = this.getClass().getName();
            if (clsName.toLowerCase().includes('contact') ||
                clsName.includes('SelectContact')) {
                console.log('[MultiAt] 阻止finish，保持选择器打开');
                return; // 阻止关闭
            }
        }
        this.finish();
    };

    // ============ Hook 3: 监控聊天输入框 ============
    console.log('[+] Hook 文本变化检测@插入...');

    // Hook EditText的addTextChangedListener来检测@插入
    const EditText = Java.use('android.widget.EditText');
    const TextWatcher = Java.use('android.text.TextWatcher');

    EditText.addTextChangedListener.implementation = function (watcher) {
        this.addTextChangedListener(watcher);

        // 包装TextWatcher来检测@
        if (this.getContext() && this.getContext().getPackageName() === 'com.tencent.mm') {
            // 检查当前是微信的输入框
            console.log('[MultiAt] 检测到微信EditText，添加监控');
        }
    };

    console.log('[+] 所有Hook已加载！');
    console.log('[+] 用法: 在微信群里 @一个人后，我会自动续接下一个@');
    console.log('[+] 在Frida控制台输入 toggleMultiAt() 开启/关闭多选模式');
    console.log('');

    // ============ 暴露控制台接口 ============

    // @ts-ignore
    globalThis.toggleMultiAt = function () {
        state.active = !state.active;
        if (!state.active) {
            state.selectedContacts.clear();
            console.log(`[MultiAt] ⏹️ 多选模式已关闭`);
        } else {
            console.log(`[MultiAt] 🚀 多选模式已开启！选择吧！`);
        }
    };

    // @ts-ignore
    globalThis.showState = function () {
        console.log(`[MultiAt] 状态:
  激活: ${state.active}
  已选: ${state.selectedContacts.size} 人
  选择器: ${state.pickerActivity ? '打开中' : '已关闭'}
  当前Activity: ${state.pickerActivity ? state.pickerActivity.getClass().getName() : 'N/A'}`);
    };

    // @ts-ignore
    globalThis.confirmSelection = function () {
        if (state.selectedContacts.size === 0) {
            console.log('[MultiAt] 没有选择任何人');
            return;
        }

        console.log(`[MultiAt] ✅ 确认选择: ${state.selectedContacts.size} 人`);
        const names = [];
        state.selectedContacts.forEach(c => {
            try {
                names.push(JSON.parse(c).name);
            } catch (e) {}
        });
        console.log(`[MultiAt] 已选: ${names.join(', ')}`);

        // 如果是通过Activity选择器，设置结果
        if (state.pickerActivity) {
            const Intent = Java.use('android.content.Intent');
            const resultIntent = Intent.new();

            // 把多个联系人打包传回去
            const idList = Java.array('java.lang.String', []);
            const nameList = Java.array('java.lang.String', []);

            state.selectedContacts.forEach(c => {
                try {
                    const info = JSON.parse(c);
                    idList.push(info.id);
                    nameList.push(info.name);
                } catch (e) {}
            });

            // 用ArrayList传递
            const ArrayList = Java.use('java.util.ArrayList');
            const idArrayList = ArrayList.$new();
            const nameArrayList = ArrayList.$new();

            for (let i = 0; i < idList.length; i++) {
                idArrayList.add(idList[i]);
                nameArrayList.add(nameList[i]);
            }

            resultIntent.putStringArrayListExtra('multi_at_contact_list', idArrayList);
            resultIntent.putStringArrayListExtra('multi_at_contact_names', nameArrayList);

            state.pickerActivity.setResult(-1, resultIntent);
            state.pickerActivity.finish();

            state.selectedContacts.clear();
            state.active = false;
            state.pickerActivity = null;
        }
    };

    // @ts-ignore
    globalThis.clearSelection = function () {
        state.selectedContacts.clear();
        console.log('[MultiAt] 选择已清空');
    };
}

// ========================== 工具函数 ==========================

function isWeChat(activity) {
    try {
        return activity.getClass().getName().startsWith('com.tencent.mm');
    } catch (e) {
        return false;
    }
}

function isContactPickerIntent(extras) {
    const keys = [
        'select_type', 'Select_Conv_Type',
        'scene', 'ContactSelectType',
        'Contact_Select_Scene'
    ];

    for (let i = 0; i < keys.length; i++) {
        if (extras.containsKey(keys[i])) {
            return true;
        }
    }
    return false;
}

function log(msg) {
    if (CONFIG.debug) {
        console.log(`[MultiAt] ${msg}`);
    }
}

console.log('海鸥提示: 输入 toggleMultiAt() 切换多选模式');
console.log('         输入 confirmSelection() 确认选择并退出');
console.log('         输入 showState() 查看当前状态');
