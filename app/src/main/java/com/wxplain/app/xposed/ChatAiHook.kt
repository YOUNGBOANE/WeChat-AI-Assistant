package com.wxplain.app.xposed

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HeaderViewListAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.wxplain.app.ai.AiEnvelope
import com.wxplain.app.wechat.ChatSlice
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier

/**
 * Hook 点对齐成功模块：Activity.onResume（基类已声明，LSPosed exact 能命中）。
 * 聊天页是盖在 LauncherUI 上的自定义层，往 ChattingUILayout addView 不会排版。
 * 悬浮钮用 Activity 窗口的 WindowManager TYPE_APPLICATION_PANEL 加在整窗之上。
 */
object ChatAiHook {
    private const val TAG = "[wxplain:ai]"
    private const val FAB_TAG = "wxplain_ai_fab"
    private const val PROVIDER = "content://com.wxplain.app.assistant"
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var talker: String = ""
    @Volatile private var busy = false
    @Volatile private var dragging = false
    private var footerRef: WeakReference<View>? = null
    private var overlayView: View? = null
    private var overlayLp: WindowManager.LayoutParams? = null
    private var optionView: View? = null
    private var optionLp: WindowManager.LayoutParams? = null
    private var imeHost: View? = null
    private var imeObserver: ViewTreeObserver.OnGlobalLayoutListener? = null
    @Volatile private var pendingTalker: String = ""
    @Volatile private var pendingQuestion: String = ""
    @Volatile private var pendingDraft: String = ""
    private var persistDraftTask: Runnable? = null
    private var lastX = -1
    private var lastY = -1

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.tencent.mm") return
        if (lpparam.processName != null && lpparam.processName != "com.tencent.mm") return

