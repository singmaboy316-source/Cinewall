package com.cinewall.tv

import android.Manifest
import android.animation.ObjectAnimator
import android.app.Activity
import android.content.ContentUris
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.LruCache
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AbsListView
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors

class Wall(val id: String, val name: String, val cat: String, val file: File?, val pal: IntArray?)

private fun c(v: Int) = v or (0xFF shl 24)

// name, category, [glow, highlight, base, deep]
private val PALS = listOf(
    Triple("Fern light", "Nature", intArrayOf(c(0x7fd47a), c(0xe6f7a1), c(0x12362b), c(0x06120e))),
    Triple("Rain on leaves", "Nature", intArrayOf(c(0x3fb58f), c(0xb6f0d4), c(0x0c2f33), c(0x050f12))),
    Triple("Alpine glow", "Mountains", intArrayOf(c(0xff9a6a), c(0xffe0b0), c(0x2a3a63), c(0x0c1224))),
    Triple("Ridge at dawn", "Mountains", intArrayOf(c(0xff7f9a), c(0xffd3a8), c(0x33295a), c(0x0e0a20))),
    Triple("Nebula drift", "Space", intArrayOf(c(0xa45cff), c(0x4ad0ff), c(0x170a3a), c(0x04030f))),
    Triple("Moonrise orbit", "Space", intArrayOf(c(0x5a7bff), c(0xe6ecff), c(0x0b1030), c(0x03050f))),
    Triple("Neon arcade", "Gaming", intArrayOf(c(0xff2db4), c(0x27e6ff), c(0x1b0a3d), c(0x07030f))),
    Triple("Pixel rain", "Gaming", intArrayOf(c(0x35ff9a), c(0x7a5cff), c(0x0b1d2e), c(0x04080f))),
    Triple("Sakura dusk", "Anime", intArrayOf(c(0xff9ec7), c(0xffd9e6), c(0x4a2350), c(0x150a1f))),
    Triple("Rooftop summer", "Anime", intArrayOf(c(0xffb35a), c(0x7fd8ff), c(0x25456f), c(0x0b1426))),
    Triple("Night drive", "Cars", intArrayOf(c(0xff4d3a), c(0xffb36a), c(0x201a3a), c(0x07060f))),
    Triple("Tail lights", "Cars", intArrayOf(c(0xff2d55), c(0xff8a5a), c(0x2a0f1f), c(0x0a0408))),
    Triple("Glass tower", "Architecture", intArrayOf(c(0x6ac0ff), c(0xd6efff), c(0x1a3a5a), c(0x08121e))),
    Triple("Concrete sun", "Architecture", intArrayOf(c(0xffc27a), c(0xfff0d0), c(0x3a3a46), c(0x12121a))),
    Triple("Ink bloom", "Abstract", intArrayOf(c(0xff6ad5), c(0x6a7bff), c(0x2a0f4a), c(0x0a0518))),
    Triple("Prism fold", "Abstract", intArrayOf(c(0x4af0c8), c(0xffd24a), c(0x1a2a5a), c(0x070b1c))),
    Triple("Soft horizon", "Minimal", intArrayOf(c(0xc9d6ff), c(0xfff2e0), c(0x33405e), c(0x161c2e))),
    Triple("Paper moon", "Minimal", intArrayOf(c(0xf4e6d0), c(0xcfd8e6), c(0x2c3446), c(0x12151f)))
)

private fun renderBuiltin(p: IntArray, w: Int, h: Int): Bitmap {
    val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val cv = Canvas(bm)
    val pt = Paint(Paint.ANTI_ALIAS_FLAG)
    val fw = w.toFloat()
    val fh = h.toFloat()
    pt.shader = LinearGradient(0f, 0f, fw * 0.5f, fh, p[2], p[3], Shader.TileMode.CLAMP)
    cv.drawRect(0f, 0f, fw, fh, pt)
    pt.shader = RadialGradient(fw * 0.85f, fh * 0.1f, fw * 0.55f, (p[1] and 0xFFFFFF) or 0xAA000000.toInt(), p[1] and 0xFFFFFF, Shader.TileMode.CLAMP)
    cv.drawRect(0f, 0f, fw, fh, pt)
    pt.shader = RadialGradient(fw * 0.2f, fh * 1.1f, fw * 0.7f, (p[0] and 0xFFFFFF) or 0xCC000000.toInt(), p[0] and 0xFFFFFF, Shader.TileMode.CLAMP)
    cv.drawRect(0f, 0f, fw, fh, pt)
    return bm
}

