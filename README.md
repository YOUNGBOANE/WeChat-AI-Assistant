# Wechat AI Assistant

单 APK：LSPosed 模块 + 助手界面。在微信会话里调用大模型起草回复；会话查询、记忆和模型请求都在助手进程完成。

## 需要什么

- 已 root 的 Android 设备（minSdk 26）
- LSPosed（或兼容框架），模块作用域勾选微信 `com.tencent.mm`
- 微信版本8.0.74
- 模型 API Key（DeepSeek 或火山方舟，OpenAI 兼容接口）

## 使用

1. 安装 APK。
2. 在框架中启用 **Wechat AI Assistant**，作用域勾选微信。
3. 在超级用户管理里授权本应用。
4. 完全退出并重开一次微信（模块挂上后才会拿到会话库口令）。
5. 打开本应用：环境通过时首页右上角不提示；不通过时点「检测不通过」查看缺哪一项。
6. **模型**里选择服务商并填写 API Key；可按需改 **提示词**、**关键词资料**。
7. 打开微信进入会话，用悬浮钮生成回复。不确定的事实会变成向你提问，确认后再生成正文。
8. **聊天记录**下拉刷新，浏览当前会话库的副本。

## 助手做什么

- **提示词**：角色、语气、回复原则；系统会再追加固定的标签输出约定。
- **记忆**：按会话保存背景。无记忆且可用记录超过 10 条时，先让模型只写 `<context>` 初始化记忆，再按最近 10 条生成回复。
- **关键词**：对方最近连续发言命中词时，把对应说明带给模型。
- **模型输出**：`<reply>` 填进输入框；`<option>` 向操作者确认；`<context>` / `<context_update>` 更新该会话记忆（二者互斥）。
- **使用日志**：每次发给模型的内容和回复（或错误）。

## 架构（简）

```
微信进程（注入）          助手进程
  KeyHook ──口令──► KeyProvider
  ChatAiHook ──call──► AssistantProvider
                         ├ WeChatStore 快照会话库
                         ├ LiveDb + ChatSlice 组上下文
                         ├ Memory / Keyword / Prompt
                         └ AiClient → 模型 API
```

微信进程不调模型。两个 ContentProvider 只接受本应用或微信。

源码在 `app/src/main/java/com/wxplain/app/`：

| 包 | 职责 |
| --- | --- |
| `xposed` | 模块入口、聊天页悬浮钮 |
| `ingest` | 跨进程 Provider、口令存储 |
| `wechat` | 会话库快照与查询 |
| `ai` | HTTP 调用与标签协议 |
| `root` | libsu |

业务编排在 `AssistantProvider.call("complete")`。

## 构建

Android Studio / Gradle，Kotlin 17，compileSdk 35。Xposed API 为 `compileOnly`。
