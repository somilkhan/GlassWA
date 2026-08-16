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
        XposedBridge.log("GlassWA: unified AMOLED/mehroon theme attached")
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
                    v is TextView -> { themeText(v, id, cls); text++ }
                    v is ImageButton -> { themeImageButton(v, id, cls); widgets++ }
                }
                when {
                    isBubble(id, cls) -> { themeBubble(v); surfaces++ }
                    isToolbar(id, cls) -> { surfaceOnce(v, surface(22f, SURFACE_1, BORDER)); surfaces++ }
                    isSearch(id, cls) -> { surfaceOnce(v, surface(26f, SURFACE_2, BORDER)); surfaces++ }
                    isComposer(id, cls) -> { surfaceOnce(v, surface(30f, SURFACE_1, ACCENT_BORDER)); surfaces++ }
                    isNavigation(id, cls) -> { surfaceOnce(v, surface(24f, SURFACE_1, ACCENT_BORDER)); surfaces++ }
                    isMedia(id, cls) -> { surfaceOnce(v, surface(18f, MEDIA, BORDER)); surfaces++ }
                    isCard(id, cls) -> { surfaceOnce(v, surface(18f, SURFACE_2, BORDER)); surfaces++ }
                    isDialogSurface(id, cls) -> { surfaceOnce(v, surface(22f, DIALOG, ACCENT_BORDER)); surfaces++ }
                }
            }
            XposedBridge.log("GlassWA: theme activity=${a.javaClass.simpleName} surfaces=$surfaces text=$text widgets=$widgets")
        } catch (t: Throwable) {
            XposedBridge.log("GlassWA: theme failed ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun themeWindow(w: Window) {
        w.statusBarColor = BLACK
        w.navigationBarColor = BLACK
        w.decorView.systemUiVisibility = w.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
    }

    private fun themeText(v: TextView, id: String, cls: String) {
        if (v.text?.toString()?.trim().isNullOrEmpty()) return
        val muted = v.textSize < 13.5f || id.contains("secondary") || id.contains("status") || id.contains("preview") || id.contains("date") || cls.contains("secondary")
        v.setTextColor(if (muted) SECONDARY else TEXT)
        v.setHintTextColor(MUTED)
        if (!cls.contains("emoji")) v.typeface = Typeface.create("sans-serif", if (id.contains("title") || id.contains("name")) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun themeEdit(v: EditText) {
        v.setTextColor(TEXT)
        v.setHintTextColor(MUTED)
        v.background = surface(25f, INPUT, ACCENT_BORDER)
        v.setPadding(dp(v, 16), v.paddingTop, dp(v, 16), v.paddingBottom)
    }

    private fun themeImageButton(v: ImageButton, id: String, cls: String) {
        v.imageTintList = ColorStateList.valueOf(if (id.contains("send") || id.contains("fab")) ACCENT_BRIGHT else ICON)
        if (id.contains("send") || id.contains("fab") || cls.contains("floating")) v.background = surface(50f, ACCENT, ACCENT_BRIGHT)
    }

    private fun themeBubble(v: View) {
        val p = IntArray(2)
        v.getLocationOnScreen(p)
        val screen = v.resources.displayMetrics.widthPixels
        val outgoing = p[0] > screen * 0.42f || p[0] + v.width > screen * 0.90f
        v.background = surface(19f, if (outgoing) OUTGOING else INCOMING, if (outgoing) ACCENT_BRIGHT else BORDER)
        v.elevation = dp(v, 1f)
    }

    private fun isToolbar(id: String, cls: String) = id.contains("toolbar") || cls.contains("wdstoolbar") || cls.endsWith("toolbar")
    private fun isSearch(id: String, cls: String) = id.contains("search") || cls.contains("searchview")
    private fun isComposer(id: String, cls: String) = id.contains("footer") || id.contains("edit_layout") || id.contains("text_entry_layout") || id.contains("composer") || cls.contains("composer")
    private fun isNavigation(id: String, cls: String) = id.contains("navigation") || id.contains("bottom_nav") || id.contains("tabbar") || cls.contains("navigationrail") || cls.contains("bottomnavigation")
    private fun isBubble(id: String, cls: String) = id.contains("conversation_text_row") || id.contains("message_bubble") || id.contains("bubble")
    private fun isMedia(id: String, cls: String) = id.contains("media_container") || id.contains("media_bubble")
    private fun isCard(id: String, cls: String) = id.contains("card") || id.contains("encryption") || id.contains("info") || cls.contains("wdslistitem")
    private fun isDialogSurface(id: String, cls: String) = cls.contains("dialog") || cls.contains("bottomsheet") || id.contains("dialog") || id.contains("sheet") || id.contains("popup")

    private fun surfaceOnce(v: View, drawable: GradientDrawable) {
        if (v.getTag(TAG_SURFACE) == true) return
        v.background = drawable
        v.setTag(TAG_SURFACE, true)
        v.invalidate()
    }

    private fun resource(v: View): String = if (v.id == View.NO_ID) "" else try { v.resources.getResourceName(v.id) } catch (_: Throwable) { "" }
    private fun dp(v: View, n: Int) = (n * v.resources.displayMetrics.density).toInt()
    private fun dp(v: View, n: Float) = n * v.resources.displayMetrics.density
    private fun surface(radius: Float, fill: Int, stroke: Int) = GradientDrawable().apply {
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
        private val SURFACE_1 = Color.rgb(10, 7, 9)
        private val SURFACE_2 = Color.rgb(17, 8, 13)
        private val INCOMING = Color.rgb(25, 22, 25)
        private val OUTGOING = Color.rgb(88, 8, 43)
        private val ACCENT = Color.rgb(118, 12, 58)
        private val ACCENT_BRIGHT = Color.rgb(230, 67, 132)
        private val TEXT = Color.rgb(247, 244, 247)
        private val SECONDARY = Color.rgb(177, 166, 175)
        private val MUTED = Color.rgb(122, 113, 121)
        private val ICON = Color.rgb(220, 211, 219)
        private val BORDER = Color.argb(55, 170, 40, 92)
        private val ACCENT_BORDER = Color.argb(95, 190, 42, 101)
        private val INPUT = Color.rgb(24, 10, 18)
        private val MEDIA = Color.rgb(22, 17, 21)
        private val DIALOG = Color.rgb(14, 8, 12)
        private const val TAG_SURFACE = 0x47572001
    }
}
