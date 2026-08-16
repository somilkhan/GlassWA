package com.glasswa.core

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
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

/**
 * GlassWA visual layer. It deliberately stays component-aware: the module
 * themes WhatsApp's existing widgets instead of replacing the app hierarchy.
 * This keeps navigation, click listeners and accessibility intact.
 */
class HookEntry : IXposedHookLoadPackage {
    override fun handleLoadPackage(p: XC_LoadPackage.LoadPackageParam) {
        if (p.packageName != WA) return
        XposedBridge.log("GlassWA: unified theme attached")
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
            var themed = 0
            var text = 0
            var surfaces = 0
            walk(root) { v ->
                if (v.visibility != View.VISIBLE || v.width <= 0 || v.height <= 0) return@walk
                val id = resource(v).lowercase()
                val cls = v.javaClass.name.lowercase()

                when {
                    v is EditText -> { themeEdit(v); themed++ }
                    v is TextView -> { themeText(v, id, cls); text++ }
                    v is ImageButton -> { themeImageButton(v, id, cls); themed++ }
                }

                when {
                    isToolbar(id, cls) -> { surfaceOnce(v, toolbarSurface()); surfaces++ }
                    isSearch(id, cls) -> { surfaceOnce(v, searchSurface()); surfaces++ }
                    isComposer(id, cls) -> { surfaceOnce(v, composerSurface()); surfaces++ }
                    isNavigation(id, cls) -> { surfaceOnce(v, navSurface()); surfaces++ }
                    isBubble(id, cls) -> { themeBubble(v); surfaces++ }
                    isMedia(id, cls) -> { surfaceOnce(v, mediaSurface()); surfaces++ }
                    isCard(id, cls) -> { surfaceOnce(v, cardSurface()); surfaces++ }
                    isDialogSurface(id, cls) -> { surfaceOnce(v, dialogSurface()); surfaces++ }
                }
            }
            XposedBridge.log("GlassWA: theme activity=${a.javaClass.simpleName} surfaces=$surfaces text=$text widgets=$themed")
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
        val s = v.text?.toString()?.trim().orEmpty()
        if (s.isEmpty()) return
        val small = v.textSize < 13.5f
        val muted = small || id.contains("secondary") || id.contains("status") || id.contains("preview") || id.contains("date") || cls.contains("secondary")
        v.setTextColor(if (muted) SECONDARY else TEXT)
        v.setHintTextColor(MUTED)
        if (!cls.contains("emoji")) v.typeface = Typeface.create("sans-serif", if (id.contains("title") || id.contains("name")) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun themeEdit(v: EditText) {
        v.setTextColor(TEXT)
        v.setHintTextColor(MUTED)
        v.background = inputSurface()
        v.setPadding(dp(v, 16), v.paddingTop, dp(v, 16), v.paddingBottom)
    }

    private fun themeImageButton(v: ImageButton, id: String, cls: String) {
        v.imageTintList = android.content.res.ColorStateList.valueOf(if (id.contains("send") || id.contains("fab")) ACCENT_BRIGHT else ICON)
        if (id.contains("send") || id.contains("fab") || cls.contains("floating")) v.background = accentButton()
    }

    private fun themeBubble(v: View) {
        val p = IntArray(2)
        v.getLocationOnScreen(p)
        val screen = v.resources.displayMetrics.widthPixels
        val outgoing = p[0] > screen * .42f || p[0] + v.width > screen * .90f
        v.background = if (outgoing) outgoingBubble() else incomingBubble()
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

    private fun surfaceOnce(v: View, drawable: android.graphics.drawable.Drawable) {
        if (v.getTag(TAG_SURFACE) == true) return
        v.background = drawable
        v.setTag(TAG_SURFACE, true)
        v.invalidate()
    }

    private fun find(root: View, id: String): View? {
        var result: View? = null
        walk(root) { if (result == null && resource(it) == id) result = it }
        return result
    }

    private fun resource(v: View): String = if (v.id == View.NO_ID) "" else try { v.resources.getResourceName(v.id) } catch (_: Throwable) { "" }
    private fun dp(v: View, n: Int) = (n * v.resources.displayMetrics.density).toInt()
    private fun dp(v: View, n: Float) = n * v.resources.displayMetrics.density
    private fun walk(root: View, action: (View) -> Unit) {
        action(root)
        if (root is ViewGroup) for (i in 0 until root.childCount) walk(root.getChildAt(i), action)
    }

    private fun rounded(radius: Float, fill: Int, stroke: Int = Color.TRANSPARENT): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(fill)
        if (stroke != Color.TRANSPARENT) setStroke(1, stroke)
    }

    private fun toolbarSurface() = rounded(22f, SURFACE_1, BORDER)
    private fun searchSurface() = rounded(26f, SURFACE_2, BORDER)
    private fun composerSurface() = rounded(30f, SURFACE_1, ACCENT_BORDER)
    private fun navSurface() = rounded(24f, SURFACE_1, ACCENT_BORDER)
    private fun inputSurface() = rounded(25f, Color.rgb(24, 10, 18), Color.argb(90, 174, 35, 91))
    private fun outgoingBubble() = rounded(19f, OUTGOING, Color.argb(120, 205, 47, 112))
    private fun incomingBubble() = rounded(19f, INCOMING, Color.argb(55, 165, 35, 87))
    private fun mediaSurface() = rounded(18f, Color.rgb(22, 17, 21), Color.argb(60, 170, 38, 91))
    private fun cardSurface() = rounded(18f, SURFACE_2, BORDER)
    private fun dialogSurface() = rounded(22f, Color.rgb(14, 8, 12), Color.argb(100, 180, 40, 94))
    private fun accentButton() = rounded(50f, ACCENT, Color.argb(180, 224, 74, 135))

    companion object {
        private const val WA = "com.whatsapp"
        private const val BLACK = Color.BLACK
        private const val SURFACE_1 = Color.rgb(10, 7, 9)
        private const val SURFACE_2 = Color.rgb(17, 8, 13)
        private const val INCOMING = Color.rgb(25, 22, 25)
        private const val OUTGOING = Color.rgb(88, 8, 43)
        private const val ACCENT = Color.rgb(118, 12, 58)
        private const val ACCENT_BRIGHT = Color.rgb(230, 67, 132)
        private const val TEXT = Color.rgb(247, 244, 247)
        private const val SECONDARY = Color.rgb(177, 166, 175)
        private const val MUTED = Color.rgb(122, 113, 121)
        private const val ICON = Color.rgb(220, 211, 219)
        private const val BORDER = Color.argb(55, 170, 40, 92)
        private const val ACCENT_BORDER = Color.argb(95, 190, 42, 101)
        private const val TAG_SURFACE = 0x47572001
    }
}