        XposedHelpers.findAndHookMethod(
            Activity::class.java,
            "onResume",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val a = param.thisObject as? Activity ?: return
                    val n = a.javaClass.name
                    if (!n.contains("LauncherUI") && !n.contains("ChattingUI") && !n.contains("chatting")) return
                    handler.postDelayed({ inject(a) }, 400)
                }
            },
        )
        XposedHelpers.findAndHookMethod(
            Activity::class.java,
            "onPause",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val a = param.thisObject as? Activity ?: return
                    val n = a.javaClass.name
                    if (!n.contains("LauncherUI") && !n.contains("ChattingUI") && !n.contains("chatting")) return
                    handler.post { inject(a) }
                }
            },
        )

        XposedHelpers.findAndHookMethod(
            View::class.java,
            "setVisibility",
            Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val v = param.thisObject as? View ?: return
                    val sn = v.javaClass.simpleName
                    if (sn != "ChatFooter" && sn != "TestTimeForChatting" && sn != "ChattingUILayout") return
                    val a = activityOf(v) ?: return
                    handler.post { inject(a) }
                }
            },
        )

        try {
            XposedHelpers.findAndHookMethod(
                "com.tencent.mm.ui.MMFragment",
                lpparam.classLoader,
                "onHiddenChanged",
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val name = param.thisObject.javaClass.name
                        if (!name.contains("Chatting")) return
                        val a = try {
                            XposedHelpers.callMethod(param.thisObject, "getActivity") as? Activity
                        } catch (_: Throwable) {
                            null
                        } ?: return
                        handler.post { inject(a) }
                    }
                },
            )
        } catch (t: Throwable) {
            XposedBridge.log("$TAG onHiddenChanged skip: ${t.message}")
        }

        XposedBridge.log("$TAG hook installed")
    }

    private fun inject(a: Activity) {
        try {
            if (dragging) return
            val root = a.window?.decorView as? ViewGroup ?: return
            if (!chattingOpen(root)) {
                captureDraft()
                removeOverlay(a)
                detachOptions(a)
                return
            }
            val footer = findShown(root, "ChatFooter") ?: findByClassName(root, "ChatFooter")
            if (footer != null) footerRef = WeakReference(footer)
            val current = extractTalker(a)
            if (current.isNotBlank()) talker = current
            if (overlayView?.isAttachedToWindow != true) showOverlay(a)
            restoreConfirm(a)
        } catch (e: Exception) {
            XposedBridge.log("$TAG inject failed: ${e.message}")
        }
    }

    private fun chattingOpen(root: ViewGroup): Boolean {
        val box = findShown(root, "ChattingUILayout") ?: findShown(root, "TestTimeForChatting")
        if (box != null && box.height > dp(160) && box.width > dp(80)) return true
        val footer = findShown(root, "ChatFooter") ?: return false
        if (footer.width < dp(40)) return false
        val r = android.graphics.Rect()
        if (!footer.getGlobalVisibleRect(r)) return false
        return r.width() > dp(40) && r.height() > dp(24)
    }

    private fun findShown(root: ViewGroup, token: String): View? {
        var hit: View? = null
        walk(root) { v ->
            if (v.javaClass.simpleName.contains(token) && v.isShown && v.width > 0 && v.height > 0) {
                hit = v
                true
            } else false
        }
        return hit
    }

    private fun showOverlay(a: Activity) {
        val attached = overlayView
        if (attached != null && attached.isAttachedToWindow) return
        if (attached != null) {
            try {
                a.windowManager.removeViewImmediate(attached)
            } catch (_: Exception) {
                (attached.parent as? ViewGroup)?.removeView(attached)
            }
            overlayView = null
        }
        val size = dp(52)
        val fab = createFab(a)
        val lp = WindowManager.LayoutParams().apply {
            width = size
            height = size
            type = WindowManager.LayoutParams.TYPE_APPLICATION_PANEL
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.BOTTOM or Gravity.END
            x = if (lastX >= 0) lastX else dp(18)
            y = if (lastY >= 0) lastY else dp(120)
            token = a.window?.decorView?.windowToken
            title = "wxplain.ai"
        }
        try {
            a.windowManager.addView(fab, lp)
            overlayView = fab
            overlayLp = lp
            XposedBridge.log("$TAG overlay added via WindowManager x=${lp.x} y=${lp.y}")
            return
        } catch (e: Exception) {
            XposedBridge.log("$TAG WindowManager failed: ${e.message}")
        }
        val decor = a.window?.decorView as? ViewGroup ?: return
        val old = decor.findViewWithTag<View>(FAB_TAG)
        if (old != null) {
            overlayView = old
            return
        }
        try {
            decor.addView(
                fab,
                FrameLayout.LayoutParams(size, size).apply {
                    gravity = Gravity.BOTTOM or Gravity.END
                    rightMargin = if (lastX >= 0) lastX else dp(18)
                    bottomMargin = if (lastY >= 0) lastY else dp(120)
                },
            )
            overlayView = fab
            XposedBridge.log("$TAG overlay added via DecorView")
        } catch (e: Exception) {
            XposedBridge.log("$TAG DecorView addView failed: ${e.message}")
        }
    }

    private fun createFab(a: Activity): TextView {
        val size = dp(52)
        val fab = TextView(a).apply {
            tag = FAB_TAG
            text = "AI"
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF1B5E20.toInt())
            }
            elevation = 24f
            layoutParams = ViewGroup.LayoutParams(size, size)
        }
        enableDrag(a, fab)
        return fab
    }

    private fun enableDrag(a: Activity, fab: TextView) {
        val slop = dp(8).toFloat()
        var downRawX = 0f
        var downRawY = 0f
        var originX = 0
        var originY = 0
        var moved = false
        fab.setOnTouchListener { v, ev ->
            val lp = overlayLp ?: v.layoutParams as? WindowManager.LayoutParams
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    moved = false
                    dragging = false
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    if (lp != null) {
                        originX = lp.x
                        originY = lp.y
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downRawX
                    val dy = ev.rawY - downRawY
                    if (kotlin.math.hypot(dx.toDouble(), dy.toDouble()) > slop) {
                        if (!moved) {
                            moved = true
                            dragging = true
                            v.alpha = 0.88f
                            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        }
                    }
                    if (moved && lp != null) {
                        lp.gravity = Gravity.BOTTOM or Gravity.END
                        lp.x = (originX - dx).toInt().coerceAtLeast(0)
                        lp.y = (originY - dy).toInt().coerceAtLeast(0)
                        lastX = lp.x
                        lastY = lp.y
                        overlayLp = lp
                        try {
                            a.windowManager.updateViewLayout(v, lp)
                        } catch (_: Exception) {
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.alpha = 1f
                    val wasDrag = moved
                    dragging = false
                    if (ev.actionMasked == MotionEvent.ACTION_UP && !wasDrag) {
                        onAiClick(a, fab)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun removeOverlay(a: Activity) {
        val fab = overlayView
        overlayView = null
        overlayLp = null
        if (fab == null) return
        try {
            a.windowManager.removeViewImmediate(fab)
        } catch (_: Exception) {
            (fab.parent as? ViewGroup)?.removeView(fab)
        }
    }

    private fun extractTalker(activity: Activity): String {
        val extra = activity.intent?.getStringExtra("Chat_User")
        if (!extra.isNullOrBlank()) return extra.trim()
        val queue = ArrayDeque<Any>()
        try {
            queue.add(XposedHelpers.callMethod(activity, "getSupportFragmentManager"))
        } catch (_: Throwable) {
        }
        try {
            queue.add(activity.fragmentManager)
        } catch (_: Throwable) {
        }
        var guard = 0
        while (queue.isNotEmpty() && guard++ < 40) {
            val fm = queue.removeFirst()
            for (frag in fragmentList(fm)) {
                val name = frag.javaClass.name
                if (name.contains("ChattingUIFragment")) {
                    try {
                        val args = XposedHelpers.callMethod(frag, "getArguments") as? Bundle
                        val u = args?.getString("Chat_User")
                        if (!u.isNullOrBlank()) return u.trim()
                    } catch (_: Throwable) {
                    }
                    invokeString(frag, "getTalkerUserName")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
                    invokeString(frag, "getTalker")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
                }
                try {
                    queue.add(XposedHelpers.callMethod(frag, "getChildFragmentManager"))
                } catch (_: Throwable) {
                }
            }
        }
        val footer = footerRef?.get()
        if (footer != null) {
            val fromFooter = talkerFromObject(footer)
            if (fromFooter.isNotBlank()) return fromFooter
        }
        return ""
    }

    private fun fragmentList(fm: Any): List<Any> {
        try {
            val r = XposedHelpers.callMethod(fm, "getFragments")
            if (r is List<*>) return r.filterNotNull()
        } catch (_: Throwable) {
        }
        for (name in arrayOf("mAdded", "mActive")) {
            val f = field(fm.javaClass, name) ?: continue
            f.isAccessible = true
            when (val v = try { f.get(fm) } catch (_: Throwable) { null }) {
                is List<*> -> return v.filterNotNull()
                is Array<*> -> return v.filterNotNull()
                is Map<*, *> -> return v.values.filterNotNull()
            }
        }
        return emptyList()
    }

    private fun talkerFromObject(obj: Any): String {
        for (f in declaredFields(obj.javaClass)) {
            if (f.type != String::class.java) continue
            f.isAccessible = true
            val v = try { f.get(obj) as? String } catch (_: Throwable) { null } ?: continue
            val s = v.trim()
            if (s.startsWith("wxid_") || s.endsWith("@chatroom") || s.startsWith("gh_")) return s
        }
        return ""
    }

    private fun findByClassName(v: ViewGroup, n: String): View? {
        for (i in 0 until v.childCount) {
            val c = v.getChildAt(i)
            if (c.javaClass.simpleName.contains(n)) return c
            if (c is ViewGroup) {
                val r = findByClassName(c, n)
                if (r != null) return r
            }
        }
        return null
    }

    private fun onAiClick(activity: Activity, btn: TextView) {
        requestAi(activity, btn, choice = null)
    }

    private data class HookResult(
        val type: String,
        val text: String,
        val question: String,
        val memoryUpdated: Boolean,
    )

    private fun requestAi(activity: Activity, btn: TextView, choice: String?) {
        if (busy) return
        busy = true
        if (choice == null) dismissConfirm(activity) else detachOptions(activity)
        btn.text = "…"
        btn.isEnabled = false
        val talkerNow = extractTalker(activity)
        talker = talkerNow
        Thread {
            val result = runCatching { callComplete(activity, talkerNow, choice) }
            handler.post {
                busy = false
                btn.text = "AI"
                btn.isEnabled = true
                result.fold(
                    onSuccess = { payload ->
                        if (payload.memoryUpdated) toast(activity, "已更新记忆")
                        if (payload.type == "option") {
                            showConfirm(activity, btn, payload.question)
                        } else {
                            val root = activity.window?.decorView as? ViewGroup
                            val footer = footerRef?.get() ?: root?.let { findByClassName(it, "ChatFooter") }
                            if (footer != null) fillInput(footer, payload.text)
                            else toast(activity, "找不到输入框")
                        }
                    },
                    onFailure = { t ->
                        XposedBridge.log("$TAG complete failed: ${t.message}")
                        toast(activity, t.message ?: "生成失败")
                    },
                )
            }
        }.apply { name = "wxplain-ai"; isDaemon = true }.start()
    }

    private fun callComplete(activity: Activity, talkerNow: String, choice: String?): HookResult {
        val fallback = collectChat(activity)
        XposedBridge.log("$TAG talker=$talkerNow choice=${choice != null} fallback=${fallback.length}")
        val extras = Bundle().apply {
            putString("talker", talkerNow)
            putString("chat", fallback)
            if (!choice.isNullOrBlank()) putString("choice", choice)
        }
        val out = activity.contentResolver.call(Uri.parse(PROVIDER), "complete", null, extras)
            ?: error("助手没有响应，先打开一次 Wechat AI Assistant")
        val err = out.getString("error")
        if (!err.isNullOrBlank()) error(err)
        val type = out.getString("type").orEmpty().ifBlank { "reply" }
        val question = out.getString("question")?.trim().orEmpty()
        val text = out.getString("text")?.trim().orEmpty()
        val memoryUpdated = out.getBoolean("memory_updated", false)
        if (type == "option") {
            if (question.isBlank()) error("没有待确认的问题")
            return HookResult("option", "", question, memoryUpdated)
        }
        if (text.isBlank()) error("模型返回为空")
        return HookResult("reply", text, "", memoryUpdated)
    }

    private fun showConfirm(activity: Activity, btn: TextView, question: String) {
        detachOptions(activity)
        if (pendingQuestion != question) pendingDraft = ""
        pendingQuestion = question
        pendingTalker = talker
        persistPending(activity)
        val q = AiEnvelope.asQuestion(question)
        fun submit(answer: String) {
            val body = answer.trim()
            if (body.isEmpty()) {
                toast(activity, "请填写答复")
                return
            }
            dismissConfirm(activity)
            requestAi(activity, btn, AiEnvelope.formatChoice(question, body))
        }
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(1), 0x22000000)
            }
            elevation = 20f
        }
        card.addView(TextView(activity).apply {
            text = "需要人工确认"
            textSize = 12f
            setTextColor(0xFF757575.toInt())
            setPadding(0, 0, 0, dp(6))
        })
        card.addView(TextView(activity).apply {
            text = q
            textSize = 17f
            setTextColor(0xFF212121.toInt())
            setPadding(0, 0, 0, dp(12))
        })
        val answerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val input = EditText(activity).apply {
            hint = "请填写需要补充的信息"
            textSize = 15f
            minHeight = dp(40)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(0xFFF5F5F5.toInt())
                setStroke(dp(1), 0x33000000)
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setText(pendingDraft)
            try {
                setSelection(text?.length ?: 0)
            } catch (_: Throwable) {
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    pendingDraft = s?.toString().orEmpty()
                    schedulePersistPending(activity)
                }
            })
        }
        val send = TextView(activity).apply {
            text = "确定"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(0xFF1B5E20.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(8) }
            isClickable = true
            setOnClickListener { submit(input.text?.toString().orEmpty()) }
        }
        input.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) scheduleLiftConfirm(activity)
        }
        answerRow.addView(input)
        answerRow.addView(send)
        card.addView(answerRow)
        card.addView(TextView(activity).apply {
            text = "取消"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(0xFF757575.toInt())
            setPadding(0, dp(12), 0, dp(4))
            isClickable = true
            setOnClickListener { dismissConfirm(activity) }
        })
        val wrap = FrameLayout(activity).apply {
            setPadding(dp(12), dp(12), dp(12), dp(12))
            addView(
                card,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        val lp = WindowManager.LayoutParams().apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            type = WindowManager.LayoutParams.TYPE_APPLICATION_PANEL
            flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(120)
            token = activity.window?.decorView?.windowToken
            title = "wxplain.ai.options"
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }
        try {
            activity.windowManager.addView(wrap, lp)
            optionView = wrap
            optionLp = lp
            watchIme(activity)
            scheduleLiftConfirm(activity)
            XposedBridge.log("$TAG confirm panel added q=$q")
        } catch (e: Exception) {
            XposedBridge.log("$TAG confirm panel failed: ${e.message}")
            toast(activity, "无法显示确认框")
        }
    }

    private fun watchIme(activity: Activity) {
        unwatchIme()
        val host = activity.window?.decorView ?: return
        val listener = ViewTreeObserver.OnGlobalLayoutListener { liftConfirm(activity) }
        imeHost = host
        imeObserver = listener
        host.viewTreeObserver.addOnGlobalLayoutListener(listener)
    }

    private fun unwatchIme() {
        val host = imeHost
        val listener = imeObserver
        imeHost = null
        imeObserver = null
        if (host != null && listener != null) {
            try {
                host.viewTreeObserver.removeOnGlobalLayoutListener(listener)
            } catch (_: Exception) {
            }
        }
    }

    private fun scheduleLiftConfirm(activity: Activity) {
        liftConfirm(activity)
        handler.post { liftConfirm(activity) }
        handler.postDelayed({ liftConfirm(activity) }, 160)
        handler.postDelayed({ liftConfirm(activity) }, 360)
    }

    private fun liftConfirm(activity: Activity) {
        val v = optionView ?: return
        val lp = optionLp ?: return
        val screenH = activity.resources.displayMetrics.heightPixels
        val r = Rect()
        activity.window?.decorView?.getWindowVisibleDisplayFrame(r)
        var ime = 0
        if (Build.VERSION.SDK_INT >= 30) {
            val insets = v.rootWindowInsets ?: activity.window?.decorView?.rootWindowInsets
            ime = insets?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0
        }
        if (ime < dp(80) && r.height() > 0) {
            ime = (screenH - r.bottom).coerceAtLeast(0)
        }
        if (ime < dp(80) && v.findFocus() is EditText) {
            ime = (screenH * 0.38f).toInt()
        }
        val visibleBottom = if (ime >= dp(80)) screenH - ime else {
            if (r.bottom > 0) r.bottom else screenH - dp(88)
        }
        val widthPx = r.width().takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
        v.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(screenH, View.MeasureSpec.AT_MOST),
        )
        val h = maxOf(v.measuredHeight, v.height)
        val minTop = (if (r.top > 0) r.top else dp(24)) + dp(8)
        val topY = (visibleBottom - h - dp(8)).coerceAtLeast(minTop)
        if (lp.gravity == (Gravity.TOP or Gravity.CENTER_HORIZONTAL) && lp.y == topY) return
        lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        lp.y = topY
        try {
            activity.windowManager.updateViewLayout(v, lp)
        } catch (_: Exception) {
        }
    }

    private fun restoreConfirm(a: Activity) {
        val current = extractTalker(a).ifBlank { talker }
        val localOk = pendingQuestion.isNotBlank() &&
            (pendingTalker.isBlank() || current.isBlank() || pendingTalker == current)
        if (!localOk && current.isNotBlank()) {
            val remote = loadPendingRemote(a, current)
            if (remote != null) {
                pendingTalker = current
                pendingQuestion = remote.first
                pendingDraft = remote.second
            }
        }
        if (pendingQuestion.isBlank()) return
        if (pendingTalker.isNotBlank() && current.isNotBlank() && pendingTalker != current) return
        if (optionView?.isAttachedToWindow == true) return
        val btn = overlayView as? TextView ?: return
        showConfirm(a, btn, pendingQuestion)
    }

    private fun captureDraft() {
        val host = optionView ?: return
        var hit: EditText? = null
        walk(host) { v ->
            if (v is EditText) {
                hit = v
                true
            } else false
        }
        pendingDraft = hit?.text?.toString().orEmpty()
    }

    private fun detachOptions(a: Activity) {
        captureDraft()
        persistPending(a)
        unwatchIme()
        optionLp = null
        val v = optionView
        optionView = null
        if (v == null) return
        try {
            a.windowManager.removeViewImmediate(v)
        } catch (_: Exception) {
            (v.parent as? ViewGroup)?.removeView(v)
        }
    }

    private fun dismissConfirm(a: Activity) {
        val t = pendingTalker.ifBlank { extractTalker(a) }
        persistDraftTask?.let { handler.removeCallbacks(it) }
        persistDraftTask = null
        detachOptions(a)
        pendingTalker = ""
        pendingQuestion = ""
        pendingDraft = ""
        if (t.isNotBlank()) clearPendingRemote(a, t)
    }

    private fun schedulePersistPending(ctx: Context) {
        persistDraftTask?.let { handler.removeCallbacks(it) }
        val task = Runnable { persistPending(ctx) }
        persistDraftTask = task
        handler.postDelayed(task, 400)
    }

    private fun persistPending(ctx: Context) {
        if (pendingTalker.isBlank() || pendingQuestion.isBlank()) return
        try {
            val extras = Bundle().apply {
                putString("talker", pendingTalker)
                putString("question", pendingQuestion)
                putString("draft", pendingDraft)
            }
            ctx.contentResolver.call(Uri.parse(PROVIDER), "pending_save", null, extras)
        } catch (t: Throwable) {
            XposedBridge.log("$TAG persist pending failed: ${t.message}")
        }
    }

    private fun loadPendingRemote(ctx: Context, talker: String): Pair<String, String>? {
        return try {
            val extras = Bundle().apply { putString("talker", talker) }
            val out = ctx.contentResolver.call(Uri.parse(PROVIDER), "pending_get", null, extras) ?: return null
            val q = out.getString("question")?.trim().orEmpty()
            if (q.isEmpty()) null else q to out.getString("draft").orEmpty()
        } catch (t: Throwable) {
            XposedBridge.log("$TAG load pending failed: ${t.message}")
            null
        }
    }

    private fun clearPendingRemote(ctx: Context, talker: String) {
        try {
            val extras = Bundle().apply { putString("talker", talker) }
            ctx.contentResolver.call(Uri.parse(PROVIDER), "pending_clear", null, extras)
        } catch (t: Throwable) {
            XposedBridge.log("$TAG clear pending failed: ${t.message}")
        }
    }

    private fun fillInput(footer: View, text: String) {
        try {
            XposedHelpers.callMethod(footer, "setLastText", text)
        } catch (t: Throwable) {
            XposedBridge.log("$TAG setLastText: ${t.message}")
        }
        val edit = findInput(footer)
        if (edit != null && edit.text?.toString() != text) {
            edit.setText(text)
            try {
                edit.setSelection(text.length)
            } catch (_: Throwable) {
            }
            edit.requestFocus()
        }
    }

    private fun collectChat(activity: Activity): String {
        val root = activity.window?.decorView as? ViewGroup ?: return ""
        val list = findByClassName(root, "MMChattingListView") ?: findListHost(root)
        val lines = LinkedHashMap<String, String>()
        if (list != null) pullFromAdapter(list, lines)
        if (lines.isEmpty() && list is ViewGroup) pullFromViews(list, lines)
        return lines.values.joinToString("\n")
    }

    private fun pullFromAdapter(host: View, out: MutableMap<String, String>) {
        val adapter = try {
            host.javaClass.getMethod("getAdapter").invoke(host)
        } catch (_: Throwable) {
            null
        } ?: return
        val raw = (adapter as? HeaderViewListAdapter)?.wrappedAdapter ?: adapter
        val count = try {
            (raw.javaClass.getMethod("getCount").invoke(raw) as? Int)
                ?: (raw.javaClass.getMethod("getItemCount").invoke(raw) as? Int)
                ?: 0
        } catch (_: Throwable) {
            0
        }
        if (count <= 0) return
        val getItem = raw.javaClass.methods.firstOrNull {
            it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                it.name == "getItem" &&
                it.returnType != Void.TYPE
        }
        val start = (count - 24).coerceAtLeast(0)
        for (i in start until count) {
            val item = try {
                getItem?.invoke(raw, i)
            } catch (_: Throwable) {
                null
            } ?: continue
            val msg = findMessage(item, 0) ?: continue
            val line = formatLine(msg)
            if (line != null) out[msg.key] = line
        }
    }

    private fun pullFromViews(host: ViewGroup, out: MutableMap<String, String>) {
        walk(host) { v ->
            if (v is TextView && v !is EditText && v.text?.length in 1..800) {
                val t = v.text.toString().trim()
                if (t.isNotEmpty() && !t.matches(Regex("\\d{1,2}:\\d{2}"))) {
                    out[t] = "对方: $t"
                }
            }
            false
        }
    }

    private data class Msg(
        val key: String,
        val content: String,
        val type: Int,
        val isSend: Boolean,
    )

    private fun findMessage(obj: Any?, depth: Int): Msg? {
        if (obj == null || depth > 4) return null
        val cls = obj.javaClass
        if (cls.name.startsWith("android.") || obj is View) return null
        val content = readString(obj, "field_content") ?: invokeString(obj, "getContent")
        if (content != null) {
            val type = readInt(obj, "field_type") ?: invokeInt(obj, "getType") ?: 1
            val send = (readInt(obj, "field_isSend") ?: invokeInt(obj, "isSend") ?: 0) == 1
            val id = readString(obj, "field_msgId") ?: content.take(40)
            return Msg(id + content.hashCode(), content, type, send)
        }
        for (f in declaredFields(cls)) {
            if (Modifier.isStatic(f.modifiers)) continue
            val t = f.type
            if (t.isPrimitive || t == String::class.java || t.name.startsWith("android.")) continue
            f.isAccessible = true
            val v = try {
                f.get(obj)
            } catch (_: Throwable) {
                null
            } ?: continue
            val found = findMessage(v, depth + 1)
            if (found != null) return found
        }
        return null
    }

    private fun formatLine(msg: Msg): String? {
        if (msg.type == 10000 || msg.type == 10002) return null
        var body = msg.content.replace('\u0000', ' ').trim()
        if (body.isEmpty() && msg.type == 1) return null
        if (msg.type != 1) {
            val plain = ChatSlice.plainText(body)
            body = if (plain.isEmpty()) "[${typeLabel(msg.type)}]" else "[${typeLabel(msg.type)}] $plain"
        }
        if (body.isEmpty()) return null
        if (!msg.isSend && body.contains(":\n")) {
            val idx = body.indexOf(":\n")
            val who = body.substring(0, idx).trim().ifBlank { "对方" }
            body = body.substring(idx + 2).trim()
            return ChatSlice.clipLine("${who}: $body")
        }
        val who = if (msg.isSend) "我" else "对方"
        return ChatSlice.clipLine("$who: $body")
    }

    private fun typeLabel(type: Int): String = when (type) {
        3 -> "图片"
        34 -> "语音"
        42 -> "名片"
        43 -> "视频"
        47 -> "表情"
        48 -> "位置"
        49 -> "链接/文件"
        else -> "类型$type"
    }

    private fun findInput(footer: View): EditText? {
        var best: EditText? = null
        walk(footer) { v ->
            if (v is EditText && v.visibility == View.VISIBLE) {
                if (v.javaClass.name.contains("MMEditText") || best == null) best = v
            }
            false
        }
        return best
    }

    private fun findListHost(root: View?): View? {
        var found: View? = null
        if (root != null) walk(root) { v ->
            if (v is ListView && v.visibility == View.VISIBLE && v.height > dp(80)) {
                found = v
                true
            } else false
        }
        return found
    }

    private fun walk(v: View, visitor: (View) -> Boolean) {
        if (visitor(v)) return
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) walk(v.getChildAt(i), visitor)
        }
    }

    private fun activityOf(obj: Any): Activity? {
        when (obj) {
            is Activity -> return obj
            is View -> {
                var c: Context? = obj.context
                while (c is ContextWrapper) {
                    if (c is Activity) return c
                    c = c.baseContext
                }
            }
        }
        return null
    }

    private fun declaredFields(cls: Class<*>): List<java.lang.reflect.Field> {
        val out = ArrayList<java.lang.reflect.Field>()
        var c: Class<*>? = cls
        var n = 0
        while (c != null && n++ < 5) {
            out.addAll(c.declaredFields)
            c = c.superclass
        }
        return out
    }

    private fun readString(obj: Any, name: String): String? {
        val f = field(obj.javaClass, name) ?: return null
        f.isAccessible = true
        return try {
            f.get(obj) as? String
        } catch (_: Throwable) {
            null
        }
    }

    private fun readInt(obj: Any, name: String): Int? {
        val f = field(obj.javaClass, name) ?: return null
        f.isAccessible = true
        return try {
            (f.get(obj) as? Number)?.toInt()
        } catch (_: Throwable) {
            null
        }
    }

    private fun field(cls: Class<*>, name: String): java.lang.reflect.Field? {
        var c: Class<*>? = cls
        while (c != null) {
            try {
                return c.getDeclaredField(name)
            } catch (_: Throwable) {
                c = c.superclass
            }
        }
        return null
    }

    private fun invokeString(obj: Any, name: String): String? = try {
        XposedHelpers.callMethod(obj, name) as? String
    } catch (_: Throwable) {
        null
    }

    private fun invokeInt(obj: Any, name: String): Int? = try {
        (XposedHelpers.callMethod(obj, name) as? Number)?.toInt()
    } catch (_: Throwable) {
        null
    }

    private fun toast(ctx: Context, msg: String) {
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int =
        (v * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
}
