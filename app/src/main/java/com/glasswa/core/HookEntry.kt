package com.glasswa.core

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/** First visual milestone: a reversible glass surface on WhatsApp's composer. */
class HookEntry : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != TARGET_PACKAGE) return
        isLoaded = true
        XposedBridge.log("GlassWA: attached to $TARGET_PACKAGE")

        XposedHelpers.findAndHookMethod(Activity::class.java, "onResume", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as? Activity ?: return
                if (activity.javaClass.name != CONVERSATION_ACTIVITY) return
                Handler(Looper.getMainLooper()).postDelayed({
                    if (!activity.isFinishing && !activity.isDestroyed) applyComposerGlass(activity)
                }, 800L)
            }
        })
    }

    private fun applyComposerGlass(activity: Activity) {
        try {
            val root = activity.window?.decorView ?: return
            val footer = findByName(root, "com.whatsapp:id/footer") ?: return
            val editLayout = findByName(root, "com.whatsapp:id/edit_layout") ?: return
            footer.setBackgroundColor(Color.TRANSPARENT)
            editLayout.background = glassDrawable()
            editLayout.elevation = 4f * editLayout.resources.displayMetrics.density
            editLayout.clipToOutline = true
            editLayout.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            XposedBridge.log("GlassWA: composer glass applied")
        } catch (t: Throwable) {
            XposedBridge.log("GlassWA: composer glass failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun glassDrawable() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 28f
        setColor(Color.argb(150, 255, 255, 255))
        setStroke(1, Color.argb(90, 255, 255, 255))
    }

    private fun findByName(root: View, resourceName: String): View? {
        if (root.id != View.NO_ID) {
            try { if (root.resources.getResourceName(root.id) == resourceName) return root } catch (_: Throwable) { }
        }
        if (root is ViewGroup) for (i in 0 until root.childCount) findByName(root.getChildAt(i), resourceName)?.let { return it }
        return null
    }

    companion object {
        const val TARGET_PACKAGE = "com.whatsapp"
        const val CONVERSATION_ACTIVITY = "com.whatsapp.Conversation"
        @Volatile var isLoaded: Boolean = false
    }
}
