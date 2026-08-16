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
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * GlassWA conversation redesign.
 *
 * The module intentionally changes only presentation properties: backgrounds,
 * elevation, clipping, window bars and supported RenderEffects. No click,
 * input, adapter, message or networking behavior is replaced.
 */
class HookEntry : IXposedHookLoadPackage {
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != TARGET_PACKAGE) return
        isLoaded = true
        XposedBridge.log("GlassWA: attached to $TARGET_PACKAGE")

        XposedHelpers.findAndHookMethod(Activity::class.java, "onResume", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as? Activity ?: return
                if (activity.javaClass.name != CONVERSATION_ACTIVITY) return
                scheduleConversationRefresh(activity)
            }
        })
    }

    private fun scheduleConversationRefresh(activity: Activity) {
        // WhatsApp inflates parts of Conversation through ViewStubs after resume.
        // A short bounded refresh window catches those views without keeping a
        // permanent polling loop alive.
        val delays = longArrayOf(350L, 900L, 1600L, 2800L, 4500L)
        delays.forEach { delay ->
            mainHandler.postDelayed({
                if (!activity.isFinishing && !activity.isDestroyed) {
                    applyConversationGlass(activity)
                }
            }, delay)
        }
    }

    private fun applyConversationGlass(activity: Activity) {
        try {
            val root = activity.window?.decorView ?: return
            val density = root.resources.displayMetrics.density

            styleWindow(activity)

            val conversationRoot = findByName(root, "com.whatsapp:id/conversation_root_layout")
            val wallpaper = findByName(root, "com.whatsapp:id/conversation_background")
            val toolbar = findByName(root, "com.whatsapp:id/toolbar")
            val coordinator = findByName(root, "com.whatsapp:id/coordinator")
            val footer = findByName(root, "com.whatsapp:id/footer")
            val editLayout = findByName(root, "com.whatsapp:id/edit_layout")
            val inputLayout = findByName(root, "com.whatsapp:id/text_entry_layout")

            // Keep the actual wallpaper visible, but soften it so translucent
            // foreground surfaces read as glass instead of flat gray cards.
            wallpaper?.let { styleWallpaper(it) }

            toolbar?.let {
                applySurface(it, toolbarGlassDrawable(), 2.5f, density)
                it.setTag(TAG_TOOLBAR, true)
            }

            footer?.let {
                it.setBackgroundColor(Color.TRANSPARENT)
                it.setTag(TAG_STYLED, true)
            }

            editLayout?.let {
                applySurface(it, composerGlassDrawable(), 5f, density)
                it.setTag(TAG_COMPOSER, true)
            }

            inputLayout?.let {
                // Only add a subtle inner glass layer when this container is
                // separate from edit_layout; input behavior remains untouched.
                if (it !== editLayout) {
                    applySurface(it, innerGlassDrawable(), 0f, density)
                }
            }

            // Style only views whose runtime class strongly indicates a bubble.
            // This avoids guessing resource IDs in WhatsApp's obfuscated WDS tree.
            val bubbleCount = styleBubbleCandidates(conversationRoot ?: root, density)

            // A very light tint on the conversation container prevents the
            // wallpaper from overpowering text while preserving its pattern.
            conversationRoot?.let {
                if (it.background == null) it.background = conversationWashDrawable()
            }

            XposedBridge.log(
                "GlassWA: conversation glass applied " +
                    "toolbar=${toolbar != null} wallpaper=${wallpaper != null} " +
                    "composer=${editLayout != null} bubbles=$bubbleCount"
            )
        } catch (t: Throwable) {
            XposedBridge.log("GlassWA: conversation glass failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun styleWindow(activity: Activity) {
        val window = activity.window ?: return
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 30) {
            val controller = window.insetsController
            if (controller != null) {
                // Keep WhatsApp's light/dark icon decision; GlassWA only makes
                // the surfaces transparent so the glass layers can meet the bars.
                controller.setSystemBarsAppearance(
                    controller.systemBarsAppearance,
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                        android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                )
            }
        }
    }

    private fun styleWallpaper(view: View) {
        if (Build.VERSION.SDK_INT >= 31 && view.getTag(TAG_WALLPAPER) != true) {
            // RenderEffect operates on the View's RenderNode. Applying it to the
            // wallpaper itself gives the translucent foreground surfaces a
            // convincing frosted backdrop without expensive bitmap snapshots.
            view.setRenderEffect(
                RenderEffect.createBlurEffect(
                    5.5f,
                    5.5f,
                    Shader.TileMode.CLAMP
                )
            )
            view.setTag(TAG_WALLPAPER, true)
        }
    }

    private fun styleBubbleCandidates(root: View, density: Float): Int {
        var count = 0
        walk(root) { view ->
            if (view.getTag(TAG_BUBBLE) == true) return@walk
            val name = view.javaClass.name.lowercase()
            if (!isBubbleClass(name)) return@walk
            if (view.width <= 0 || view.height <= 0) return@walk
            if (view === root) return@walk

            // Do not replace a view's click/input semantics. We only replace
            // its presentation background and outline.
            view.background = bubbleGlassDrawable(isOutgoingCandidate(view))
            view.elevation = 2f * density
            view.outlineProvider = ViewOutlineProvider.BACKGROUND
            view.clipToOutline = true
            view.setTag(TAG_BUBBLE, true)
            count++
        }
        return count
    }

    private fun isBubbleClass(name: String): Boolean {
        return (name.contains("bubble") &&
            (name.contains("message") || name.contains("conversation") || name.contains("chat"))) ||
            name.contains("conversationmessageview") ||
            name.contains("messagebubbleview")
    }

    private fun isOutgoingCandidate(view: View): Boolean {
        // Avoid content inspection. WhatsApp's own child/background hierarchy
        // remains authoritative; this simply gives likely outgoing bubbles a
        // slightly stronger accent when their existing background is non-null.
        return view.background != null && view.background.alpha < 245
    }

    private fun applySurface(view: View, drawable: GradientDrawable, elevationDp: Float, density: Float) {
        view.background = drawable
        if (elevationDp > 0f) view.elevation = elevationDp * density
        view.outlineProvider = ViewOutlineProvider.BACKGROUND
        view.clipToOutline = true
        view.invalidate()
    }

    private fun composerGlassDrawable() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 30f
        colors = intArrayOf(
            Color.argb(178, 35, 42, 47),
            Color.argb(150, 25, 31, 36)
        )
        orientation = GradientDrawable.Orientation.TL_BR
        setStroke(1, Color.argb(105, 255, 255, 255))
    }

    private fun toolbarGlassDrawable() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        colors = intArrayOf(
            Color.argb(170, 28, 31, 35),
            Color.argb(125, 20, 22, 25)
        )
        orientation = GradientDrawable.Orientation.TL_BR
        setStroke(1, Color.argb(70, 255, 255, 255))
    }

    private fun innerGlassDrawable() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 24f
        setColor(Color.argb(32, 255, 255, 255))
    }

    private fun bubbleGlassDrawable(outgoing: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 22f
        if (outgoing) {
            colors = intArrayOf(
                Color.argb(215, 215, 38, 115),
                Color.argb(190, 164, 25, 96)
            )
        } else {
            colors = intArrayOf(
                Color.argb(175, 38, 45, 49),
                Color.argb(145, 25, 31, 35)
            )
        }
        orientation = GradientDrawable.Orientation.TL_BR
        setStroke(1, Color.argb(70, 255, 255, 255))
    }

    private fun conversationWashDrawable() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        colors = intArrayOf(
            Color.argb(20, 255, 255, 255),
            Color.argb(8, 255, 255, 255)
        )
        orientation = GradientDrawable.Orientation.TOP_BOTTOM
    }

    private fun walk(root: View, visitor: (View) -> Unit) {
        visitor(root)
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                walk(root.getChildAt(i), visitor)
            }
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

    companion object {
        const val TARGET_PACKAGE = "com.whatsapp"
        const val CONVERSATION_ACTIVITY = "com.whatsapp.Conversation"
        private const val TAG_STYLED = 0x47574101
        private const val TAG_TOOLBAR = 0x47574102
        private const val TAG_COMPOSER = 0x47574103
        private const val TAG_WALLPAPER = 0x47574104
        private const val TAG_BUBBLE = 0x47574105
        @Volatile var isLoaded: Boolean = false
    }
}
