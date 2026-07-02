# 🕊️ 海鸥 WeChat Multi-@

**微信群聊 @ 多人不用反复搜索！**

## 痛点

在群聊里需要 @ 多个人时，微信的@选择器每次选完人就关闭，想@下一个必须重新输入@重新搜索。群几百号人的时候，操作体验跟屎一样。

## 解决方案

### 🏆 方案一：无障碍服务 APK（推荐，无需Root）

纯 Android AccessibilityService 实现，**不需要 Root，不需要 Xposed，不需要电脑**。

直接装APK -> 开启无障碍服务 -> 搞定。

[点我下载 APK](https://github.com/seagull-hacker/wechat-multi-at/releases) (TODO: 发布Release)

**使用方法：**

1. 安装 APK
2. 打开 app，开启「无障碍服务」和「悬浮窗权限」
3. 进入微信群聊
4. 点击悬浮的海鸥图标（开启多选模式）
5. 正常输入 @ 选择第一个人
6. **App 会自动帮你输入下一个 @**，选择器重新打开
7. 继续选第二个、第三个...
8. 选完点击海鸥图标关闭
9. 输入消息，发送

**实现原理：**

```
用户：输入@ → 选人 → @张三 被插入
海鸥：检测到@插入 → 自动在末尾追加@ → 选择器重新打开
用户：选李四 → @张三 @李四 被插入
海鸥：再次自动追加@...
用户：选完，关闭多选，发送消息
```

纯无障碍 API，无侵入，不修改微信。

### 🔧 方案二：Frida 脚本（需要电脑）

适合开发者/测试用，手机连接电脑后注入。

```bash
# 手机运行 frida-server
frida -U com.tencent.mm -l frida/wechat-multi-at.js

# 然后在Frida控制台：
toggleMultiAt()   # 开启多选
confirmSelection() # 确认选择
```

### 🦾 方案三：LSPosed 模块（需要Root）

已经 Root 的用户，可以用 LSPosed 模块，效果更丝滑。
详见 `lsposed/` 目录。

## 构建

```bash
git clone https://github.com/seagull-hacker/wechat-multi-at
cd wechat-multi-at
# 用 Android Studio 打开，Build → Build APK
# 或者命令行：
./gradlew assembleRelease
```

## 关于

海鸥出品，开源项目，随便用。
有问题提 Issue，老子看到了会回。

## License

MIT
