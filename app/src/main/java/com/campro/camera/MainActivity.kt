package com.campro.camera

import android.Manifest
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
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
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.lifecycle.LiveData
import androidx.camera.core.ZoomState
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    companion object {
        /** CAMPRO is tuned for Samsung Galaxy A35 only (model codes SM-A356*). Set false to run anywhere. */
        private const val STRICT_A35_ONLY = true
        private const val TAG = "CAMPRO"
        /** Software HDR+ (3-shot exposure bracket + fusion). Not a CameraX extension. */
        private const val SOFT_HDR = -100
        private const val VIDEO = -200
        private const val MP50 = -300
        private const val WIDE = -400
        private const val NIGHT_SIGHT = -500
        private const val SUPER_ZOOM = -600
        private const val PVIDEO = -700
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
    private val tabViews = mutableMapOf<Category, TextView>()
    private lateinit var modeBar: LinearLayout
    private lateinit var evBox: LinearLayout
    private lateinit var evBar: SeekBar
    private lateinit var evLabel: TextView
    private lateinit var timerBtn: IconView
    private lateinit var evBtn: IconView
    private lateinit var countdown: TextView
    private var extMgr: ExtensionsManager? = null
    private var extMode: Int = ExtensionMode.NONE
    private var timerSec = 0
    private lateinit var pvView: ImageView
    private val soft = SoftPortrait { bmp -> runOnUiThread { pvView.setImageBitmap(bmp) } }
    private val analysisExec = Executors.newSingleThreadExecutor()
    private lateinit var zoomBar: LinearLayout
    private lateinit var micBtn: TextView
    private lateinit var recLabel: TextView
    private var zoomLive: LiveData<ZoomState>? = null
    private var zoomAnim: ValueAnimator? = null
    private val zoomPresets = mutableListOf<Float>()
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var videoFile: File? = null
    private var recStartMs = 0L
    private var videoStartNs = 0L
    private var micSource = 0 // 0 = phone mic, 1 = bluetooth mic
    private var audioRec: MediaRecorder? = null
    private var audioFile: File? = null
    private var audioStartNs = 0L
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private val recTick = object : Runnable {
        override fun run() {
            val sec = (SystemClock.elapsedRealtime() - recStartMs) / 1000
            recLabel.text = String.format(Locale.US, "\u25CF %02d:%02d", sec / 60, sec % 60)
            ui.postDelayed(this, 500)
        }
    }
    private var evLower = 0
    private var evStep = 1f
    private val ui = Handler(Looper.getMainLooper())
    private val evHide = Runnable {
        evBox.visibility = View.GONE
        evBtn.state = 0
    }

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

    private val mediaPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refreshMic() }

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
        pvView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }
        pc.addView(pvView, FrameLayout.LayoutParams(MATCH, MATCH))
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
        pc.addView(zoomLabel, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply {
            topMargin = dp(100)
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
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.18f
            gravity = Gravity.CENTER
        }
        timerBtn = IconView(this, IconView.TIMER).apply { setOnClickListener { cycleTimer() } }
        evBtn = IconView(this, IconView.EV).apply { setOnClickListener { toggleEv() } }
        topBar.addView(flashBtn, LinearLayout.LayoutParams(dp(52), dp(56)))
        topBar.addView(timerBtn, LinearLayout.LayoutParams(dp(52), dp(56)))
        topBar.addView(title, LinearLayout.LayoutParams(0, MATCH, 1f))
        topBar.addView(evBtn, LinearLayout.LayoutParams(dp(52), dp(56)))
        topBar.addView(gridBtn, LinearLayout.LayoutParams(dp(52), dp(56)))
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

        // shooting-mode pills (PHOTO / HDR+ / HDR / NIGHT / PORTRAIT ...)
        modeBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val modeScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            addView(modeBar, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        pc.addView(modeScroll, FrameLayout.LayoutParams(MATCH, dp(36), Gravity.TOP).apply { topMargin = dp(56) })

        // exposure compensation slider
        evLabel = TextView(this).apply {
            text = "EV 0.0"
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
        }
        evBar = SeekBar(this).apply {
            progressTintList = android.content.res.ColorStateList.valueOf(ACCENT)
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) setEv(p)
                }
                override fun onStartTrackingTouch(s: SeekBar?) { ui.removeCallbacks(evHide) }
                override fun onStopTrackingTouch(s: SeekBar?) { ui.postDelayed(evHide, 3500) }
            })
        }
        evBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.argb(140, 0, 0, 0))
            }
            visibility = View.GONE
        }
        evBox.addView(evLabel, LinearLayout.LayoutParams(dp(64), WRAP))
        evBox.addView(evBar, LinearLayout.LayoutParams(0, WRAP, 1f))
        pc.addView(evBox, FrameLayout.LayoutParams(MATCH, dp(40), Gravity.BOTTOM).apply {
            bottomMargin = dp(92)
            marginStart = dp(20)
            marginEnd = dp(20)
        })

        countdown = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 96f
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(12f, 0f, 0f, Color.BLACK)
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        pc.addView(countdown, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))

        // zoom presets (0.6x / 1x / 2x)
        zoomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        pc.addView(zoomBar, FrameLayout.LayoutParams(WRAP, dp(32), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(54)
        })

        // microphone source (video mode only)
        micBtn = TextView(this).apply {
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(5), dp(12), dp(5))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.argb(130, 0, 0, 0))
            }
            visibility = View.GONE
            setOnClickListener { tick(it); cycleMic() }
        }
        pc.addView(micBtn, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START).apply {
            topMargin = dp(100)
            marginStart = dp(12)
        })

        recLabel = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#FF3B30"))
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(5), dp(12), dp(5))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.argb(130, 0, 0, 0))
            }
            visibility = View.GONE
        }
        pc.addView(recLabel, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(100)
            marginEnd = dp(12)
        })

        setupGestures()

        // ---- bottom panel
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(bottom, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val tabRow = LinearLayout(this).apply { gravity = Gravity.CENTER }
        for (cat in listOf(Category.CINEMA, Category.KODAK, Category.IPHONE, Category.FUJI, Category.NATURAL, Category.RAW, Category.VIBE)) {
            val tv = makeTab(cat.label) { selectCategory(cat) }
            tabViews[cat] = tv
            tabRow.addView(tv)
        }
        val tabScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            addView(tabRow, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        bottom.addView(tabScroll, LinearLayout.LayoutParams(MATCH, dp(40)))

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
        shutter = ShutterView(this).apply { setOnClickListener { tick(it); onShutter() } }
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
        setPadding(dp(16), dp(8), dp(16), dp(8))
        setOnClickListener { onClick() }
    }

    private fun selectCategory(cat: Category) {
        category = cat
        for ((c, tv) in tabViews) {
            tv.setTextColor(if (c == cat) ACCENT else Color.parseColor("#8A8F98"))
        }
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
            v.animate().scaleX(if (sel) 1.08f else 1f).scaleY(if (sel) 1.08f else 1f).setDuration(140).start()
        }
    }

    private fun tick(v: View? = null) {
        (v ?: window.decorView).performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    private fun selectFilter(f: CamFilter) {
        tick()
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
        if (extMode == PVIDEO) effects.set(0f, 0f) else effects.set(f.vignette * k, f.grain * k)
        soft.colorFilter = if (f.isNone || k <= 0.01f) null else ColorMatrixColorFilter(Looks.lerp(f.matrix, k))
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
        val pt = if (extMode == PVIDEO)
            SurfaceOrientedMeteringPointFactory(effects.width.toFloat(), effects.height.toFloat()).createPoint(x, y)
        else previewView.meteringPointFactory.createPoint(x, y)
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
            val provider = future.get()
            cameraProvider = provider
            val ef = ExtensionsManager.getInstanceAsync(this, provider)
            ef.addListener({
                extMgr = try { ef.get() } catch (e: Exception) { null }
                refreshModes()
                bindCamera()
            }, ContextCompat.getMainExecutor(this))
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return
        var base = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        if (extMode == WIDE) base = wideSelector(provider) ?: base
        val mgr = extMgr
        val isVideo = extMode == VIDEO
        val isPV = extMode == PVIDEO
        val selector = try {
            if (!isVideo && mgr != null && extMode > 0 && mgr.isExtensionAvailable(base, extMode))
                mgr.getExtensionEnabledCameraSelector(base, extMode)
            else base
        } catch (e: Exception) {
            base
        }
        previewView.visibility = if (isPV) View.INVISIBLE else View.VISIBLE
        pvView.visibility = if (isPV) View.VISIBLE else View.GONE
        previewView.scaleType = if (isVideo) PreviewView.ScaleType.FIT_CENTER else PreviewView.ScaleType.FILL_CENTER
        updateModeUi()

        for (attempt in 0..1) {
            try {
                provider.unbindAll()
                imageCapture = null
                videoCapture = null

                val ratio = if (isVideo) AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
                else AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                val pb = Preview.Builder()
                    .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(ratio).build())
                val preview = pb.build().also { it.surfaceProvider = previewView.surfaceProvider }

                if (isPV) {
                    val analysis = ImageAnalysis.Builder()
                        .setResolutionSelector(
                            ResolutionSelector.Builder()
                                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                                .setResolutionStrategy(
                                    ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                                )
                                .build()
                        )
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(analysisExec, soft)
                    camera = provider.bindToLifecycle(this, selector, analysis)
                } else if (isVideo) {
                    val recorder = Recorder.Builder()
                        .setQualitySelector(
                            QualitySelector.fromOrderedList(
                                listOf(Quality.UHD, Quality.FHD, Quality.HD),
                                FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                            )
                        )
                        .build()
                    val vc = VideoCapture.withOutput(recorder)
                    camera = provider.bindToLifecycle(this, selector, preview, vc)
                    videoCapture = vc
                    applyLook()
                } else {
                    // Standard modes use the sensor's normal (binned, low-noise) JPEG size.
                    // 50MP mode asks CameraX for the highest-resolution size the phone exposes.
                    val hi = extMode == MP50 && attempt == 0
                    val capSel = ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .apply {
                            if (hi) {
                                setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                                setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
                            }
                        }
                        .build()
                    val builder = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .setJpegQuality(100)
                        .setResolutionSelector(capSel)
                        .setFlashMode(flashMode)
                    if (extMode == MP50) {
                        Camera2Interop.Extender(builder)
                            .setCaptureRequestOption(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
                            .setCaptureRequestOption(
                                CaptureRequest.NOISE_REDUCTION_MODE,
                                CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
                            )
                    }
                    val ic = builder.build()
                    camera = provider.bindToLifecycle(this, selector, preview, ic)
                    imageCapture = ic
                    if (extMode == MP50) reportResolution(ic)
                }
                setupEv()
                setupZoomPills()
                applyTorch()
                if (extMode == WIDE) goWide()
                Log.i(TAG, "Bound camera, mode=$extMode, attempt=$attempt")
                return
            } catch (e: Exception) {
                Log.w(TAG, "bind failed (mode=$extMode, attempt=$attempt)", e)
            }
        }
        if (extMode == PVIDEO) {
            toast("Portrait video failed / بورتريه الفيديو فشل")
            extMode = VIDEO
            refreshModes()
            bindCamera()
            return
        }
        if (extMode != ExtensionMode.NONE) {
            // this mode can't run on this camera: fall back to plain photo
            extMode = ExtensionMode.NONE
            refreshModes()
            bindCamera()
            return
        }
        toast("Camera failed to start / تعذر تشغيل الكاميرا")
    }

    private fun reportResolution(ic: ImageCapture) {
        ui.postDelayed({
            val r = ic.resolutionInfo?.resolution ?: return@postDelayed
            val mp = r.width * r.height / 1_000_000f
            val size = String.format(Locale.US, "%dx%d (%.1f MP)", r.width, r.height, mp)
            toast(
                if (mp >= 40f) "50MP \u2713  $size"
                else "$size \u2014 this is the biggest size the phone gives to third-party apps / أعلى دقة بيسمح بيها الهاتف للتطبيقات"
            )
        }, 700)
    }

    /** The back camera with the shortest focal length (the ultra-wide), or null when there is only one. */
    private fun wideSelector(provider: ProcessCameraProvider): CameraSelector? {
        return try {
            val back = provider.availableCameraInfos.filter {
                it.lensFacing == CameraSelector.LENS_FACING_BACK
            }
            if (back.size < 2) return null
            val widest = back.minByOrNull {
                Camera2CameraInfo.from(it)
                    .getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?.minOrNull() ?: Float.MAX_VALUE
            } ?: return null
            val id = Camera2CameraInfo.from(widest).cameraId
            CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .addCameraFilter { list -> list.filter { Camera2CameraInfo.from(it).cameraId == id } }
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "wide selector failed", e)
            null
        }
    }

    private fun goWide() {
        val cam = camera ?: return
        val st = cam.cameraInfo.zoomState.value ?: return
        if (st.minZoomRatio < 0.95f) {
            cam.cameraControl.setZoomRatio(st.minZoomRatio)
        } else {
            // either the dedicated ultra-wide camera is now bound, or the phone doesn't expose one
            val f = try {
                Camera2CameraInfo.from(cam.cameraInfo)
                    .getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull()
            } catch (e: Exception) {
                null
            }
            if (f != null && f > 3.0f) toast("No ultra-wide lens available to apps / العدسة الواسعة مش متاحة للتطبيقات")
        }
    }

    private fun bokehSupported(): Boolean {
        return try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val facing = if (lensFacing == CameraSelector.LENS_FACING_FRONT)
                CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
            cm.cameraIdList.any { id ->
                val ch = cm.getCameraCharacteristics(id)
                ch.get(CameraCharacteristics.LENS_FACING) == facing &&
                    (ch.get(CameraCharacteristics.CONTROL_AVAILABLE_EXTENDED_SCENE_MODE_CAPABILITIES)
                        ?.any { it.mode == CameraMetadata.CONTROL_EXTENDED_SCENE_MODE_BOKEH_CONTINUOUS } == true)
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun updateModeUi() {
        val video = isVid()
        shutter.videoMode = video
        micBtn.visibility = if (video) View.VISIBLE else View.GONE
        if (video) refreshMic()
    }

    private fun applyTorch() {
        camera?.cameraControl?.enableTorch(isVid() && flashMode != ImageCapture.FLASH_MODE_OFF)
    }

    private fun setupZoomPills() {
        zoomBar.removeAllViews()
        zoomPresets.clear()
        zoomLive?.removeObservers(this)
        val cam = camera ?: return
        val st = cam.cameraInfo.zoomState.value ?: return
        if (st.minZoomRatio < 0.95f) zoomPresets.add(st.minZoomRatio)
        zoomPresets.add(1f)
        if (st.maxZoomRatio >= 2f) zoomPresets.add(2f)
        for (z in zoomPresets) {
            val tv = TextView(this).apply {
                text = (if (z < 1f) String.format(Locale.US, "%.1f", z) else z.toInt().toString()) + "x"
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setOnClickListener {
                    tick(it)
                    animateZoom(z)
                }
            }
            zoomBar.addView(tv, LinearLayout.LayoutParams(dp(40), dp(32)).apply {
                marginStart = dp(3)
                marginEnd = dp(3)
            })
        }
        updateZoomPills(st.zoomRatio)
        zoomLive = cam.cameraInfo.zoomState.also { live ->
            live.observe(this) { updateZoomPills(it.zoomRatio) }
        }
    }

    private fun updateZoomPills(ratio: Float) {
        if (zoomPresets.isEmpty()) return
        var best = 0
        for (i in zoomPresets.indices) {
            if (Math.abs(zoomPresets[i] - ratio) < Math.abs(zoomPresets[best] - ratio)) best = i
        }
        for (i in 0 until zoomBar.childCount) {
            val tv = zoomBar.getChildAt(i) as TextView
            val sel = i == best
            tv.setTextColor(if (sel) Color.BLACK else Color.WHITE)
            tv.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (sel) ACCENT else Color.argb(110, 0, 0, 0))
            }
        }
    }

    private fun animateZoom(target: Float) {
        val cam = camera ?: return
        val from = cam.cameraInfo.zoomState.value?.zoomRatio ?: 1f
        zoomAnim?.cancel()
        zoomAnim = ValueAnimator.ofFloat(from, target).apply {
            duration = 260
            addUpdateListener { cam.cameraControl.setZoomRatio(it.animatedValue as Float) }
            start()
        }
    }

    // ------------------------------------------------------------------ video + microphone

    private fun hasPerm(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun ensureMediaPerms() {
        val need = mutableListOf<String>()
        if (!hasPerm(Manifest.permission.RECORD_AUDIO)) need.add(Manifest.permission.RECORD_AUDIO)
        if (!hasPerm(Manifest.permission.BLUETOOTH_CONNECT)) need.add(Manifest.permission.BLUETOOTH_CONNECT)
        if (need.isNotEmpty()) mediaPermLauncher.launch(need.toTypedArray())
    }

    private fun isBtType(t: Int) = t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || t == AudioDeviceInfo.TYPE_BLE_HEADSET

    private fun btInput(): AudioDeviceInfo? = try {
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { isBtType(it.type) }
    } catch (e: Exception) {
        null
    }

    private fun btComm(): AudioDeviceInfo? = try {
        audioManager.availableCommunicationDevices.firstOrNull { isBtType(it.type) }
    } catch (e: Exception) {
        null
    }

    private fun refreshMic() {
        if (!::micBtn.isInitialized) return
        micBtn.text = if (micSource == 1) "MIC: BLUETOOTH" else "MIC: PHONE"
        micBtn.setTextColor(if (micSource == 1) ACCENT else Color.WHITE)
    }

    private fun cycleMic() {
        if (isRecording()) return
        if (micSource == 0) {
            if (!hasPerm(Manifest.permission.BLUETOOTH_CONNECT) || !hasPerm(Manifest.permission.RECORD_AUDIO)) {
                ensureMediaPerms()
                return
            }
            if (btInput() == null && btComm() == null) {
                toast("No Bluetooth mic connected / وصّل سماعة بلوتوث الأول")
                return
            }
            micSource = 1
        } else {
            micSource = 0
        }
        refreshMic()
    }

    private fun onShutter() {
        if (isVid() && isRecording()) {
            stopRecord()
        } else {
            takePhoto()
        }
    }

    private fun startRecord() {
        val vc = videoCapture
        if (extMode != PVIDEO && vc == null) return
        if (isRecording() || busy) return
        val audioOk = hasPerm(Manifest.permission.RECORD_AUDIO)
        val useBt = micSource == 1 && audioOk && (btInput() != null || btComm() != null)
        if (micSource == 1 && !useBt) {
            toast("Bluetooth mic not available / ميك البلوتوث مش متاح")
            return
        }
        if (!audioOk) toast("No microphone permission: recording without sound / بدون صوت")

        val out = File.createTempFile("vid_", ".mp4", cacheDir)
        videoFile = out

        fun begin() {
            if (useBt || (extMode == PVIDEO && audioOk)) startAudioRec(useBt)
            if (extMode == PVIDEO) {
                if (soft.startRecording(out)) {
                    shutter.recording = true
                    recStartMs = SystemClock.elapsedRealtime()
                    recLabel.visibility = View.VISIBLE
                    ui.post(recTick)
                } else {
                    stopBtAudio()
                    audioFile?.delete()
                    audioFile = null
                    out.delete()
                    toast("Camera not ready yet, try again / الكاميرا لسه مجهزتش، جرّب تاني")
                }
                return
            }
            try {
                var pr = vc!!.output.prepareRecording(this, FileOutputOptions.Builder(out).build())
                if (audioOk && !useBt) pr = pr.withAudioEnabled()
                recording = pr.start(ContextCompat.getMainExecutor(this)) { onVideoEvent(it) }
                shutter.recording = true
            } catch (t: Throwable) {
                Log.e(TAG, "record start failed", t)
                stopBtAudio()
                audioFile?.delete()
                audioFile = null
                out.delete()
                toast("Recording failed / فشل التسجيل")
            }
        }

        if (useBt) {
            btComm()?.let { audioManager.setCommunicationDevice(it) }
            toast("Connecting Bluetooth mic… / جاري توصيل ميك البلوتوث")
            ui.postDelayed({ begin() }, 900)
        } else {
            begin()
        }
    }

    private fun stopRecord() {
        shutter.recording = false
        stopBtAudio()
        if (extMode == PVIDEO && soft.recording) {
            videoStartNs = soft.firstFrameNs
            soft.stopRecording { ok ->
                runOnUiThread {
                    ui.removeCallbacks(recTick)
                    recLabel.visibility = View.GONE
                    val f = videoFile
                    videoFile = null
                    if (ok && f != null) {
                        finishVideo(f, true)
                    } else {
                        f?.delete()
                        audioFile?.delete()
                        audioFile = null
                        toast("Recording failed / فشل التسجيل")
                    }
                }
            }
            return
        }
        recording?.stop()
    }

    private fun startAudioRec(bt: Boolean) {
        try {
            val f = File.createTempFile("aud_", ".m4a", cacheDir)
            val mr = MediaRecorder(this)
            mr.setAudioSource(if (bt) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.CAMCORDER)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioSamplingRate(44100)
            mr.setAudioEncodingBitRate(96000)
            mr.setAudioChannels(1)
            if (bt) btInput()?.let { mr.setPreferredDevice(it) }
            mr.setOutputFile(f.absolutePath)
            mr.prepare()
            mr.start()
            audioStartNs = SystemClock.elapsedRealtimeNanos()
            audioRec = mr
            audioFile = f
        } catch (t: Throwable) {
            Log.e(TAG, "bt audio failed", t)
            audioRec = null
            audioFile?.delete()
            audioFile = null
            toast("Bluetooth mic failed / فشل ميك البلوتوث")
        }
    }

    private fun stopBtAudio() {
        audioRec?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        audioRec = null
        try { audioManager.clearCommunicationDevice() } catch (_: Exception) {}
    }

    private fun onVideoEvent(ev: VideoRecordEvent) {
        when (ev) {
            is VideoRecordEvent.Start -> {
                videoStartNs = SystemClock.elapsedRealtimeNanos()
                recStartMs = SystemClock.elapsedRealtime()
                recLabel.visibility = View.VISIBLE
                ui.post(recTick)
            }
            is VideoRecordEvent.Finalize -> {
                ui.removeCallbacks(recTick)
                recLabel.visibility = View.GONE
                shutter.recording = false
                recording = null
                stopBtAudio()
                val f = videoFile
                videoFile = null
                if (f != null && f.exists() && f.length() > 0) {
                    finishVideo(f)
                } else {
                    f?.delete()
                    audioFile?.delete()
                    audioFile = null
                    toast("Recording failed / فشل التسجيل")
                }
            }
            else -> {}
        }
    }

    private fun finishVideo(raw: File, baked: Boolean = false) {
        busy = true
        shutter.busy = true
        toast("Processing video… / جاري معالجة الفيديو")
        val filter = if (baked) Looks.NONE else currentFilter
        val k = if (filter.isNone) 0f else intensity
        val aud = audioFile
        audioFile = null
        val offsetUs = ((videoStartNs - audioStartNs) / 1000L).coerceAtLeast(0L)

        if (!filter.isNone && k > 0.01f) {
            val t = File.createTempFile("vf_", ".mp4", cacheDir)
            try {
                VideoProcessor.transcode(this, raw, t, filter, k, aud != null) { err ->
                    if (err != null) {
                        Log.e(TAG, "video filter failed", err)
                        toast("Filter failed, saved the original / فشل الفلتر، اتحفظ الأصلي")
                        storeVideo(raw, aud, offsetUs, listOf(raw, t))
                    } else {
                        storeVideo(t, aud, offsetUs, listOf(raw, t))
                    }
                }
            } catch (t2: Throwable) {
                Log.e(TAG, "video filter start failed", t2)
                storeVideo(raw, aud, offsetUs, listOf(raw, t))
            }
        } else {
            storeVideo(raw, aud, offsetUs, listOf(raw))
        }
    }

    private fun storeVideo(stage: File, aud: File?, offsetUs: Long, cleanup: List<File>) {
        io.execute {
            var uri: Uri? = null
            try {
                var finalFile = stage
                if (aud != null) {
                    finalFile = File.createTempFile("vm_", ".mp4", cacheDir)
                    AvMuxer.mux(stage, aud, finalFile, offsetUs)
                }
                uri = VideoSaver.store(applicationContext, finalFile)
                if (finalFile !== stage) finalFile.delete()
            } catch (t: Throwable) {
                Log.e(TAG, "video save failed", t)
            }
            cleanup.forEach { it.delete() }
            aud?.delete()
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
                    toast("Video saved / تم حفظ الفيديو")
                } else {
                    toast("Save failed / فشل الحفظ")
                }
            }
        }
    }

    // ------------------------------------------------------------------ modes, EV, timer

    private fun isVid() = extMode == VIDEO || extMode == PVIDEO

    private fun isRecording() = recording != null || soft.recording

    private fun refreshModes() {
        if (!::modeBar.isInitialized) return
        modeBar.removeAllViews()
        val base = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        val items = mutableListOf(
            ExtensionMode.NONE to "PHOTO",
            WIDE to "WIDE",
            MP50 to "50MP",
            NIGHT_SIGHT to "NIGHT SIGHT",
            SUPER_ZOOM to "SUPER ZOOM",
            SOFT_HDR to "HDR+",
            VIDEO to "VIDEO",
            PVIDEO to "P-VIDEO"
        )
        val mgr = extMgr
        if (mgr != null) {
            val candidates = listOf(
                ExtensionMode.HDR to "HDR HW",
                ExtensionMode.NIGHT to "NIGHT HW",
                ExtensionMode.BOKEH to "PORTRAIT",
                ExtensionMode.FACE_RETOUCH to "RETOUCH",
                ExtensionMode.AUTO to "AUTO"
            )
            for ((m, n) in candidates) {
                val ok = try { mgr.isExtensionAvailable(base, m) } catch (e: Exception) { false }
                if (ok) items.add(m to n)
            }
        }
        for ((m, n) in items) {
            val sel = m == extMode
            val tv = TextView(this).apply {
                text = n
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(if (sel) Color.BLACK else Color.WHITE)
                setPadding(dp(14), dp(5), dp(14), dp(5))
                background = GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(if (sel) ACCENT else Color.argb(110, 0, 0, 0))
                }
                setOnClickListener {
                    if (extMode != m && !isRecording() && !busy) {
                        tick(it)
                        extMode = m
                        if (m == VIDEO || m == PVIDEO) ensureMediaPerms()
                        refreshModes()
                        bindCamera()
                    }
                }
            }
            modeBar.addView(tv, LinearLayout.LayoutParams(WRAP, WRAP).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            })
        }
    }

    private fun setupEv() {
        val st = camera?.cameraInfo?.exposureState ?: return
        if (!st.isExposureCompensationSupported) {
            evBtn.visibility = View.GONE
            return
        }
        evBtn.visibility = View.VISIBLE
        evLower = st.exposureCompensationRange.lower
        evStep = st.exposureCompensationStep.toFloat()
        evBar.max = st.exposureCompensationRange.upper - evLower
        evBar.progress = -evLower
        evLabel.text = "EV 0.0"
    }

    private fun setEv(p: Int) {
        val idx = evLower + p
        camera?.cameraControl?.setExposureCompensationIndex(idx)
        evLabel.text = String.format(Locale.US, "EV %+.1f", idx * evStep)
    }

    private fun toggleEv() {
        ui.removeCallbacks(evHide)
        if (evBox.visibility == View.VISIBLE) {
            evHide.run()
        } else {
            evBox.visibility = View.VISIBLE
            evBtn.state = 2
            ui.postDelayed(evHide, 3500)
        }
    }

    private fun cycleTimer() {
        timerSec = when (timerSec) {
            0 -> 3
            3 -> 10
            else -> 0
        }
        timerBtn.state = timerSec
    }

    private fun flipCamera() {
        if (isRecording() || busy) return
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
            CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        extMode = ExtensionMode.NONE
        refreshModes()
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
        applyTorch()
    }

    private fun toggleGrid() {
        val show = gridView.visibility != View.VISIBLE
        gridView.visibility = if (show) View.VISIBLE else View.GONE
        gridBtn.state = if (show) 2 else 0
    }

    // ------------------------------------------------------------------ capture

    private fun takePhoto() {
        if (busy) return
        if (timerSec <= 0) {
            captureNow()
            return
        }
        busy = true
        runCountdown(timerSec)
    }

    private fun runCountdown(n: Int) {
        if (n <= 0) {
            countdown.visibility = View.GONE
            busy = false
            captureNow()
            return
        }
        countdown.text = n.toString()
        countdown.visibility = View.VISIBLE
        ui.postDelayed({ runCountdown(n - 1) }, 1000)
    }

    /** Multi-frame modes: HDR+ (exposure bracket), Night Sight (long-EV burst), Super Res Zoom (aligned burst). */
    private fun captureBurst(kind: Int) {
        val ic = imageCapture ?: return
        val cam = camera ?: return
        if (busy) return
        val st = cam.cameraInfo.exposureState
        if (!st.isExposureCompensationSupported) {
            toast("Not supported on this camera / غير مدعوم")
            return
        }
        busy = true
        shutter.busy = true
        toast(
            when (kind) {
                SOFT_HDR -> "HDR+ \u2014 hold steady / ثبّت الهاتف"
                NIGHT_SIGHT -> "Night Sight \u2014 hold VERY still, ~6 s / ثبّت الهاتف كويس"
                else -> "Zoom detail \u2014 hold steady / ثبّت الهاتف"
            }
        )
        flashFx.animate().cancel()
        flashFx.alpha = 0.5f
        flashFx.animate().alpha(0f).setDuration(220).start()

        val step = st.exposureCompensationStep.toFloat()
        val range = st.exposureCompensationRange
        val base = st.exposureCompensationIndex
        val shots: List<Int> = when (kind) {
            SOFT_HDR -> listOf(-2f, 0f, 2f).map { (base + (it / step).roundToInt()).coerceIn(range.lower, range.upper) }
            NIGHT_SIGHT -> List(6) { range.upper }
            else -> List(6) { base }
        }
        val zoomNow = cam.cameraInfo.zoomState.value?.zoomRatio ?: 1f
        val oldFlash = ic.flashMode
        ic.flashMode = ImageCapture.FLASH_MODE_OFF
        val files = mutableListOf<File>()
        val filter = currentFilter
        val k = if (filter.isNone) 0f else intensity
        val exec = ContextCompat.getMainExecutor(this)

        fun restore() {
            cam.cameraControl.setExposureCompensationIndex(base)
            ic.flashMode = oldFlash
        }

        fun fail() {
            files.forEach { it.delete() }
            restore()
            busy = false
            shutter.busy = false
            toast("Capture failed / فشل التصوير")
        }

        fun next(i: Int) {
            if (i >= shots.size) {
                restore()
                io.execute {
                    val uri = try {
                        if (kind == SOFT_HDR) PhotoSaver.saveHdr(applicationContext, files, filter, k)
                        else PhotoSaver.saveBurst(applicationContext, files, filter, k, kind == NIGHT_SIGHT, zoomNow)
                    } catch (t: Throwable) {
                        Log.e(TAG, "burst save failed", t)
                        null
                    }
                    files.forEach { it.delete() }
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
                return
            }
            val f = File.createTempFile("burst_", ".jpg", cacheDir)
            cam.cameraControl.setExposureCompensationIndex(shots[i]).addListener({
                ic.takePicture(
                    ImageCapture.OutputFileOptions.Builder(f).build(),
                    exec,
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                            files.add(f)
                            next(i + 1)
                        }

                        override fun onError(exception: ImageCaptureException) {
                            Log.e(TAG, "burst shot error", exception)
                            f.delete()
                            fail()
                        }
                    }
                )
            }, exec)
        }
        next(0)
    }

    private fun captureNow() {
        if (isVid()) {
            startRecord()
            return
        }
        if (extMode == SOFT_HDR || extMode == NIGHT_SIGHT || extMode == SUPER_ZOOM) {
            captureBurst(extMode)
            return
        }
        // zoomed photos: merge a burst automatically so the zoomed detail comes out clearer
        val zr = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
        if (extMode == ExtensionMode.NONE && zr >= 1.6f && imageCapture != null) {
            captureBurst(SUPER_ZOOM)
            return
        }
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
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, contentResolver.getType(uri) ?: "image/jpeg")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (e: Exception) {
            toast("No gallery app found")
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (::shutter.isInitialized) onShutter()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        try { recording?.stop() } catch (_: Exception) {}
        stopBtAudio()
        soft.release()
        analysisExec.shutdown()
        io.shutdown()
        super.onDestroy()
    }
}