private fun decode(f: File, maxW: Int, maxH: Int): Bitmap? {
    if (!f.exists()) return null
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(f.path, o)
    if (o.outWidth <= 0 || o.outHeight <= 0) return null
    var s = 1
    while (o.outWidth / (s * 2) >= maxW && o.outHeight / (s * 2) >= maxH) s *= 2
    return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
}

class Row(val label: String, val value: () -> String, val act: (Int) -> Unit)

class MainActivity : Activity() {
    private val MAX = 100
    private val GOLD = 0xFFE8B86A.toInt()
    private val INK = 0xFFF2EDE4.toInt()
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val h = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(2)
    private lateinit var sp: SharedPreferences
    private lateinit var dir: File
    private val builtin = ArrayList<Wall>()
    private val personal = ArrayList<Wall>()
    private val fav = HashSet<String>()
    private val recent = ArrayList<String>()
    private val filters = listOf("All", "Favourites", "Recent") + listOf("Nature", "Mountains", "Space", "Gaming", "Anime", "Cars", "Architecture", "Abstract", "Minimal") + "Personal"
    private var cur = "b0"
    private var filt = 0
    private var secs = 30
    private var random = false
    private var clockOn = true
    private var slow = false
    private var play = true
    private var sw = 1920
    private var sh = 1080
    private var genC = 0
    private var counter = 0
    private var committed = ""
    private var selId = ""
    private var libOpen = false
    private var setOpen = false
    private var uiVisible = true

