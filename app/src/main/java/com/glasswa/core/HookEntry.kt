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

/** GlassWA visual milestones: composer + conversation toolbar. */
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
                    if (!activity.isFinishing && !activity.isDestroyed) applyGlassSurfaces(activity)
                }, 800L)
            }
        })
    }

    private fun applyGlassSurfaces(activity: Activity) {
        try {
            val root = activity.window?.decorView ?: return
            val footer = findByName(root, "com.whatsapp:id/footer")
            val editLayout = findByName(root, "com.whatsapp:id/edit_layout")
            val toolbar = findByName(root, "com.whatsapp:id/toolbar")

            footer?.setBackgroundColor(Color.TRANSPARENT)

            editLayout?.let {
                it.background = composerGlassDrawable()
                it.elevation = 4f * it.resources.displayMetrics.density
                it.clipToOutline = true
                it.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            }

            toolbar?.let {
                it.background = toolbarGlassDrawable()
                it.elevation = 3f * it.resources.displayMetrics.density
            }

            XposedBridge.log(
                "GlassWA: glass applied composer=${editLayout != null} toolbar=${toolbar != null}"
            )
        } catch (t: Throwable) {
            XposedBridge.log("GlassWA: glass failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun composerGlassDrawable() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 28f
        setColor(Color.argb(150, 255, 255, 255))
        setStroke(1, Color.argb(90, 255, 255, 255))
    }

    private fun toolbarGlassDrawable() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(Color.argb(120, 18, 18, 18))
        setStroke(1, Color.argb(55, 255, 255, 255))
    }

    private fun findByName(root: View, resourceName: String): View? {
        if (root.id != View.NO_ID) {
            try {
                if (root.resources.getResourceName(root.id) == resourceName) return root
            } catch (_: Throwable) { }
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findByName(root.getChildAt(i), resourceName)?.let { return it }
            }
        }
        return null
    }

    companion object {
        const val TARGET_PACKAGE = "com.whatsapp"
        const val CONVERSATION_ACTIVITY = "com.whatsapp.Conversation"
        @Volatile var isLoaded: Boolean = false
    }
}
