package com.glasswa.core

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/** Diagnostic milestone: capture the fully inflated WhatsApp Conversation UI tree. */
class HookEntry : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != TARGET_PACKAGE) return
        isLoaded = true
        XposedBridge.log("GlassWA: attached to $TARGET_PACKAGE")

        XposedHelpers.findAndHookMethod(
            Activity::class.java,
            "onResume",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            if (activity.javaClass.name == CONVERSATION_ACTIVITY) {
                                XposedBridge.log("GlassWA: dumping Conversation tree")
                                dumpTree(activity.window?.decorView, 0, activity.javaClass.name)
                            }
                        }
                    }, 1500L)
                }
            }
        )
    }

    private fun dumpTree(view: View?, depth: Int, activityName: String) {
        if (view == null || depth > MAX_DEPTH) return
        val indent = "  ".repeat(depth)
        val id = if (view.id != View.NO_ID) {
            try { view.resources.getResourceName(view.id) } catch (_: Throwable) { view.id.toString() }
        } else "-"
        XposedBridge.log("GlassWA:UI $activityName $indent${view.javaClass.name} id=$id")
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                dumpTree(view.getChildAt(i), depth + 1, activityName)
            }
        }
    }

    companion object {
        const val TARGET_PACKAGE = "com.whatsapp"
        const val CONVERSATION_ACTIVITY = "com.whatsapp.Conversation"
        const val MAX_DEPTH = 20
        @Volatile var isLoaded: Boolean = false
    }
}