    private lateinit var stage: FrameLayout
    private lateinit var front: ImageView
    private lateinit var back: ImageView
    private lateinit var ui: FrameLayout
    private lateinit var clockTv: TextClock
    private lateinit var dateTv: TextClock
    private lateinit var nameTv: TextView
    private lateinit var metaTv: TextView
    private lateinit var toastTv: TextView
    private lateinit var lib: LinearLayout
    private lateinit var setP: LinearLayout
    private lateinit var grid: GridView
    private lateinit var catBtn: TextView
    private lateinit var emptyTv: TextView
    private val adapter = Adapter()
    private val rows = ArrayList<Pair<TextView, Row>>()
    private val cache = object : LruCache<String, Bitmap>(24 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }
    private val hideUi = Runnable { if (!libOpen && !setOpen) setUi(false) }
    private val toastHide = Runnable { toastTv.animate().alpha(0f).setDuration(500).start() }
    private val tickR = object : Runnable {
        override fun run() {
            if (play && !libOpen && !setOpen) step(1)
            h.postDelayed(this, secs * 1000L)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun all(): List<Wall> = builtin + personal
    private fun find(id: String): Wall? = all().firstOrNull { it.id == id }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val dm = resources.displayMetrics
        sw = maxOf(dm.widthPixels, dm.heightPixels)
        sh = minOf(dm.widthPixels, dm.heightPixels)
        sp = getSharedPreferences("cinewall", MODE_PRIVATE)
        dir = File(filesDir, "wallpapers").apply { mkdirs() }
        PALS.forEachIndexed { i, t -> builtin.add(Wall("b$i", t.first, t.second, null, t.third)) }
        secs = sp.getInt("secs", 30)
        random = sp.getBoolean("random", false)
        clockOn = sp.getBoolean("clock", true)
        slow = sp.getBoolean("slow", false)
        play = sp.getBoolean("play", true)
        cur = sp.getString("cur", "b0") ?: "b0"
        filt = sp.getInt("filt", 0).coerceIn(0, filters.size - 1)
        fav.addAll(sp.getStringSet("fav", emptySet()) ?: emptySet())
        recent.addAll((sp.getString("rec", "") ?: "").split(",").filter { it.isNotEmpty() })
        reloadPersonal()
        buildUi()
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        show(find(cur) ?: builtin[0], false, true)
        setUi(true)
    }

    override fun onResume() { super.onResume(); resched() }
    override fun onPause() { super.onPause(); h.removeCallbacks(tickR) }
    override fun onDestroy() { super.onDestroy(); io.shutdownNow() }

    private fun save() {
        sp.edit().putInt("secs", secs).putBoolean("random", random).putBoolean("clock", clockOn)
            .putBoolean("slow", slow).putBoolean("play", play).putString("cur", cur).putInt("filt", filt)
            .putStringSet("fav", HashSet(fav)).putString("rec", recent.joinToString(",")).apply()
    }

    private fun resched() {
        h.removeCallbacks(tickR)
        if (play) h.postDelayed(tickR, secs * 1000L)
    }

    private fun reloadPersonal() {
        personal.clear()
        (dir.listFiles() ?: emptyArray()).filter { it.isFile }.sortedBy { it.name }.forEach {
            personal.add(Wall(it.name, it.nameWithoutExtension.substringAfter('_').substringAfter('_'), "Personal", it, null))
        }
    }

    private fun pool(): List<Wall> {
        val a = all()
        return when (val f = filters[filt]) {
            "All" -> a
            "Favourites" -> a.filter { fav.contains(it.id) }
            "Recent" -> recent.mapNotNull { id -> a.firstOrNull { it.id == id } }
            "Personal" -> personal.toList()
            else -> a.filter { it.cat == f }
        }
    }

    private fun step(d: Int) {
        var p = pool()
        if (p.isEmpty()) p = all()
        val ix = p.indexOfFirst { it.id == cur }
        val n = when {
            ix < 0 -> 0
            random && p.size > 1 -> { var r: Int; do { r = p.indices.random() } while (r == ix); r }
            else -> (ix + d + p.size) % p.size
        }
        show(p[n])
    }

    private fun full(w: Wall): Bitmap? = try {
        if (w.pal != null) renderBuiltin(w.pal, minOf(sw, 1920), minOf(sh, 1080))
        else decode(w.file!!, minOf(sw, 3840), minOf(sh, 2160))
    } catch (e: Throwable) { null }

    private fun show(w: Wall, preview: Boolean = false, instant: Boolean = false) {
        nameTv.text = w.name
        metaTv.text = w.cat + (if (fav.contains(w.id)) "  \u2665" else "") + "  \u00b7  " + (all().indexOf(w) + 1) + " of " + all().size
        if (!preview) {
            cur = w.id
            recent.remove(w.id); recent.add(0, w.id)
            while (recent.size > 12) recent.removeAt(recent.size - 1)
            save()
        }
        val my = ++genC
        io.execute {
            val bm = full(w)
            runOnUiThread {
                if (my != genC || isFinishing) return@runOnUiThread
                if (bm == null) {
                    toast("Can\u2019t open " + w.name)
                    if (!preview && all().size > 1) h.postDelayed({ step(1) }, 900)
                    return@runOnUiThread
                }
                swap(bm, instant)
            }
        }
    }

    private fun swap(bm: Bitmap, instant: Boolean) {
        val d = if (instant) 0L else if (slow) 3500L else 1500L
        front.animate().cancel(); back.animate().cancel()
        back.setImageBitmap(bm)
        stage.bringChildToFront(back)
        back.alpha = 0f
        back.animate().alpha(1f).setDuration(d).start()
        front.animate().alpha(0f).setDuration(d).start()
        for (p in arrayOf("scaleX", "scaleY")) ObjectAnimator.ofFloat(back, p, 1f, 1.06f).setDuration(40000).start()
        val t = front; front = back; back = t
    }

    private fun toast(t: String) {
        toastTv.text = t
        h.removeCallbacks(toastHide)
        toastTv.animate().cancel()
        toastTv.alpha = 1f
        h.postDelayed(toastHide, 2400)
    }

    private fun setUi(v: Boolean) {
        uiVisible = v
        ui.animate().alpha(if (v) 1f else 0f).setDuration(400).start()
        h.removeCallbacks(hideUi)
        if (v) h.postDelayed(hideUi, 9000)
    }

    // ---------- UI ----------
    private fun tv(t: String, size: Float, col: Int = INK) = TextView(this).apply { text = t; textSize = size; setTextColor(col) }

    private fun focusBg(): StateListDrawable {
        val f = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(0x33E8B86A); setStroke(dp(3), GOLD) }
        val n = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(0x14FFFFFF) }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), f)
            addState(intArrayOf(), n)
        }
    }

