package com.glasswa.core

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class HookEntry : IXposedHookLoadPackage {
    override fun handleLoadPackage(p: XC_LoadPackage.LoadPackageParam) {
        if (p.packageName != WA) return
        XposedBridge.log("GlassWA: attached to $WA")
        XposedHelpers.findAndHookMethod(Activity::class.java, "onResume", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val a = param.thisObject as? Activity ?: return
                when (a.javaClass.name) {
                    CONVERSATION -> schedule(a, false)
                    HOME -> schedule(a, true)
                }
            }
        })
    }

    private fun schedule(a: Activity, home: Boolean) {
        android.os.Handler(a.mainLooper).postDelayed({
            if (!a.isFinishing && !a.isDestroyed) if (home) applyHome(a) else applyConversation(a)
        }, 500L)
    }

    private fun applyConversation(a: Activity) {
        try {
            val root = a.window.decorView
            a.window.statusBarColor = BLACK
            a.window.navigationBarColor = BLACK
            root.setBackgroundColor(BLACK)
            val r = find(root, "com.whatsapp:id/conversation_root_layout") ?: root
            find(root, "com.whatsapp:id/toolbar")?.let { once(it, toolbar(), TAG_TOOLBAR) }
            find(root, "com.whatsapp:id/footer")?.let { once(it, transparent(), TAG_FOOTER) }
            find(root, "com.whatsapp:id/edit_layout")?.let { once(it, composer(), TAG_COMPOSER) }
            find(root, "com.whatsapp:id/text_entry_layout")?.let { once(it, inputSurface(), TAG_INPUT) }
            var bubbles = 0; var media = 0; var labels = 0; var text = 0
            walk(r) { v ->
                if (v.visibility != View.VISIBLE || v.width <= 0 || v.height <= 0) return@walk
                val id = resource(v); val cls = v.javaClass.name
                when {
                    id == "com.whatsapp:id/conversation_text_row" && v.getTag(TAG_BUBBLE) != true -> { styleBubble(v); v.setTag(TAG_BUBBLE, true); bubbles++ }
                    id == "com.whatsapp:id/media_container" && v.getTag(TAG_MEDIA) != true -> { v.background = media(); v.setTag(TAG_MEDIA, true); media++ }
                    v is TextView && isConversationText(cls, id) -> { styleConversationText(v); text++ }
                    v is TextView && (v.text?.toString()?.trim()?.equals("today", true) == true || v.text?.toString()?.trim()?.equals("yesterday", true) == true) -> {
                        (v.parent as? View)?.let { if (it.width > 0 && it.height > 0) { it.background = dateChip(); labels++ } }
                    }
                }
            }
            XposedBridge.log("GlassWA: conversation redesign bubbles=$bubbles media=$media labels=$labels text=$text")
        } catch (t: Throwable) { XposedBridge.log("GlassWA: conversation failed ${t.javaClass.simpleName}: ${t.message}") }
    }

    private fun applyHome(a: Activity) {
        try {
            val root = a.window.decorView
            a.window.statusBarColor = BLACK; a.window.navigationBarColor = BLACK; root.setBackgroundColor(BLACK)
            val main = find(root, "com.whatsapp:id/main_container") ?: root
            var rows = 0; var surfaces = 0
            walk(main) { v ->
                if (v.visibility != View.VISIBLE || v.width <= 0 || v.height <= 0) return@walk
                val cls = v.javaClass.name.lowercase()
                if ((cls.contains("wdslistitemconversation") || cls.contains("conversationrow")) && v.getTag(TAG_ROW) != true) {
                    v.background = listRow(); v.setTag(TAG_ROW, true); rows++
                }
                if (v is TextView) styleHomeText(v, cls)
                val role = when {
                    cls.contains("wdstoolbar") -> ROLE_TOOLBAR
                    cls.contains("searchview") || cls.contains("conversationsearchview") -> ROLE_SEARCH
                    cls.contains("navigationrail") || cls.contains("bottomnavigation") || cls.contains("tabbar") -> ROLE_NAV
                    else -> 0
                }
                if (role != 0 && v.getTag(TAG_HOME) != true) {
                    v.background = when (role) { ROLE_SEARCH -> searchSurface(); ROLE_NAV -> navigationSurface(); else -> homeToolbar() }
                    v.elevation = if (role == ROLE_NAV) dp(v, 5f) else dp(v, 2f)
                    v.setTag(TAG_HOME, true); surfaces++
                }
            }
            XposedBridge.log("GlassWA: HOME redesign rows=$rows glass=$surfaces")
        } catch (t: Throwable) { XposedBridge.log("GlassWA: home failed ${t.javaClass.simpleName}: ${t.message}") }
    }

    private fun styleBubble(v: View) {
        val p = IntArray(2); v.getLocationOnScreen(p); val w = v.resources.displayMetrics.widthPixels
        val outgoing = p[0] + v.width > w * .92f || p[0] > w * .40f
        v.background = if (outgoing) outgoingBubble() else incomingBubble(); v.elevation = dp(v, 1.5f); v.invalidate()
    }
    private fun styleConversationText(v: TextView) { v.setTextColor(TEXT); v.setHintTextColor(SECONDARY); v.typeface = Typeface.create("sans", Typeface.NORMAL) }
    private fun styleHomeText(v: TextView, cls: String) {
        val s = v.text?.toString()?.trim().orEmpty()
        if (s.isEmpty()) return
        v.setTextColor(if (cls.contains("secondary") || cls.contains("preview") || cls.contains("status") || v.textSize < 14f) SECONDARY else TEXT)
    }
    private fun isConversationText(cls: String, id: String) = id.contains("message_text") || id.contains("date") || cls.contains("wdsrichtextview") || cls.contains("wdstextview")
    private fun once(v: View, d: android.graphics.drawable.Drawable, tag: Int) { if (v.getTag(tag) != true) { v.background = d; v.setTag(tag, true); v.invalidate() } }
    private fun find(root: View, id: String): View? { var result: View? = null; walk(root) { if (result == null && resource(it) == id) result = it }; return result }
    private fun resource(v: View) = if (v.id == View.NO_ID) "" else try { v.resources.getResourceName(v.id) } catch (_: Throwable) { "" }
    private fun dp(v: View, n: Float) = n * v.resources.displayMetrics.density
    private fun walk(root: View, action: (View) -> Unit) { action(root); if (root is ViewGroup) for (i in 0 until root.childCount) walk(root.getChildAt(i), action) }

    private fun toolbar() = rounded(18f, intArrayOf(Color.argb(245, 6, 6, 8), Color.argb(238, 45, 5, 25)), BORDER)
    private fun homeToolbar() = rounded(0f, intArrayOf(Color.BLACK, Color.argb(232, 27, 3, 15)), BORDER)
    private fun searchSurface() = rounded(26f, intArrayOf(Color.argb(230, 15, 15, 17), Color.argb(220, 43, 7, 25)), BORDER)
    private fun navigationSurface() = rounded(24f, intArrayOf(Color.argb(242, 8, 8, 10), Color.argb(232, 46, 6, 26)), Color.argb(75, 185, 45, 100))
    private fun composer() = rounded(32f, intArrayOf(Color.argb(238, 10, 10, 12), Color.argb(220, 58, 7, 32)), Color.argb(85, 185, 45, 100))
    private fun inputSurface() = rounded(27f, intArrayOf(Color.argb(35, 255, 255, 255), Color.argb(20, 130, 20, 70)), Color.argb(40, 185, 45, 100))
    private fun outgoingBubble() = rounded(20f, intArrayOf(Color.rgb(92, 10, 46), Color.rgb(66, 7, 35)), Color.argb(105, 194, 49, 108))
    private fun incomingBubble() = rounded(20f, intArrayOf(Color.rgb(24, 24, 27), Color.rgb(18, 18, 21)), Color.argb(48, 145, 40, 82))
    private fun listRow() = rounded(18f, intArrayOf(Color.BLACK, Color.rgb(8, 8, 10)), Color.argb(28, 160, 35, 80))
    private fun media() = rounded(18f, intArrayOf(Color.rgb(20, 20, 23), Color.rgb(13, 13, 16)), Color.argb(55, 155, 38, 84))
    private fun dateChip() = rounded(20f, intArrayOf(Color.rgb(29, 7, 19), Color.rgb(20, 5, 14)), Color.argb(80, 185, 45, 100))
    private fun transparent() = android.graphics.ColorDrawable(Color.TRANSPARENT)
    private fun rounded(r: Float, colors: IntArray, stroke: Int) = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = r; this.colors = colors; orientation = GradientDrawable.Orientation.TL_BR; setStroke(1, stroke) }

    companion object {
        private const val WA = "com.whatsapp"; private const val CONVERSATION = "com.whatsapp.Conversation"; private const val HOME = "com.whatsapp.home.ui.HomeActivity"
        private const val BLACK = Color.BLACK; private const val TEXT = Color.rgb(245, 242, 245); private const val SECONDARY = Color.rgb(164, 156, 164); private const val BORDER = Color.argb(60, 170, 42, 92)
        private const val TAG_TOOLBAR = 0x47571001; private const val TAG_FOOTER = 0x47571002; private const val TAG_COMPOSER = 0x47571003; private const val TAG_INPUT = 0x47571004; private const val TAG_BUBBLE = 0x47571005; private const val TAG_MEDIA = 0x47571006; private const val TAG_ROW = 0x47571007; private const val TAG_HOME = 0x47571008
        private const val ROLE_TOOLBAR = 1; private const val ROLE_SEARCH = 2; private const val ROLE_NAV = 3
    }
}
