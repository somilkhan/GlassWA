package com.glasswa.core

import android.app.Activity
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.TextView
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * GlassWA — cohesive Conversation-page presentation layer.
 *
 * This module does not replace WhatsApp behavior. It only changes view
 * presentation: backgrounds, blur, elevation, clipping and system bars.
 */
class HookEntry : IXposedHookLoadPackage {
    private val handler = Handler(Looper.getMainLooper())

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
        // WhatsApp inflates the conversation footer, media sheet and message
        // rows asynchronously. A finite refresh window catches those states
        // without leaving a polling loop running forever.
        longArrayOf(250L, 700L, 1400L, 2400L, 4000L).forEach { delay ->
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

            val conversation = findByName(root, "com.whatsapp:id/conversation_root_layout") ?: root
            val wallpaper = findByName(root, "com.whatsapp:id/conversation_background")
            val toolbar = findByName(root, "com.whatsapp:id/toolbar")
            val footer = findByName(root, "com.whatsapp:id/footer")
            val editLayout = findByName(root, "com.whatsapp:id/edit_layout")
            val inputLayout = findByName(root, "com.whatsapp:id/text_entry_layout")
            val keyboardLayout = findByName(root, "com.whatsapp:id/conversation_layout")

            wallpaper?.let { styleWallpaper(it) }
            toolbar?.let { glassSurface(it, toolbarDrawable(), 0f, density, TAG_TOOLBAR) }

            footer?.let {
                // The footer itself stays transparent; the inner composer is
                // the glass capsule. This prevents a gray rectangle around it.
                if (it.getTag(TAG_FOOTER) != true) {
                    it.setBackgroundColor(Color.TRANSPARENT)
                    it.setTag(TAG_FOOTER, true)
                }
            }

            editLayout?.let { glassSurface(it, composerDrawable(), 0f, density, TAG_COMPOSER) }
            inputLayout?.let {
                if (it !== editLayout) glassSurface(it, innerDrawable(), 0f, density, TAG_INPUT)
            }

            // The keyboard/conversation container remains behaviorally intact,
            // but gets a transparent base so our surfaces can float over it.
            keyboardLayout?.let {
                if (it.getTag(TAG_LAYOUT) != true) {
                    it.setBackgroundColor(Color.TRANSPARENT)
                    it.setTag(TAG_LAYOUT, true)
                }
            }

            val bubbles = styleBubbleCandidates(conversation, density)
            val sheets = styleBottomSheets(conversation, density)
            val chips = styleConversationLabels(conversation, density)

            XposedBridge.log(
                "GlassWA: conversation overhaul toolbar=${toolbar != null} " +
                    "composer=${editLayout != null} bubbles=$bubbles sheets=$sheets labels=$chips"
            )
        } catch (t: Throwable) {
            XposedBridge.log("GlassWA: conversation overhaul failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun styleWindow(activity: Activity) {
        val window = activity.window ?: return
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        }
    }

    private fun styleWallpaper(view: View) {
        if (Build.VERSION.SDK_INT < 31 || view.getTag(TAG_WALLPAPER) == true) return
        view.setRenderEffect(RenderEffect.createBlurEffect(4.0f, 4.0f, Shader.TileMode.CLAMP))
        view.setTag(TAG_WALLPAPER, true)
    }

    /** Style actual bubble containers without touching message content/views. */
    private fun styleBubbleCandidates(root: View, density: Float): Int {
        var count = 0
        val screenWidth = root.resources.displayMetrics.widthPixels
        val screenHeight = root.resources.displayMetrics.heightPixels
        walk(root) { view ->
            if (view.getTag(TAG_BUBBLE) == true) return@walk
            if (view === root || view is TextView) return@walk
            if (view.visibility != View.VISIBLE || view.width <= 0 || view.height <= 0) return@walk

            val name = view.javaClass.name.lowercase()
            val resource = resourceName(view).lowercase()
            val semantic = isBubbleClass(name) || resource.contains("message_bubble") || resource.contains("bubble")
            val geometry = view.width in (screenWidth * 0.25f).toInt()..(screenWidth * 0.90f).toInt() &&
                view.height in (28 * density).toInt()..(210 * density).toInt() &&
                view.top < screenHeight * 0.88f
            if (!semantic && !geometry) return@walk
            if (resource.contains("composer") || resource.contains("edit_layout")) return@walk

            // Require a semantically named class for generic geometry matches;
            // this keeps arbitrary message-row containers from becoming glass.
            if (!semantic) return@walk
            view.background = bubbleDrawable(isOutgoing(view))
            view.elevation = 2f * density
            view.outlineProvider = ViewOutlineProvider.BACKGROUND
            view.clipToOutline = true
            view.setTag(TAG_BUBBLE, true)
            count++
        }
        return count
    }

    private fun isBubbleClass(name: String): Boolean {
        return name.contains("messagebubble") ||
            name.contains("conversationbubble") ||
            name.contains("bubbleview") ||
            (name.contains("bubble") && name.contains("message"))
    }

    private fun isOutgoing(view: View): Boolean {
        // Presentation-only heuristic: preserve WhatsApp's existing visual
        // distinction by looking at the current background when available.
        val alpha = view.background?.alpha ?: 255
        return alpha < 250
    }

