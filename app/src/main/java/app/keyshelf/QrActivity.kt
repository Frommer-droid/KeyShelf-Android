package app.keyshelf

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.*
import android.media.ImageReader
import android.graphics.ImageFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.Toast
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer

class QrActivity : Activity(), SurfaceHolder.Callback {
    private lateinit var preview: SurfaceView
    private val thread = HandlerThread("keyshelf-camera")
    private lateinit var worker: Handler
    private var camera: CameraDevice? = null
    private var reader: ImageReader? = null
    private var session: CameraCaptureSession? = null
    private var finished = false
    override fun onCreate(state: Bundle?) {
        super.onCreate(state); window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        thread.start(); worker = Handler(thread.looper)
        val panel = Ui.panel(this)
        val header = android.widget.LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        header.addView(Ui.iconButton(this, R.drawable.ic_back, "Назад") { finish() })
        header.addView(Ui.label(this, "Сканировать QR", 22f))
        panel.addView(header)
        panel.addView(Ui.label(this, "Наведите камеру на QR в KeyShelf на компьютере", 14f, muted = true))
        preview = SurfaceView(this); panel.addView(preview, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
        panel.addView(Ui.button(this, "Отмена") { finish() }); setContentView(panel)
        preview.holder.addCallback(this)
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
    }
    override fun onRequestPermissionsResult(request: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(request, permissions, results)
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED && preview.holder.surface.isValid) openCamera()
        else if (results.firstOrNull() != PackageManager.PERMISSION_GRANTED) { Toast.makeText(this, "Для сканирования нужна камера", Toast.LENGTH_LONG).show(); finish() }
    }
    override fun surfaceCreated(holder: SurfaceHolder) { if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openCamera() }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
    override fun surfaceDestroyed(holder: SurfaceHolder) { closeCamera() }
    @android.annotation.SuppressLint("MissingPermission")
    private fun openCamera() {
        if (camera != null || reader != null) return
        val manager = getSystemService(CameraManager::class.java)
        val id = manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK } ?: return
        reader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2)
        reader!!.setOnImageAvailableListener({ source ->
            val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                if (finished) return@setOnImageAvailableListener
                val plane = image.planes[0]; val buffer = plane.buffer
                val bytes = ByteArray(image.width * image.height)
                for (y in 0 until image.height) for (x in 0 until image.width)
                    bytes[y * image.width + x] = buffer.get(y * plane.rowStride + x * plane.pixelStride)
                val luminance = PlanarYUVLuminanceSource(bytes, image.width, image.height, 0, 0, image.width, image.height, false)
                val result = runCatching { MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(luminance)), mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)) }.getOrNull()
                if (result != null && result.text.length <= 4096) {
                    finished = true
                    runOnUiThread { setResult(RESULT_OK, Intent().putExtra("invitation", result.text)); finish() }
                }
            } finally { image.close() }
        }, worker)
        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                camera = device
                val output = reader?.surface ?: run { device.close(); return }
                if (!preview.holder.surface.isValid) { device.close(); return }
                device.createCaptureSession(listOf(preview.holder.surface, output), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s
                        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(preview.holder.surface); addTarget(output)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        }
                        runCatching { s.setRepeatingRequest(request.build(), null, worker) }
                    }
                    override fun onConfigureFailed(s: CameraCaptureSession) { runOnUiThread { finish() } }
                }, worker)
            }
            override fun onDisconnected(device: CameraDevice) { device.close() }
            override fun onError(device: CameraDevice, error: Int) { device.close(); runOnUiThread { finish() } }
        }, worker)
    }
    private fun closeCamera() { session?.close(); session = null; camera?.close(); camera = null; reader?.close(); reader = null }
    override fun onDestroy() { finished = true; closeCamera(); thread.quitSafely(); super.onDestroy() }
}
