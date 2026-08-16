package com.glasswa.core

import android.app.Activity
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * GlassWA — cohesive, presentation-only Liquid Glass layer for WhatsApp.
 *
 * The module deliberately targets stable WhatsApp resource IDs and semantic
 * runtime classes instead of styling arbitrary ViewGroups. No WhatsApp
 * behavior, message data, networking, or input handlers are replaced.
 */
class HookEntry : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != TARGET_PACKAGE) return
        XposedBridge.log("GlassWA: attached to $TARGET_PACKAGE")

        XposedHelpers.findAndHookMethod(Activity::class.java, "onResume", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as? Activity ?: return
                when (activity.javaClass.name) {
                    CONVERSATION_ACTIVITY -> scheduleConversation(activity)
                    HOME_ACTIVITY -> scheduleHome(activity)
                }
            }
        })
    }

    private fun scheduleConversation(activity: Activity) {
        // Handler is created only from a real Activity lifecycle callback.
        val handler = Handler(activity.mainLooper)
        longArrayOf(180L, 550L, 1100L, 2000L, 3500L).forEach { delay ->
            handler.postDelayed({
                if (!activity.isFinishing && !activity.isDestroyed) applyConversation(activity)
            }, delay)
        }
    }

    private fun scheduleHome(activity: Activity) {
        val handler = Handler(activity.mainLooper)
        longArrayOf(180L, 650L, 1300L, 2500L).forEach { delay ->
            handler.postDelayed({
                if (!activity.isFinishing && !activity.isDestroyed) applyHome(activity)
            }, delay)
        }
    }

    private fun applyConversation(activity: Activity) {
        try {
            val root = activity.window?.decorView ?: return
            val density = root.resources.displayMetrics.density
            styleWindow(activity)

            val conversation = find(root, "com.whatsapp:id/conversation_root_layout") ?: root
            val wallpaper = find(root, "com.whatsapp:id/conversation_background")
            val toolbar = find(root, "com.whatsapp:id/toolbar")
            val footer = find(root, "com.whatsapp:id/footer")
            val edit = find(root, "com.whatsapp:id/edit_layout")
            val input = find(root, "com.whatsapp:id/text_entry_layout")
            val conversationLayout = find(root, "com.whatsapp:id/conversation_layout")

            wallpaper?.let { styleWallpaper(it) }
            toolbar?.let { applyBackground(it, toolbarGlass(), TAG_TOOLBAR) }
            footer?.let { transparent(it, TAG_FOOTER) }
            edit?.let { applyBackground(it, composerGlass(), TAG_COMPOSER) }
            input?.let { if (it !== edit) applyBackground(it, innerGlass(), TAG_INPUT) }
            conversationLayout?.let { transparent(it, TAG_LAYOUT) }

            val bubbles = styleMessageSurfaces(conversation, density)
            val media = styleMediaSurfaces(conversation, density)
            val labels = styleConversationLabels(conversation, density)
            val sheets = styleAttachmentSheets(conversation, density)

            XposedBridge.log(
                "GlassWA: conversation glass toolbar=${toolbar != null} composer=${edit != null} " +
                    "bubbles=$bubbles media=$media labels=$labels sheets=$sheets"
            )
        } catch (t: Throwable) {
            XposedBridge.log("GlassWA: conversation failed ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun applyHome(activity: Activity) {
        try {
            val root = activity.window?.decorView ?: return
            val density = root.resources.displayMetrics.density
            styleWindow(activity)

            val main = find(root, "com.whatsapp:id/main_container")
            val protection = find(root, "com.whatsapp:id/navigation_bar_protection")
            main?.let { transparent(it, TAG_HOME_ROOT) }
            protection?.let { transparent(it, TAG_HOME_PROTECTION) }

            val surfaces = styleHomeSurfaces(main ?: root, density)
            XposedBridge.log("GlassWA: home glass surfaces=$surfaces main=${main != null}")
        } catch (t: Throwable) {
            XposedBridge.log("GlassWA: home failed ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun styleWindow(activity: Activity) {
        val window = activity.window ?: return
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        // Do not force edge-to-edge. WhatsApp owns its insets and forcing them
        // here was a source of the top/bottom alignment glitches.
    }

    private fun styleWallpaper(view: View) {
        if (Build.VERSION.SDK_INT < 31 || view.getTag(TAG_WALLPAPER) == true) return
        // The wallpaper is the visual source underneath the glass. Keep the
        // blur deliberately low: high-radius blur makes wallpaper look smeared
        // and does not create a convincing glass surface.
        view.setRenderEffect(RenderEffect.createBlurEffect(1.6f, 1.6f, Shader.TileMode.CLAMP))
        view.setTag(TAG_WALLPAPER, true)
        view.invalidate()
    }

    private fun styleMessageSurfaces(root: View, density: Float): Int {
        var count = 0
        walk(root) { view ->
            if (view.getTag(TAG_BUBBLE) == true) return@walk
            if (view.visibility != View.VISIBLE || view.width <= 0 || view.height <= 0) return@walk
            if (resource(view) != "com.whatsapp:id/conversation_text_row") return@walk

            val pos = IntArray(2)
            view.getLocationOnScreen(pos)
            val screenWidth = view.resources.displayMetrics.widthPixels
            val center = pos[0] + view.width / 2f
            val outgoing = center > screenWidth * 0.52f

            view.background = messageGlass(outgoing)
            view.setPadding(
                (10f * density).toInt(),
                (5f * density).toInt(),
                (10f * density).toInt(),
                (5f * density).toInt()
            )
            view.elevation = 1.5f * density
            view.setTag(TAG_BUBBLE, true)
            view.invalidate()
            count++
        }
        return count
    }

    private fun styleMediaSurfaces(root: View, density: Float): Int {
        var count = 0
        walk(root) { view ->
            if (view.getTag(TAG_MEDIA) == true) return@walk
            val id = resource(view)
            if (id != "com.whatsapp:id/media_container" && id != "com.whatsapp:id/text_and_date") return@walk
            if (view.width <= 0 || view.height <= 0) return@walk
            view.background = mediaGlass()
            view.elevation = 1.5f * density
            view.setTag(TAG_MEDIA, true)
            view.invalidate()
            count++
        }
        return count
    }

    private fun styleConversationLabels(root: View, density: Float): Int {
        var count = 0
        walk(root) { view ->
            val text = view as? TextView ?: return@walk
            if (text.getTag(TAG_TEXT) == true) return@walk
            val value = text.text?.toString()?.trim()?.lowercase() ?: return@walk

            if (value == "today" || value == "yesterday") {
                val card = nearestCompactParent(text, density, 0.90f, 160f)
                if (card != null) {
                    card.background = dayGlass()
                    card.elevation = 3f * density
                    card.setTag(TAG_TEXT, true)
                    count++
                }
                return@walk
            }

            if (value.contains("messages to yourself are end-to-end encrypted")) {
                val card = nearestCompactParent(text, density, 0.90f, 180f)
                if (card != null) {
                    card.background = infoGlass()
                    card.elevation = 3f * density
                    card.setTag(TAG_TEXT, true)
                    count++
                }
            }
        }
        return count
    }

    private fun nearestCompactParent(text: TextView, density: Float, maxWidthFraction: Float, maxHeightDp: Float): View? {
        var parent = text.parent as? View
        val screenWidth = text.resources.displayMetrics.widthPixels
        val maxHeight = (maxHeightDp * density).toInt()
        repeat(5) {
            if (parent == null) return null
            if (parent.width > 0 && parent.height > 0 &&
                parent.width <= screenWidth * maxWidthFraction && parent.height <= maxHeight &&
                parent.height >= 28 * density) return parent
            parent = parent.parent as? View
        }
        return null
    }

    private fun styleAttachmentSheets(root: View, density: Float): Int {
        var count = 0
        val h = root.resources.displayMetrics.heightPixels
        val w = root.resources.displayMetrics.widthPixels
        walk(root) { view ->
            if (view.getTag(TAG_SHEET) == true) return@walk
            val group = view as? ViewGroup ?: return@walk
            if (view.visibility != View.VISIBLE || view.width <= 0 || view.height <= 0) return@walk
            if (view.width < w * 0.78f || view.height < h * 0.20f || view.height > h * 0.72f) return@walk
            if (view.top < h * 0.42f) return@walk

            val name = view.javaClass.name.lowercase()
            val res = resource(view).lowercase()
            val named = name.contains("bottomsheet") || name.contains("attachmentsheet") ||
                name.contains("mediasheet") || name.contains("gallerypicker") ||
                res.contains("bottom_sheet") || res.contains("attachment") || res.contains("media_picker")
            if (!named && group.childCount < 5) return@walk

            view.background = sheetGlass()
            view.elevation = 8f * density
            view.setTag(TAG_SHEET, true)
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i)
                if (child is ViewGroup && child.width > 50 * density && child.height > 34 * density && child.height < 120 * density) {
                    child.background = sheetItemGlass()
                    child.setTag(TAG_SHEET_ITEM, true)
                }
            }
            count++
        }
        return count
    }

    private fun styleHomeSurfaces(root: View, density: Float): Int {
        var count = 0
        walk(root) { view ->
            if (view is android.view.ViewStub) return@walk
            if (view.getTag(TAG_HOME_SURFACE) == true) return@walk
            if (view.visibility != View.VISIBLE || view.width <= 0 || view.height <= 0) return@walk

            val name = view.javaClass.name.lowercase()
            val res = resource(view).lowercase()
            val semantic = name.contains("wdstoolbar") || name.contains("topbar") ||
                name.contains("searchview") || name.contains("search_view") ||
                name.contains("bottomnavigation") || name.contains("navigationbar") ||
                name.contains("navigationrail") || name.contains("tabbar") ||
                res.contains("toolbar") || res.contains("search") ||
                res.contains("bottom_navigation") || res.contains("navigation_rail")
            if (!semantic) return@walk
            if (res.contains("navigation_bar_protection")) return@walk

            val bottom = view.top > root.height * 0.70f
            val top = view.top < root.height * 0.20f
            if (!bottom && !top && !name.contains("wdstoolbar") && !name.contains("topbar")) return@walk

            view.background = if (bottom) homeBottomGlass() else homeTopGlass()
            view.elevation = if (bottom) 7f * density else 4f * density
            view.setTag(TAG_HOME_SURFACE, true)
            view.invalidate()
            count++
        }
        return count
    }

    private fun applyBackground(view: View, drawable: GradientDrawable, tag: Int) {
        if (view.getTag(tag) == true) return
        view.background = drawable
        view.setTag(tag, true)
        view.invalidate()
    }

    private fun transparent(view: View, tag: Int) {
        if (view.getTag(tag) == true) return
        view.setBackgroundColor(Color.TRANSPARENT)
        view.setTag(tag, true)
    }

    private fun toolbarGlass() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        colors = intArrayOf(Color.argb(105, 30, 38, 43), Color.argb(58, 18, 24, 28))
        orientation = GradientDrawable.Orientation.TOP_BOTTOM
        setStroke(1, Color.argb(38, 255, 255, 255))
    }

    private fun composerGlass() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 34f
        colors = intArrayOf(Color.argb(112, 45, 57, 62), Color.argb(72, 22, 29, 34))
        orientation = GradientDrawable.Orientation.TL_BR
        setStroke(1, Color.argb(55, 255, 255, 255))
    }

    private fun innerGlass() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 28f
        setColor(Color.argb(22, 255, 255, 255))
    }

    private fun messageGlass(outgoing: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 20f
        colors = if (outgoing) {
            intArrayOf(Color.argb(145, 220, 65, 132), Color.argb(100, 164, 42, 104))
        } else {
            intArrayOf(Color.argb(105, 55, 67, 73), Color.argb(68, 28, 37, 42))
        }
        orientation = GradientDrawable.Orientation.TL_BR
        setStroke(1, Color.argb(42, 255, 255, 255))
    }

    private fun mediaGlass() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 18f
        setColor(Color.argb(40, 255, 255, 255))
        setStroke(1, Color.argb(45, 255, 255, 255))
    }

    private fun sheetGlass() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadii = floatArrayOf(28f, 28f, 28f, 28f, 0f, 0f, 0f, 0f)
        colors = intArrayOf(Color.argb(190, 22, 29, 34), Color.argb(165, 14, 20, 24))
        orientation = GradientDrawable.Orientation.TOP_BOTTOM
        setStroke(1, Color.argb(45, 255, 255, 255))
    }

    private fun sheetItemGlass() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 26f
        setColor(Color.argb(22, 255, 255, 255))
        setStroke(1, Color.argb(35, 255, 255, 255))
    }

    private fun dayGlass() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 30f
        setColor(Color.argb(120, 26, 34, 39))
        setStroke(1, Color.argb(38, 255, 255, 255))
    }

    private fun infoGlass() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 20f
        colors = intArrayOf(Color.argb(145, 30, 38, 43), Color.argb(105, 18, 24, 29))
        orientation = GradientDrawable.Orientation.TL_BR
        setStroke(1, Color.argb(42, 255, 255, 255))
    }

    private fun homeTopGlass() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        colors = intArrayOf(Color.argb(120, 25, 32, 37), Color.argb(68, 17, 22, 26))
        orientation = GradientDrawable.Orientation.TOP_BOTTOM
        setStroke(1, Color.argb(35, 255, 255, 255))
    }

    private fun homeBottomGlass() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 34f
        colors = intArrayOf(Color.argb(125, 28, 36, 41), Color.argb(88, 16, 22, 27))
        orientation = GradientDrawable.Orientation.TL_BR
        setStroke(1, Color.argb(45, 255, 255, 255))
    }

    private fun walk(view: View, visitor: (View) -> Unit) {
        visitor(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i), visitor)
    }

    private fun find(root: View, idName: String): View? {
        if (root.id != View.NO_ID) {
            try { if (root.resources.getResourceName(root.id) == idName) return root } catch (_: Throwable) { }
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                find(root.getChildAt(i), idName)?.let { return it }
            }
        }
        return null
    }

    private fun resource(view: View): String = if (view.id == View.NO_ID) "" else try {
        view.resources.getResourceName(view.id)
    } catch (_: Throwable) { "" }

    companion object {
        const val TARGET_PACKAGE = "com.whatsapp"
        const val CONVERSATION_ACTIVITY = "com.whatsapp.Conversation"
        const val HOME_ACTIVITY = "com.whatsapp.home.ui.HomeActivity"

        private const val TAG_TOOLBAR = 0x47574110
        private const val TAG_COMPOSER = 0x47574111
        private const val TAG_INPUT = 0x47574112
        private const val TAG_FOOTER = 0x47574113
        private const val TAG_WALLPAPER = 0x47574114
        private const val TAG_BUBBLE = 0x47574115
        private const val TAG_SHEET = 0x47574116
        private const val TAG_SHEET_ITEM = 0x47574117
        private const val TAG_TEXT = 0x47574118
        private const val TAG_LAYOUT = 0x47574119
        private const val TAG_MEDIA = 0x4757411A
        private const val TAG_HOME_ROOT = 0x47574120
        private const val TAG_HOME_PROTECTION = 0x47574121
        private const val TAG_HOME_SURFACE = 0x47574122
    }
}