    /**
     * Find WhatsApp's attachment/media sheet without relying on an obfuscated
     * resource ID. The sheet is a large bottom-anchored surface; the message
     * list and composer do not satisfy this combination of constraints.
     */
    private fun styleBottomSheets(root: View, density: Float): Int {
        var count = 0
        val screenHeight = root.resources.displayMetrics.heightPixels
        val screenWidth = root.resources.displayMetrics.widthPixels
        walk(root) { view ->
            if (view.getTag(TAG_SHEET) == true) return@walk
            val group = view as? ViewGroup ?: return@walk
            if (view.visibility != View.VISIBLE || view.width <= 0 || view.height <= 0) return@walk
            if (view.width < screenWidth * 0.72f || view.height < screenHeight * 0.18f) return@walk
            if (view.top < screenHeight * 0.48f) return@walk
            if (view.height > screenHeight * 0.80f) return@walk

            val name = view.javaClass.name.lowercase()
            val resource = resourceName(view).lowercase()
            val named = name.contains("bottomsheet") || name.contains("attachmentsheet") ||
                name.contains("mediasheet") || name.contains("gallerypicker") ||
                resource.contains("bottom_sheet") || resource.contains("attachment") ||
                resource.contains("media_picker")
            val populated = group.childCount >= 4
            if (!named && !populated) return@walk
            if (view === findByName(root, "com.whatsapp:id/footer")) return@walk

            glassSurface(view, sheetDrawable(), 10f, density, TAG_SHEET)
            styleSheetChildren(group, density)
            count++
        }
        return count
    }

    private fun styleSheetChildren(sheet: ViewGroup, density: Float) {
        for (i in 0 until sheet.childCount) {
            val child = sheet.getChildAt(i)
            if (child.getTag(TAG_CHIP) == true) continue
            val h = child.height
            val w = child.width
            if (w > 55 * density && w < 190 * density && h > 38 * density && h < 110 * density) {
                child.background = pillDrawable()
                child.setTag(TAG_CHIP, true)
                child.invalidate()
            }
        }
    }

    /** Style the day chip and encryption/info card as floating glass labels. */
    private fun styleConversationLabels(root: View, density: Float): Int {
        var count = 0
        walk(root) { view ->
            val textView = view as? TextView ?: return@walk
            if (textView.getTag(TAG_LABEL) == true) return@walk
            val text = textView.text?.toString()?.trim()?.lowercase() ?: return@walk
            if (text == "today" || text == "yesterday" || text == "messages to yourself are end-to-end encrypted." ||
                text.contains("messages to yourself are end-to-end encrypted")) {
                val parent = textView.parent as? View ?: return@walk
                if (parent.width <= 0 || parent.height <= 0) return@walk
                parent.background = if (text == "today" || text == "yesterday") dayPillDrawable() else infoDrawable()
                parent.elevation = 5f * density
                parent.setTag(TAG_LABEL, true)
                count++
            }
        }
        return count
    }

    private fun glassSurface(view: View, drawable: GradientDrawable, elevationDp: Float, density: Float, tag: Int) {
        if (view.getTag(tag) == true) return
        view.background = drawable
        if (elevationDp > 0f) view.elevation = elevationDp * density
        view.outlineProvider = ViewOutlineProvider.BACKGROUND
        view.clipToOutline = true
        view.setTag(tag, true)
        view.invalidate()
    }

    private fun composerDrawable() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 32f
        colors = intArrayOf(Color.argb(120, 48, 58, 63), Color.argb(88, 24, 31, 36))
        orientation = GradientDrawable.Orientation.TL_BR
        setStroke(1, Color.argb(115, 255, 255, 255))
    }

    private fun toolbarDrawable() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        colors = intArrayOf(Color.argb(105, 24, 29, 33), Color.argb(70, 17, 21, 24))
        orientation = GradientDrawable.Orientation.TOP_BOTTOM
        setStroke(1, Color.argb(75, 255, 255, 255))
    }

    private fun innerDrawable() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 28f
        setColor(Color.argb(28, 255, 255, 255))
    }

    private fun sheetDrawable() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadii = floatArrayOf(28f, 28f, 28f, 28f, 0f, 0f, 0f, 0f)
        colors = intArrayOf(Color.argb(215, 21, 27, 31), Color.argb(182, 16, 21, 25))
        orientation = GradientDrawable.Orientation.TOP_BOTTOM
        setStroke(1, Color.argb(100, 255, 255, 255))
    }

    private fun pillDrawable() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 28f
        setColor(Color.argb(26, 255, 255, 255))
        setStroke(1, Color.argb(65, 255, 255, 255))
    }

    private fun dayPillDrawable() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 40f
        setColor(Color.argb(145, 25, 32, 37))
        setStroke(1, Color.argb(75, 255, 255, 255))
    }

    private fun infoDrawable() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 22f
        colors = intArrayOf(Color.argb(155, 28, 35, 40), Color.argb(120, 20, 26, 30))
        orientation = GradientDrawable.Orientation.TL_BR
        setStroke(1, Color.argb(65, 255, 255, 255))
    }

    private fun bubbleDrawable(outgoing: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 22f
        if (outgoing) {
            colors = intArrayOf(Color.argb(170, 232, 48, 125), Color.argb(135, 171, 32, 101))
        } else {
            colors = intArrayOf(Color.argb(112, 47, 57, 63), Color.argb(82, 27, 34, 39))
        }
        orientation = GradientDrawable.Orientation.TL_BR
        setStroke(1, Color.argb(75, 255, 255, 255))
    }

    private fun walk(root: View, visitor: (View) -> Unit) {
        visitor(root)
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) walk(root.getChildAt(i), visitor)
        }
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

    private fun resourceName(view: View): String {
        if (view.id == View.NO_ID) return ""
        return try { view.resources.getResourceName(view.id) } catch (_: Throwable) { "" }
    }

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
