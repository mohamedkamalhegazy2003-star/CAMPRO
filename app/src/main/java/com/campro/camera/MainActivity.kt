package com.campro.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.GestureDetector
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatioStrategy
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        /** CAMPRO is tuned for Samsung Galaxy A35 only (model codes SM-A356*). Set false to run anywhere. */
        private const val STRICT_A35_ONLY = true
        private const val TAG = "CAMPRO"
        private val ACCENT = Color.parseColor("#FF7A18")
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    private lateinit var previewView: PreviewView
    private lateinit var effects: EffectsView
    private lateinit var gridView: GridView
    private lateinit var flashFx: View
    private lateinit var focusRing: View
    private lateinit var zoomLabel: TextView
    private lateinit var strip: LinearLayout
    private lateinit var intensityBox: LinearLayout
    private lateinit var thumb: ImageView
    private lateinit var shutter: ShutterView
    private lateinit var flashBtn: IconView
    private lateinit var gridBtn: IconView
    private lateinit var tabCinema: TextView
    private lateinit var tabKodak: TextView

    private val chips = mutableListOf<Pair<CamFilter, View>>()

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var flashMode = ImageCapture.FLASH_MODE_OFF
    private var currentFilter: CamFilter = Looks.NONE
    private var intensity = 1f
    private var category = Category.CINEMA
    private var busy = false
    private var lastUri: Uri? = null
    private val io = Executors.newSingleThreadExecutor()

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else toast("Camera permission is required / مطلوب إذن الكاميرا")
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (STRICT_A35_ONLY && !Build.MODEL.uppercase(Locale.ROOT).startsWith("SM-A356")) {
            setContentView(View(this).apply { setBackgroundColor(Color.BLACK) })
            AlertDialog.Builder(this)
                .setTitle("CAMPRO")
                .setMessage("This app is made for Samsung Galaxy A35 only.\nهذا التطبيق مخصص لهاتف Samsung Galaxy A35 فقط.\n\nDetected: ${Build.MANUFACTURER} ${Build.MODEL}")
                .setCancelable(false)
                .setPositiveButton("Close / إغلاق") { _, _ -> finish() }
                .show()
            return
        }

        setContentView(buildUi())
        selectCategory(Category.CINEMA)
        applyLook()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // ------------------------------------------------------------------ UI

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, b.top, 0, b.bottom)
            insets
        }

        // ---- preview area (4:3, matches the photo)
        val w = resources.displayMetrics.widthPixels
        val pc = FrameLayout(this)
        root.addView(pc, LinearLayout.LayoutParams(MATCH, w * 4 / 3))

        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            setBackgroundColor(Color.BLACK)
        }
        pc.addView(previewView, FrameLayout.LayoutParams(MATCH, MATCH))
        previewView.previewStreamState.observe(this) { state ->
            if (state == PreviewView.StreamState.STREAMING) applyPreviewFilter()
        }

        effects = EffectsView(this)
        pc.addView(effects, FrameLayout.LayoutParams(MATCH, MATCH))

        gridView = GridView(this).apply { visibility = View.GONE }
        pc.addView(gridView, FrameLayout.LayoutParams(MATCH, MATCH))

        focusRing = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(2), Color.WHITE)
                setColor(Color.TRANSPARENT)
            }
            visibility = View.GONE
        }
        pc.addView(focusRing, FrameLayout.LayoutParams(dp(72), dp(72)))

        flashFx = View(this).apply {
            setBackgroundColor(Color.WHITE)
            alpha = 0f
        }
        pc.addView(flashFx, FrameLayout.LayoutParams(MATCH, MATCH))

        zoomLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(4), dp(12), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.argb(140, 0, 0, 0))
            }
            alpha = 0f
        }
        pc.addView(zoomLabel, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM).apply {
            bottomMargin = dp(60)
        })

        // top bar over the preview
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(150, 0, 0, 0), Color.TRANSPARENT)
            )
        }
        flashBtn = IconView(this, IconView.FLASH).apply { setOnClickListener { cycleFlash() } }
        gridBtn = IconView(this, IconView.GRID).apply { setOnClickListener { toggleGrid() } }
        val title = TextView(this).apply {
            text = "CAMPRO"
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.22f
            gravity = Gravity.CENTER
        }
        topBar.addView(flashBtn, LinearLayout.LayoutParams(dp(56), dp(56)))
        topBar.addView(title, LinearLayout.LayoutParams(0, MATCH, 1f))
        topBar.addView(gridBtn, LinearLayout.LayoutParams(dp(56), dp(56)))
        pc.addView(topBar, FrameLayout.LayoutParams(MATCH, dp(56), Gravity.TOP))

        // intensity slider over the preview bottom
        intensityBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), 0, dp(18), 0)
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Color.argb(150, 0, 0, 0), Color.TRANSPARENT)
            )
            visibility = View.GONE
        }
        val label = TextView(this).apply {
            text = "Intensity"
            setTextColor(Color.WHITE)
            textSize = 12f
        }
        val bar = SeekBar(this).apply {
            max = 100
            progress = 100
            progressTintList = android.content.res.ColorStateList.valueOf(ACCENT)
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    intensity = p / 100f
                    applyLook()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        intensityBox.addView(label, LinearLayout.LayoutParams(WRAP, WRAP))
        intensityBox.addView(bar, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(10) })
        pc.addView(intensityBox, FrameLayout.LayoutParams(MATCH, dp(48), Gravity.BOTTOM))

        setupGestures()

        // ---- bottom panel
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(bottom, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val tabs = LinearLayout(this).apply { gravity = Gravity.CENTER }
        tabCinema = makeTab("CINEMATIC") { selectCategory(Category.CINEMA) }
        tabKodak = makeTab("KODAK") { selectCategory(Category.KODAK) }
        tabs.addView(tabCinema)
        tabs.addView(tabKodak)
        bottom.addView(tabs, LinearLayout.LayoutParams(MATCH, dp(40)))

        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), 0, dp(10), 0)
        }
        scroll.addView(strip, FrameLayout.LayoutParams(WRAP, MATCH))
        bottom.addView(scroll, LinearLayout.LayoutParams(MATCH, dp(100)))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bottom.addView(row, LinearLayout.LayoutParams(MATCH, 0, 1f))

        // gallery thumbnail
        val left = FrameLayout(this)
        thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#2A2D35"))
                setStroke(dp(2), Color.WHITE)
            }
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
            setOnClickListener { openGallery() }
        }
        left.addView(thumb, FrameLayout.LayoutParams(dp(52), dp(52), Gravity.CENTER))
        row.addView(left, LinearLayout.LayoutParams(0, MATCH, 1f))

        // shutter
        val center = FrameLayout(this)
        shutter = ShutterView(this).apply { setOnClickListener { takePhoto() } }
        center.addView(shutter, FrameLayout.LayoutParams(dp(78), dp(78), Gravity.CENTER))
        row.addView(center, LinearLayout.LayoutParams(0, MATCH, 1f))

        // flip camera
        val right = FrameLayout(this)
        val flip = IconView(this, IconView.FLIP).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#2A2D35"))
            }
            setOnClickListener { flipCamera() }
        }
        right.addView(flip, FrameLayout.LayoutParams(dp(52), dp(52), Gravity.CENTER))
        row.addView(right, LinearLayout.LayoutParams(0, MATCH, 1f))

        return root
    }

    private fun makeTab(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.15f
        gravity = Gravity.CENTER
        setPadding(dp(22), dp(8), dp(22), dp(8))
        setOnClickListener { onClick() }
    }

    private fun selectCategory(cat: Category) {
        category = cat
        tabCinema.setTextColor(if (cat == Category.CINEMA) ACCENT else Color.parseColor("#8A8F98"))
        tabKodak.setTextColor(if (cat == Category.KODAK) ACCENT else Color.parseColor("#8A8F98"))
        buildStrip()
    }

    private fun buildStrip() {
        strip.removeAllViews()
        chips.clear()
        val list = listOf(Looks.NONE) + Looks.forCategory(category)
        for (f in list) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(4), dp(6), dp(4), 0)
                setOnClickListener { selectFilter(f) }
            }
            val sw = View(this)
            item.addView(sw, LinearLayout.LayoutParams(dp(58), dp(58)))
            val tv = TextView(this).apply {
                text = f.name
                setTextColor(Color.WHITE)
                textSize = 10.5f
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            item.addView(tv, LinearLayout.LayoutParams(dp(70), WRAP).apply { topMargin = dp(5) })
            strip.addView(item, LinearLayout.LayoutParams(dp(76), MATCH))
            chips.add(f to sw)
        }
        refreshChips()
    }

    private fun refreshChips() {
        for ((f, v) in chips) {
            val sel = f === currentFilter
            v.background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR, intArrayOf(f.swatchA, f.swatchB)
            ).apply {
                cornerRadius = dp(14).toFloat()
                setStroke(if (sel) dp(3) else dp(1), if (sel) ACCENT else Color.argb(70, 255, 255, 255))
            }
            v.scaleX = if (sel) 1.06f else 1f
            v.scaleY = if (sel) 1.06f else 1f
        }
    }

    private fun selectFilter(f: CamFilter) {
        currentFilter = f
        intensityBox.visibility = if (f.isNone) View.GONE else View.VISIBLE
        refreshChips()
        applyLook()
    }

    // ------------------------------------------------------------------ look (preview)

    private fun applyLook() {
        if (!::effects.isInitialized) return
        val f = currentFilter
        val k = if (f.isNone) 0f else intensity
        effects.set(f.vignette * k, f.grain * k)
        applyPreviewFilter()
    }

    private fun applyPreviewFilter() {
        val tv = findTextureView(previewView) ?: return
        val f = currentFilter
        if (f.isNone || intensity <= 0.01f) {
            tv.setLayerPaint(null)
        } else {
            val paint = Paint()
            paint.colorFilter = ColorMatrixColorFilter(Looks.lerp(f.matrix, intensity))
            tv.setLayerPaint(paint)
        }
    }

    private fun findTextureView(v: View): TextureView? {
        if (v is TextureView) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                findTextureView(v.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    // ------------------------------------------------------------------ gestures

    private fun setupGestures() {
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val cam = camera ?: return true
                val st = cam.cameraInfo.zoomState.value ?: return true
                val r = (st.zoomRatio * d.scaleFactor).coerceIn(st.minZoomRatio, st.maxZoomRatio)
                cam.cameraControl.setZoomRatio(r)
                showZoom(r)
                return true
            }
        })
        val tap = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                focusAt(e.x, e.y)
                return true
            }
        })
        effects.setOnTouchListener { _, ev ->
            scale.onTouchEvent(ev)
            if (!scale.isInProgress) tap.onTouchEvent(ev)
            true
        }
    }

    private fun showZoom(r: Float) {
        zoomLabel.text = String.format(Locale.US, "%.1fx", r)
        zoomLabel.animate().cancel()
        zoomLabel.alpha = 1f
        zoomLabel.animate().alpha(0f).setStartDelay(900).setDuration(300).start()
    }

    private fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        val pt = previewView.meteringPointFactory.createPoint(x, y)
        cam.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(pt).build())
        focusRing.animate().cancel()
        focusRing.x = x - dp(36)
        focusRing.y = y - dp(36)
        focusRing.visibility = View.VISIBLE
        focusRing.alpha = 1f
        focusRing.scaleX = 1.4f
        focusRing.scaleY = 1.4f
        focusRing.animate().scaleX(1f).scaleY(1f).setDuration(220).withEndAction {
            focusRing.animate().alpha(0f).setStartDelay(600).setDuration(250)
                .withEndAction { focusRing.visibility = View.GONE }.start()
        }.start()
    }

    // ------------------------------------------------------------------ camera

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            bindCamera()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

        for (high in listOf(true, false)) {
            try {
                provider.unbindAll()

                val captureSel = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .apply {
                        if (high) {
                            setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                            setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
                        }
                    }
                    .build()

                val ic = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setJpegQuality(100)
                    .setResolutionSelector(captureSel)
                    .setFlashMode(flashMode)
                    .build()

                val previewSel = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .build()
                val preview = Preview.Builder()
                    .setResolutionSelector(previewSel)
                    .build()
                    .also { it.surfaceProvider = previewView.surfaceProvider }

                camera = provider.bindToLifecycle(this, selector, preview, ic)
                imageCapture = ic
                Log.i(TAG, "Bound camera, highRes=$high")
                return
            } catch (e: Exception) {
                Log.w(TAG, "bind failed (highRes=$high)", e)
            }
        }
        toast("Camera failed to start / تعذر تشغيل الكاميرا")
    }

    private fun flipCamera() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
            CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        bindCamera()
    }

    private fun cycleFlash() {
        val (mode, state) = when (flashMode) {
            ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO to 1
            ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON to 2
            else -> ImageCapture.FLASH_MODE_OFF to 0
        }
        flashMode = mode
        imageCapture?.flashMode = mode
        flashBtn.state = state
    }

    private fun toggleGrid() {
        val show = gridView.visibility != View.VISIBLE
        gridView.visibility = if (show) View.VISIBLE else View.GONE
        gridBtn.state = if (show) 2 else 0
    }

    // ------------------------------------------------------------------ capture

    private fun takePhoto() {
        val ic = imageCapture ?: return
        if (busy) return
        busy = true
        shutter.busy = true

        shutter.animate().scaleX(0.88f).scaleY(0.88f).setDuration(70).withEndAction {
            shutter.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        }.start()
        flashFx.animate().cancel()
        flashFx.alpha = 0.75f
        flashFx.animate().alpha(0f).setDuration(220).start()

        val tmp = File.createTempFile("cap_", ".jpg", cacheDir)
        val filter = currentFilter
        val k = if (filter.isNone) 0f else intensity

        ic.takePicture(
            ImageCapture.OutputFileOptions.Builder(tmp).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    io.execute {
                        val uri = try {
                            PhotoSaver.save(applicationContext, tmp, filter, k)
                        } catch (t: Throwable) {
                            Log.e(TAG, "save failed", t)
                            null
                        }
                        tmp.delete()
                        val thumbBmp: Bitmap? = uri?.let {
                            try {
                                contentResolver.loadThumbnail(it, Size(320, 320), null)
                            } catch (t: Throwable) {
                                null
                            }
                        }
                        runOnUiThread {
                            busy = false
                            shutter.busy = false
                            if (uri != null) {
                                lastUri = uri
                                thumbBmp?.let { thumb.setImageBitmap(it) }
                            } else {
                                toast("Save failed / فشل الحفظ")
                            }
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "capture error", exception)
                    tmp.delete()
                    busy = false
                    shutter.busy = false
                    toast("Capture failed / فشل التصوير")
                }
            }
        )
    }

    private fun openGallery() {
        val uri = lastUri ?: return
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/jpeg")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (e: Exception) {
            toast("No gallery app found")
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (::shutter.isInitialized) takePhoto()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }
}
