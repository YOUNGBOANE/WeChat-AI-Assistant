package com.wxplain.app.xposed

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

class HookEntry : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.tencent.mm") return
        XposedBridge.log("[wxplain] loaded in WeChat pid=${android.os.Process.myPid()}")
        KeyHook.install(lpparam)
        ChatAiHook.install(lpparam)
    }
}
