package com.glasswa.core

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class HookEntry : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != TARGET_PACKAGE) return
        XposedBridge.log("GlassWA: attached to $TARGET_PACKAGE")
        XposedHelpers.findAndHookMethod(Activity::class.java, "onResume", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as? Activity ?: return
                when (activity.javaClass.name) {
                    CONVERSATION_ACTIVITY -> schedule(activity, false)
                    HOME_ACTIVITY -> schedule(activity, true)
                }
            }
        })
    }

    private fun schedule(activity: Activity, home: Boolean) {
        Handler(activity.mainLooper).postDelayed({
            if (!activity.isFinishing && !activity.isDestroyed) {
                if (home) applyHome(activity) else applyConversation(activity)
            }
        }, 350L)
        Handler(activity.mainLooper).postDelayed({
            if (!activity.isFinishing && !activity.isDestroyed) {
                if (home) applyHome(activity) else applyConversation(activity)
            }
        }, 1200L)
    }

    private fun applyConversation(activity: Activity) {
        try {
            val root = activity.window?.decorView ?: return
            windowTheme(activity)
            root.setBackgroundColor(AMOLED)
            val conversation = find(root, "com.whatsapp:id/conversation_root_layout") ?: root
            find(root, "com.whatsapp:id/toolbar")?.let { glassOnce(it, toolbarGlass(), TAG_TOOLBAR) }
            find(root, "com.whatsapp:id/footer")?.setBackgroundColor(Color.TRANSPARENT)
            find(root, "com.whatsapp:id/edit_layout")?.let { glassOnce(it, composerGlass(), TAG_COMPOSER) }
            find(root, "com.whatsapp:id/text_entry_layout")?.let { input ->
                if (input !== find(root, "com.whatsapp:id/edit_layout")) glassOnce(input, innerGlass(), TAG_INPUT)
            }
            val bubbles = styleMessageRows(conversation)
            val media = styleMediaRows(conversation)
            val labels = styleLabels(conversation)
            val sheets = styleAttachmentSheets(root)
            XposedBridge.log("GlassWA: AMOLED conversation bubbles=$bubbles media=$media labels=$labels sheets=$sheets")
        } catch (t: Throwable) {
            XposedBridge.log("GlassWA: conversation failed ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun applyHome(activity: Activity) {
        try {
            val root = activity.window?.decorView ?: return
            windowTheme(activity)
            root.setBackgroundColor(AMOLED)
            val main = find(root, "com.whatsapp:id/main_container") ?: root
            find(root, "com.whatsapp:id/navigation_bar_protection")?.setBackgroundColor(AMOLED)
            XposedBridge.log("GlassWA: AMOLED home glass_surfaces=${styleHomeSurfaces(main)}")
        } catch (t: Throwable) {
            XposedBridge.log("GlassWA: home failed ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun windowTheme(activity: Activity) {
        activity.window?.let { it.statusBarColor = AMOLED; it.navigationBarColor = AMOLED }
    }

    private fun styleMessageRows(root: View): Int {
        var count = 0
        walk(root) { view ->
            if (view.getTag(TAG_BUBBLE) == true || view.visibility != View.VISIBLE || view.width <= 0) return@walk
            if (resource(view) != "com.whatsapp:id/conversation_text_row") return@walk
            val p = IntArray(2); view.getLocationOnScreen(p)
            val outgoing = p[0] + view.width / 2f > view.resources.displayMetrics.widthPixels * 0.52f
            view.background = bubble(outgoing)
            view.elevation = 1.5f * view.resources.displayMetrics.density
            view.setTag(TAG_BUBBLE, true); view.invalidate(); count++
        }
        return count
    }

    private fun styleMediaRows(root: View): Int {
        var count = 0
        walk(root) { view ->
            if (view.getTag(TAG_MEDIA) == true) return@walk
            val id = resource(view)
            if (id != "com.whatsapp:id/media_container" && id != "com.whatsapp:id/text_and_date") return@walk
            if (view.width <= 0 || view.height <= 0) return@walk
            view.background = mediaSurface(); view.setTag(TAG_MEDIA, true); count++
        }
        return count
    }

    private fun styleLabels(root: View): Int {
        var count = 0
        walk(root) { view ->
            val text = view as? TextView ?: return@walk
            if (text.getTag(TAG_LABEL) == true) return@walk
            val value = text.text?.toString()?.trim()?.lowercase() ?: return@walk
            if (value != "today" && value != "yesterday") return@walk
            val parent = text.parent as? ViewGroup ?: return@walk
            if (parent.width <= 0 || parent.height <= 0 || parent.height > text.resources.displayMetrics.density * 160) return@walk
            parent.background = chip(); parent.setTag(TAG_LABEL, true); count++
        }
        return count
    }

    private fun styleAttachmentSheets(root: View): Int {
        var count = 0
        walk(root) { view ->
            if (view.getTag(TAG_SHEET) == true) return@walk
            val group = view as? ViewGroup ?: return@walk
            val h = root.resources.displayMetrics.heightPixels.toFloat()
            val w = root.resources.displayMetrics.widthPixels.toFloat()
            if (group.visibility != View.VISIBLE || group.width < w * .82f || group.height < h * .20f || group.height > h * .72f || group.top < h * .40f) return@walk
            val name = group.javaClass.name.lowercase(); val res = resource(group).lowercase()
            if (!(name.contains("bottomsheet") || name.contains("attachmentsheet") || name.contains("gallerypicker") || res.contains("bottom_sheet") || res.contains("attachment") || res.contains("media_picker"))) return@walk
            group.background = attachmentGlass(); group.elevation = 8f * root.resources.displayMetrics.density; group.setTag(TAG_SHEET, true); count++
        }
        return count
    }

    private fun styleHomeSurfaces(root: View): Int {
        var count = 0
        walk(root) { view ->
            if (view.getTag(TAG_HOME_SURFACE) == true || view.visibility != View.VISIBLE || view.width <= 0 || view.height <= 0) return@walk
            val name = view.javaClass.name.lowercase(); val res = resource(view).lowercase()
            val toolbar = name.contains("wdstoolbar") || name.contains("topbar") || res.contains("toolbar")
            val search = name.contains("searchview") || name.contains("search_view")
            val navigation = name.contains("bottomnavigation") || name.contains("navigationrail") || name.contains("navigationbar") || name.contains("tabbar") || res.contains("bottom_navigation") || res.contains("navigation_rail")
            if (!toolbar && !search && !navigation) return@walk
            if (res.contains("navigation_bar_protection")) return@walk
            view.background = if (navigation) homeNavigationGlass() else homeSurfaceGlass()
            view.elevation = if (navigation) 5f * root.resources.displayMetrics.density else 3f * root.resources.displayMetrics.density
            view.setTag(TAG_HOME_SURFACE, true); count++
        }
        return count
    }

    private fun glassOnce(view: View, drawable: GradientDrawable, tag: Int) {
        if (view.getTag(tag) == true) return
        view.background = drawable; view.setTag(tag, true); view.invalidate()
    }
    private fun toolbarGlass() = GradientDrawable().apply { shape=GradientDrawable.RECTANGLE; colors=intArrayOf(Color.argb(235,8,8,10),Color.argb(220,40,5,23)); orientation=GradientDrawable.Orientation.TOP_BOTTOM; setStroke(1,Color.argb(55,170,45,92)) }
    private fun composerGlass() = GradientDrawable().apply { shape=GradientDrawable.RECTANGLE; cornerRadius=34f; colors=intArrayOf(Color.argb(230,10,10,12),Color.argb(215,55,7,30)); orientation=GradientDrawable.Orientation.TL_BR; setStroke(1,Color.argb(70,174,45,96)) }
    private fun innerGlass() = GradientDrawable().apply { shape=GradientDrawable.RECTANGLE; cornerRadius=28f; setColor(Color.argb(28,255,255,255)) }
    private fun bubble(outgoing:Boolean) = GradientDrawable().apply { shape=GradientDrawable.RECTANGLE; cornerRadius=20f; setColor(if(outgoing) Color.rgb(82,10,41) else Color.rgb(20,20,23)); setStroke(1,Color.argb(if(outgoing) 80 else 45,184,42,101)) }
    private fun mediaSurface() = GradientDrawable().apply { shape=GradientDrawable.RECTANGLE; cornerRadius=18f; setColor(Color.rgb(18,18,21)); setStroke(1,Color.argb(45,132,34,73)) }
    private fun chip() = GradientDrawable().apply { shape=GradientDrawable.RECTANGLE; cornerRadius=28f; setColor(Color.rgb(28,8,18)); setStroke(1,Color.argb(75,170,43,94)) }
    private fun attachmentGlass() = GradientDrawable().apply { shape=GradientDrawable.RECTANGLE; cornerRadii=floatArrayOf(28f,28f,28f,28f,0f,0f,0f,0f); colors=intArrayOf(Color.argb(242,10,10,12),Color.argb(242,38,6,22)); orientation=GradientDrawable.Orientation.TOP_BOTTOM; setStroke(1,Color.argb(70,167,42,91)) }
    private fun homeSurfaceGlass() = GradientDrawable().apply { shape=GradientDrawable.RECTANGLE; colors=intArrayOf(Color.argb(238,8,8,10),Color.argb(228,42,6,24)); orientation=GradientDrawable.Orientation.TOP_BOTTOM; setStroke(1,Color.argb(55,160,38,86)) }
    private fun homeNavigationGlass() = GradientDrawable().apply { shape=GradientDrawable.RECTANGLE; colors=intArrayOf(Color.argb(238,8,8,10),Color.argb(230,55,8,31)); orientation=GradientDrawable.Orientation.TOP_BOTTOM; setStroke(1,Color.argb(70,178,42,96)) }

    private fun find(root: View, idName: String): View? { var result: View? = null; walk(root) { view -> if(result==null && resource(view)==idName) result=view }; return result }
    private fun resource(view: View): String { if(view.id==View.NO_ID)return ""; return try{view.resources.getResourceName(view.id)}catch(_:Throwable){""} }

    private fun walk(root: View, action: (View) -> Unit) {
        action(root)
        if (root is ViewGroup) for (i in 0 until root.childCount) walk(root.getChildAt(i), action)
    }

    companion object {
        private const val TARGET_PACKAGE="com.whatsapp"
        private const val CONVERSATION_ACTIVITY="com.whatsapp.Conversation"
        private const val HOME_ACTIVITY="com.whatsapp.home.ui.HomeActivity"
        private const val TAG_TOOLBAR=0x47570002; private const val TAG_COMPOSER=0x47570003; private const val TAG_INPUT=0x47570004
        private const val TAG_BUBBLE=0x47570005; private const val TAG_MEDIA=0x47570006; private const val TAG_LABEL=0x47570007
        private const val TAG_SHEET=0x47570008; private const val TAG_HOME_SURFACE=0x47570009
        private const val AMOLED=Color.BLACK
    }
}
