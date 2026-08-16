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
import android.view.ViewOutlineProvider
import android.widget.TextView
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/** GlassWA presentation-only Conversation redesign. */
class HookEntry : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != TARGET_PACKAGE) return
        XposedBridge.log("GlassWA: attached to $TARGET_PACKAGE")

        XposedHelpers.findAndHookMethod(Activity::class.java, "onResume", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as? Activity ?: return
                if (activity.javaClass.name != CONVERSATION_ACTIVITY) return
                schedule(activity)
            }
        })
    }

    private fun schedule(activity: Activity) {
        // Never create a Handler during Xposed/Zygote construction. The main
        // MessageQueue may not exist there. Create it only after Activity.onResume.
        val handler = Handler(activity.mainLooper)
        longArrayOf(200L, 600L, 1200L, 2200L, 4000L).forEach { delay ->
            handler.postDelayed({
                if (!activity.isFinishing && !activity.isDestroyed) applyConversation(activity)
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

            wallpaper?.let { blurWallpaper(it) }
            toolbar?.let { applyGlass(it, toolbarGlass(), TAG_TOOLBAR) }
            footer?.let { transparentOnce(it, TAG_FOOTER) }
            edit?.let { applyGlass(it, composerGlass(), TAG_COMPOSER) }
            input?.let { if (it !== edit) applyGlass(it, innerGlass(), TAG_INPUT) }
            conversationLayout?.let { transparentOnce(it, TAG_LAYOUT) }

            val bubbles = styleBubbles(conversation, density)
            val sheets = styleSheets(conversation, density)
            val labels = styleLabels(conversation, density)
            XposedBridge.log("GlassWA: conversation overhaul toolbar=${toolbar != null} composer=${edit != null} bubbles=$bubbles sheets=$sheets labels=$labels")
        } catch (t: Throwable) {
            XposedBridge.log("GlassWA: conversation overhaul failed ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun styleWindow(activity: Activity) {
        val window = activity.window ?: return
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
    }

    private fun blurWallpaper(view: View) {
        if (Build.VERSION.SDK_INT >= 31 && view.getTag(TAG_WALLPAPER) != true) {
            view.setRenderEffect(RenderEffect.createBlurEffect(4f, 4f, Shader.TileMode.CLAMP))
            view.setTag(TAG_WALLPAPER, true)
        }
    }

    private fun styleBubbles(root: View, density: Float): Int {
        var count = 0
        walk(root) { view ->
            if (view.getTag(TAG_BUBBLE) == true || view is TextView || view === root) return@walk
            if (view.visibility != View.VISIBLE || view.width <= 0 || view.height <= 0) return@walk
            val name = view.javaClass.name.lowercase()
            val res = resource(view).lowercase()
            val bubble = name.contains("messagebubble") || name.contains("conversationbubble") ||
                name.contains("bubbleview") || (name.contains("message") && name.contains("bubble")) ||
                res.contains("message_bubble") || res.contains("bubble")
            if (!bubble) return@walk
            view.background = bubbleGlass(outgoing = isOutgoing(view))
            view.elevation = 2f * density
            view.outlineProvider = ViewOutlineProvider.BACKGROUND
            view.clipToOutline = true
            view.setTag(TAG_BUBBLE, true)
            view.invalidate()
            count++
        }
        return count
    }

    private fun isOutgoing(view: View): Boolean = (view.background?.alpha ?: 255) < 250

    private fun styleSheets(root: View, density: Float): Int {
        var count = 0
        val h = root.resources.displayMetrics.heightPixels
        val w = root.resources.displayMetrics.widthPixels
        walk(root) { view ->
            if (view.getTag(TAG_SHEET) == true) return@walk
            val group = view as? ViewGroup ?: return@walk
            if (view.visibility != View.VISIBLE || view.width <= 0 || view.height <= 0) return@walk
            if (view.width < w * .72f || view.height < h * .18f || view.height > h * .80f || view.top < h * .48f) return@walk
            val name = view.javaClass.name.lowercase()
            val res = resource(view).lowercase()
            val named = name.contains("bottomsheet") || name.contains("attachmentsheet") || name.contains("mediasheet") ||
                name.contains("gallerypicker") || res.contains("bottom_sheet") || res.contains("attachment") || res.contains("media_picker")
            if (!named && group.childCount < 4) return@walk
            applyGlass(view, sheetGlass(), TAG_SHEET)
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i)
                if (child.width > 55 * density && child.width < 190 * density && child.height > 38 * density && child.height < 110 * density) {
                    child.background = pillGlass()
                    child.setTag(TAG_CHIP, true)
                }
            }
            count++
        }
        return count
    }

    private fun styleLabels(root: View, density: Float): Int {
        var count = 0
        walk(root) { view ->
            val text = view as? TextView ?: return@walk
            if (text.getTag(TAG_LABEL) == true) return@walk
            val value = text.text?.toString()?.trim()?.lowercase() ?: return@walk
            if (value != "today" && value != "yesterday" && !value.contains("messages to yourself are end-to-end encrypted")) return@walk
            val parent = text.parent as? View ?: return@walk
            if (parent.width <= 0 || parent.height <= 0) return@walk
            parent.background = if (value == "today" || value == "yesterday") dayGlass() else infoGlass()
            parent.elevation = 4f * density
            parent.setTag(TAG_LABEL, true)
            count++
        }
        return count
    }

    private fun applyGlass(view: View, drawable: GradientDrawable, tag: Int) {
        if (view.getTag(tag) == true) return
        view.background = drawable
        view.outlineProvider = ViewOutlineProvider.BACKGROUND
        view.clipToOutline = true
        view.setTag(tag, true)
        view.invalidate()
    }

    private fun transparentOnce(view: View, tag: Int) {
        if (view.getTag(tag) == true) return
        view.setBackgroundColor(Color.TRANSPARENT)
        view.setTag(tag, true)
    }

    private fun composerGlass() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; cornerRadius = 34f
        colors = intArrayOf(Color.argb(145, 40, 51, 57), Color.argb(92, 22, 29, 34))
        orientation = GradientDrawable.Orientation.TL_BR
        setStroke(1, Color.argb(120, 255, 255, 255))
    }

    private fun toolbarGlass() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        colors = intArrayOf(Color.argb(115, 28, 34, 39), Color.argb(72, 15, 19, 23))
        orientation = GradientDrawable.Orientation.TOP_BOTTOM
        setStroke(1, Color.argb(80, 255, 255, 255))
    }

    private fun innerGlass() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; cornerRadius = 28f
        setColor(Color.argb(35, 255, 255, 255))
        setStroke(1, Color.argb(55, 255, 255, 255))
    }

    private fun bubbleGlass(outgoing: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; cornerRadius = 22f
        colors = if (outgoing) intArrayOf(Color.argb(180, 230, 48, 124), Color.argb(135, 169, 31, 99))
        else intArrayOf(Color.argb(125, 53, 63, 69), Color.argb(82, 25, 32, 38))
        orientation = GradientDrawable.Orientation.TL_BR
        setStroke(1, Color.argb(85, 255, 255, 255))
    }

    private fun sheetGlass() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadii = floatArrayOf(30f, 30f, 30f, 30f, 0f, 0f, 0f, 0f)
        colors = intArrayOf(Color.argb(225, 20, 27, 32), Color.argb(190, 14, 19, 23))
        orientation = GradientDrawable.Orientation.TOP_BOTTOM
        setStroke(1, Color.argb(105, 255, 255, 255))
    }

    private fun pillGlass() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; cornerRadius = 30f
        setColor(Color.argb(30, 255, 255, 255)); setStroke(1, Color.argb(70, 255, 255, 255))
    }

    private fun dayGlass() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; cornerRadius = 40f
        setColor(Color.argb(150, 25, 32, 38)); setStroke(1, Color.argb(80, 255, 255, 255))
    }

    private fun infoGlass() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; cornerRadius = 22f
        colors = intArrayOf(Color.argb(160, 29, 36, 42), Color.argb(120, 18, 24, 29))
        orientation = GradientDrawable.Orientation.TL_BR
        setStroke(1, Color.argb(70, 255, 255, 255))
    }

    private fun walk(view: View, visitor: (View) -> Unit) {
        visitor(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i), visitor)
    }

    private fun find(root: View, idName: String): View? {
        if (root.id != View.NO_ID) {
            try { if (root.resources.getResourceName(root.id) == idName) return root } catch (_: Throwable) { }
        }
        if (root is ViewGroup) for (i in 0 until root.childCount) find(root.getChildAt(i), idName)?.let { return it }
        return null
    }

    private fun resource(view: View): String = if (view.id == View.NO_ID) "" else try { view.resources.getResourceName(view.id) } catch (_: Throwable) { "" }

    companion object {
        const val TARGET_PACKAGE = "com.whatsapp"
        const val CONVERSATION_ACTIVITY = "com.whatsapp.Conversation"
        private const val TAG_TOOLBAR = 0x47574110
        private const val TAG_COMPOSER = 0x47574111
        private const val TAG_INPUT = 0x47574112
        private const val TAG_FOOTER = 0x47574113
        private const val TAG_WALLPAPER = 0x47574114
        private const val TAG_BUBBLE = 0x47574115
        private const val TAG_SHEET = 0x47574116
        private const val TAG_CHIP = 0x47574117
        private const val TAG_LABEL = 0x47574118
        private const val TAG_LAYOUT = 0x47574119
    }
}
