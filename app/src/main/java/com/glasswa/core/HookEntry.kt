package com.glasswa.core

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class HookEntry : IXposedHookLoadPackage {
    override fun handleLoadPackage(p: XC_LoadPackage.LoadPackageParam) {
        if (p.packageName != WA) return
        XposedBridge.log("GlassWA: unified glass theme engine attached")
        XposedHelpers.findAndHookMethod(Activity::class.java, "onResume", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as? Activity ?: return
                Handler(activity.mainLooper).postDelayed({
                    if (!activity.isFinishing && !activity.isDestroyed) applyTheme(activity)
                }, 180L)
            }
        })
    }

    private fun applyTheme(a: Activity) {
        try {
            themeWindow(a.window)
            val root = a.window.decorView
            root.setBackgroundColor(BLACK)
            var surfaces = 0
            var text = 0
            var widgets = 0
            walk(root) { v ->
                if (v.visibility != View.VISIBLE || v.width <= 0 || v.height <= 0) return@walk
                val id = resource(v).lowercase()
                val cls = v.javaClass.name.lowercase()
                when {
                    v is EditText -> { themeEdit(v); widgets++ }
                    v is ImageButton -> { themeImageButton(v, id, cls); widgets++ }
                    v is TextView -> { themeText(v, id, cls); text++ }
                }
                when {
                    isBubble(id, cls) -> { applySurface(v, GLASS_BUBBLE_OUT, GLASS_BUBBLE_IN, 20f, bubbleOutgoing(v)); surfaces++ }
                    isComposer(id, cls) -> { applySurface(v, GLASS_COMPOSER, GLASS_COMPOSER, 28f, false); surfaces++ }
                    isToolbar(id, cls) -> { applySurface(v, GLASS_TOOLBAR, GLASS_TOOLBAR, 22f, false); surfaces++ }
                    isNavigation(id, cls) -> { applySurface(v, GLASS_NAV, GLASS_NAV, 24f, false); surfaces++ }
                    isSearch(id, cls) -> { applySurface(v, GLASS_SEARCH, GLASS_SEARCH, 26f, false); surfaces++ }
                    isDialogSurface(id, cls) -> { applySurface(v, GLASS_DIALOG, GLASS_DIALOG, 24f, false); surfaces++ }
                    isCard(id, cls) -> { applySurface(v, GLASS_CARD, GLASS_CARD, 18f, false); surfaces++ }
                    isMedia(id, cls) -> { applySurface(v, GLASS_MEDIA, GLASS_MEDIA, 18f, false); surfaces++ }
                }
            }
            XposedBridge.log("GlassWA: unified theme activity=${a.javaClass.simpleName} surfaces=$surfaces text=$text widgets=$widgets")
        } catch (t: Throwable) {
            XposedBridge.log("GlassWA: theme failed ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun themeWindow(w: Window) {
        w.statusBarColor = BLACK
        w.navigationBarColor = BLACK
        w.decorView.systemUiVisibility = w.decorView.systemUiVisibility and
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() and
            View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
    }

    private fun themeText(v: TextView, id: String, cls: String) {
        if (v.text?.toString()?.trim().isNullOrEmpty()) return
        val muted = v.textSize < 13.5f || id.contains("secondary") || id.contains("status") || id.contains("preview") || id.contains("date") || cls.contains("secondary")
        v.setTextColor(if (muted) SECONDARY else TEXT)
        v.setHintTextColor(MUTED)
        if (!cls.contains("emoji")) {
            val weight = if (id.contains("title") || id.contains("name") || id.contains("header")) Typeface.BOLD else Typeface.NORMAL
            v.typeface = Typeface.create("sans-serif", weight)
        }
    }

    private fun themeEdit(v: EditText) {
        v.setTextColor(TEXT)
        v.setHintTextColor(MUTED)
        v.background = glass(25f, GLASS_INPUT, ACCENT_BORDER)
        v.setPadding(dp(v, 16), v.paddingTop, dp(v, 16), v.paddingBottom)
    }

    private fun themeImageButton(v: ImageButton, id: String, cls: String) {
        v.imageTintList = ColorStateList.valueOf(if (id.contains("send") || id.contains("fab")) ACCENT_BRIGHT else ICON)
        if (id.contains("send") || id.contains("fab") || cls.contains("floating")) {
            v.background = glass(50f, GLASS_ACCENT, ACCENT_BRIGHT)
            v.elevation = dp(v, 2f)
        }
    }

    private fun applySurface(v: View, outgoing: Int, incoming: Int, radius: Float, isOutgoing: Boolean) {
        if (v.getTag(TAG_SURFACE) == true) return
        v.background = glass(radius, if (isOutgoing) outgoing else incoming, if (isOutgoing) ACCENT_BORDER else BORDER)
        v.elevation = dp(v, if (radius >= 24f) 1.5f else 1f)
        v.setTag(TAG_SURFACE, true)
        v.invalidate()
    }

    private fun bubbleOutgoing(v: View): Boolean {
        val p = IntArray(2)
        v.getLocationOnScreen(p)
        val screen = v.resources.displayMetrics.widthPixels
        return p[0] > screen * 0.42f || p[0] + v.width > screen * 0.90f
    }

    private fun isToolbar(id: String, cls: String) = id.contains("toolbar") || cls.contains("wdstoolbar") || cls.endsWith("toolbar")
    private fun isSearch(id: String, cls: String) = id.contains("search") || cls.contains("searchview")
    private fun isComposer(id: String, cls: String) = id.contains("footer") || id.contains("edit_layout") || id.contains("text_entry_layout") || id.contains("composer") || cls.contains("composer")
    private fun isNavigation(id: String, cls: String) = id.contains("navigation") || id.contains("bottom_nav") || id.contains("tabbar") || cls.contains("navigationrail") || cls.contains("bottomnavigation")
    private fun isBubble(id: String, cls: String) = id.contains("conversation_text_row") || id.contains("message_bubble") || id.contains("bubble") || cls.contains("messageviewholder") || cls.contains("groupedmessagebysenderviewholder")
    private fun isMedia(id: String, cls: String) = id.contains("media_container") || id.contains("media_bubble")
    private fun isCard(id: String, cls: String) = id.contains("card") || id.contains("encryption") || id.contains("info") || cls.contains("wdslistitem")
    private fun isDialogSurface(id: String, cls: String) = cls.contains("dialog") || cls.contains("bottomsheet") || id.contains("dialog") || id.contains("sheet") || id.contains("popup")

    private fun resource(v: View): String = if (v.id == View.NO_ID) "" else try { v.resources.getResourceName(v.id) } catch (_: Throwable) { "" }
    private fun dp(v: View, n: Int) = (n * v.resources.displayMetrics.density).toInt()
    private fun dp(v: View, n: Float) = n * v.resources.displayMetrics.density
    private fun glass(radius: Float, fill: Int, stroke: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(fill)
        setStroke(1, stroke)
    }
    private fun walk(root: View, action: (View) -> Unit) {
        action(root)
        if (root is ViewGroup) for (i in 0 until root.childCount) walk(root.getChildAt(i), action)
    }

    companion object {
        private const val WA = "com.whatsapp"
        private val BLACK = Color.BLACK
        private val GLASS_TOOLBAR = Color.argb(220, 12, 8, 11)
        private val GLASS_NAV = Color.argb(228, 12, 7, 11)
        private val GLASS_SEARCH = Color.argb(205, 22, 11, 17)
        private val GLASS_COMPOSER = Color.argb(225, 20, 9, 16)
        private val GLASS_INPUT = Color.argb(235, 25, 11, 19)
        private val GLASS_DIALOG = Color.argb(242, 16, 8, 13)
        private val GLASS_CARD = Color.argb(215, 20, 10, 16)
        private val GLASS_MEDIA = Color.argb(205, 18, 14, 18)
        private val GLASS_BUBBLE_IN = Color.argb(225, 28, 23, 28)
        private val GLASS_BUBBLE_OUT = Color.argb(230, 94, 10, 48)
        private val GLASS_ACCENT = Color.argb(235, 120, 12, 60)
        private val TEXT = Color.rgb(247, 244, 247)
        private val SECONDARY = Color.rgb(190, 180, 188)
        private val MUTED = Color.rgb(128, 118, 126)
        private val ICON = Color.rgb(224, 215, 222)
        private val BORDER = Color.argb(58, 200, 115, 154)
        private val ACCENT_BORDER = Color.argb(110, 225, 70, 135)
        private val ACCENT_BRIGHT = Color.rgb(236, 76, 143)
        private const val TAG_SURFACE = 0x47572001
    }
}
