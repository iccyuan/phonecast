package com.phonecast.viewer

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 内置扫码器: Camera2 取 YUV 帧 → ZXing 解码 → 命中 phonecast:// 即回传。
 * 之所以自己扫: 系统相机/多数扫码 App 不会打开自定义协议链接。
 */
class ScanActivity : Activity() {

    private companion object {
        const val REQ_CAMERA = 1
        val ANALYSIS_SIZE = Size(1280, 720)
    }

    private lateinit var preview: SurfaceView
    private lateinit var statusText: TextView

    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null

    private val handled = AtomicBoolean(false)
    // 只解二维码: 不限定格式会遍历所有条码类型, 每帧开销大到明显掉帧
    private val zxing = MultiFormatReader().apply {
        setHints(mapOf(
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        ))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val c = this

        preview = SurfaceView(c)
        statusText = TextView(c).apply {
            text = "将电脑上的配对二维码放入框内"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
        }

        val overlay = View(c).apply { background = ScanFrame(c) }

        val topBar = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(c, 12), Ui.dp(c, 40), Ui.dp(c, 12), 0)
            addView(Ui.iconButton(c, Icons.Back(c, Color.WHITE), "返回") { finish() },
                LinearLayout.LayoutParams(Ui.dp(c, 44), Ui.dp(c, 44)))
            addView(TextView(c).apply {
                text = "扫码配对"
                setTextColor(Color.WHITE)
                textSize = 18f
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = Ui.dp(c, 8) })
        }

        setContentView(FrameLayout(c).apply {
            setBackgroundColor(Color.BLACK)
            addView(preview, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(overlay, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(topBar, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP))
            addView(statusText, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
                bottomMargin = Ui.dp(c, 120)
            })
            addView(Ui.flatButton(c, "改用手动填写", Color.WHITE).apply {
                setOnClickListener {
                    startActivity(Intent(c, AddActivity::class.java))
                    finish()
                }
            }, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, Ui.dp(c, 48), Gravity.BOTTOM).apply {
                bottomMargin = Ui.dp(c, 48)
            })
        })

        preview.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) = ensurePermissionAndStart()
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) = stopCamera()
        })
    }

    private fun ensurePermissionAndStart() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
            return
        }
        startCamera()
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(rc, perms, res)
        if (rc == REQ_CAMERA) {
            if (res.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                Toast.makeText(this, "没有相机权限,请改用手动填写", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startCamera() {
        if (camera != null) return
        bgThread = HandlerThread("scan-camera").apply { start() }
        bgHandler = Handler(bgThread!!.looper)

        val mgr = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = mgr.cameraIdList.firstOrNull {
            mgr.getCameraCharacteristics(it)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: mgr.cameraIdList.firstOrNull() ?: run {
            Toast.makeText(this, "没有可用的相机", Toast.LENGTH_LONG).show()
            return
        }

        // 分析尺寸必须取相机真正支持的, 否则会话配置会失败(机型差异大)
        val size = pickAnalysisSize(mgr, id)
        reader = ImageReader.newInstance(
            size.width, size.height, ImageFormat.YUV_420_888, 2).apply {
            setOnImageAvailableListener({ r -> analyze(r) }, bgHandler)
        }

        try {
            mgr.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    camera = cam
                    createSession(cam)
                }
                override fun onDisconnected(cam: CameraDevice) { cam.close(); camera = null }
                override fun onError(cam: CameraDevice, err: Int) {
                    cam.close(); camera = null
                    runOnUiThread { Toast.makeText(this@ScanActivity, "相机打开失败 ($err)", Toast.LENGTH_LONG).show() }
                }
            }, bgHandler)
        } catch (e: SecurityException) {
            Toast.makeText(this, "没有相机权限", Toast.LENGTH_LONG).show()
        }
    }

    /** 选一个最接近 720p 的受支持 YUV 尺寸: 太大解码慢, 太小扫不出小码 */
    private fun pickAnalysisSize(mgr: CameraManager, id: String): Size {
        val supported = mgr.getCameraCharacteristics(id)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.YUV_420_888)
            ?.toList().orEmpty()
        if (supported.isEmpty()) return ANALYSIS_SIZE
        val target = ANALYSIS_SIZE.width * ANALYSIS_SIZE.height
        return supported.minByOrNull { kotlin.math.abs(it.width * it.height - target) }!!
    }

    @Suppress("DEPRECATION") // createCaptureSession(list, cb, handler) 在 minSdk 24 上是唯一选择
    private fun createSession(cam: CameraDevice) {
        val previewSurface = preview.holder.surface
        val analysisSurface = reader!!.surface
        val request = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(previewSurface)
            addTarget(analysisSurface)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }
        cam.createCaptureSession(listOf(previewSurface, analysisSurface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    runCatching { s.setRepeatingRequest(request.build(), null, bgHandler) }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    runOnUiThread { Toast.makeText(this@ScanActivity, "相机预览配置失败", Toast.LENGTH_LONG).show() }
                }
            }, bgHandler)
    }

    /** 取 Y 平面做灰度解码; 竖屏时画面是旋转的, 失败则换个方向再试一次。 */
    private fun analyze(r: ImageReader) {
        val image = r.acquireLatestImage() ?: return
        try {
            if (handled.get()) return
            val plane = image.planes[0]
            val buf = plane.buffer
            val data = ByteArray(buf.remaining())
            buf.get(data)
            val w = image.width
            val h = image.height
            val rowStride = plane.rowStride

            // rowStride 可能大于宽度, 需按行紧凑化
            val gray = if (rowStride == w) data else ByteArray(w * h).also { out ->
                for (y in 0 until h) System.arraycopy(data, y * rowStride, out, y * w, w)
            }

            val text = decode(gray, w, h) ?: decode(rotate90(gray, w, h), h, w)
            if (text != null && handled.compareAndSet(false, true)) {
                runOnUiThread { onScanned(text) }
            }
        } catch (_: Exception) {
            // 单帧解码失败无所谓, 下一帧继续
        } finally {
            image.close()
        }
    }

    private fun decode(gray: ByteArray, w: Int, h: Int): String? = try {
        val source = PlanarYUVLuminanceSource(gray, w, h, 0, 0, w, h, false)
        zxing.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
    } catch (_: Exception) {
        null
    } finally {
        zxing.reset()
    }

    private fun rotate90(src: ByteArray, w: Int, h: Int): ByteArray {
        val out = ByteArray(src.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                out[x * h + (h - 1 - y)] = src[y * w + x]
            }
        }
        return out
    }

    private fun onScanned(text: String) {
        val link = Regex("phonecast://\\S+").find(text)?.value
        if (link == null) {
            Toast.makeText(this, "这不是 PhoneCast 的配对二维码", Toast.LENGTH_SHORT).show()
            handled.set(false) // 允许继续扫
            return
        }
        setResult(RESULT_OK, Intent().setData(android.net.Uri.parse(link)))
        finish()
    }

    private fun stopCamera() {
        runCatching { session?.close() }
        runCatching { camera?.close() }
        runCatching { reader?.close() }
        session = null
        camera = null
        reader = null
        bgThread?.quitSafely()
        bgThread = null
        bgHandler = null
    }

    override fun onPause() {
        super.onPause()
        stopCamera()
    }

    /** 取景框: 半透明遮罩 + 四角高亮 */
    private class ScanFrame(private val c: Context) : Drawable() {
        private val mask = Paint().apply { color = 0xA6000000.toInt() }
        private val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
        }
        private val corner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Ui.ACCENT
            strokeCap = Paint.Cap.ROUND
            strokeWidth = Ui.dpf(c, 4f)
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            val side = minOf(b.width(), b.height()) * 0.68f
            val cx = b.exactCenterX()
            val cy = b.exactCenterY() - Ui.dpf(c, 24f)
            val box = RectF(cx - side / 2, cy - side / 2, cx + side / 2, cy + side / 2)
            val r = Ui.dpf(c, 20f)

            val layer = canvas.saveLayer(null, null)
            canvas.drawRect(b, mask)
            canvas.drawRoundRect(box, r, r, clear)
            canvas.restoreToCount(layer)

            val arm = side * 0.12f
            for ((sx, sy, dx, dy) in listOf(
                listOf(box.left, box.top, 1f, 1f),
                listOf(box.right, box.top, -1f, 1f),
                listOf(box.left, box.bottom, 1f, -1f),
                listOf(box.right, box.bottom, -1f, -1f),
            ).map { Quad(it[0], it[1], it[2], it[3]) }) {
                canvas.drawLine(sx, sy, sx + arm * dx, sy, corner)
                canvas.drawLine(sx, sy, sx, sy + arm * dy, corner)
            }
        }

        private data class Quad(val a: Float, val b: Float, val c: Float, val d: Float)

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
        @Deprecated("deprecated in API 29", ReplaceWith("android.graphics.PixelFormat.TRANSLUCENT"))
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
