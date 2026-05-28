package com.example.noisecam

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import androidx.exifinterface.media.ExifInterface
import android.content.pm.PackageManager
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.TypedValue
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var viewFinder: PreviewView
    private lateinit var captureButton: ImageButton
    private lateinit var focusRing: ImageView
    private lateinit var thumbnailView: ImageView

    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var lastSavedUri: Uri? = null
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private lateinit var zoomIndicator: TextView

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms.all { it.value }) {
            startCamera()
        } else {
            Toast.makeText(this, getString(R.string.permission_denied), Toast.LENGTH_LONG).show()
            finish()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewFinder = findViewById(R.id.viewFinder)
        captureButton = findViewById(R.id.captureButton)
        focusRing = findViewById(R.id.focusRing)
        thumbnailView = findViewById(R.id.thumbnailView)
        zoomIndicator = findViewById(R.id.zoomIndicator)

        scaleGestureDetector = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val cam = camera ?: return true
                    val state = cam.cameraInfo.zoomState.value ?: return true
                    val newRatio = (state.zoomRatio * detector.scaleFactor)
                        .coerceIn(state.minZoomRatio, state.maxZoomRatio)
                    cam.cameraControl.setZoomRatio(newRatio)
                    showZoomIndicator(newRatio)
                    return true
                }
            }
        )

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissions.launch(requiredPermissions())
        }

        captureButton.setOnClickListener { takePhoto() }

        thumbnailView.setOnClickListener {
            lastSavedUri?.let { uri ->
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "image/jpeg")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(intent)
            }
        }

        // ピンチでズーム、シングルタップでピント合わせ
        viewFinder.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            if (!scaleGestureDetector.isInProgress && event.action == MotionEvent.ACTION_DOWN) {
                tapToFocus(event.x, event.y)
            }
            true
        }
    }

    private fun allPermissionsGranted() = requiredPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requiredPermissions(): Array<String> = buildList {
        add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }.toTypedArray()

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(viewFinder.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture
                )
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.camera_error), Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun tapToFocus(x: Float, y: Float) {
        val cam = camera ?: return

        val factory = viewFinder.meteringPointFactory
        val point = factory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()
        cam.cameraControl.startFocusAndMetering(action)

        showFocusRing(x, y)
    }

    private fun showZoomIndicator(ratio: Float) {
        zoomIndicator.text = "%.1fx".format(ratio)
        zoomIndicator.visibility = View.VISIBLE
        zoomIndicator.animate().cancel()
        zoomIndicator.alpha = 1f
        zoomIndicator.animate()
            .setStartDelay(800)
            .alpha(0f)
            .setDuration(400)
            .withEndAction { zoomIndicator.visibility = View.GONE }
            .start()
    }

    private fun showFocusRing(x: Float, y: Float) {
        val offset = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 32f, resources.displayMetrics
        )
        focusRing.translationX = x - offset
        focusRing.translationY = y - offset
        focusRing.visibility = View.VISIBLE
        focusRing.alpha = 1f
        focusRing.animate()
            .setStartDelay(600)
            .alpha(0f)
            .setDuration(400)
            .withEndAction { focusRing.visibility = View.GONE }
            .start()
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        captureButton.isEnabled = false

        capture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val bitmap = imageProxyToBitmap(image)
                    val noisyBitmap = compressToNoisy(bitmap)
                    bitmap.recycle()
                    saveAndShowThumbnail(noisyBitmap)
                } finally {
                    image.close()
                }
                runOnUiThread { captureButton.isEnabled = true }
            }

            override fun onError(exception: ImageCaptureException) {
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.capture_error),
                        Toast.LENGTH_SHORT
                    ).show()
                    captureButton.isEnabled = true
                }
            }
        })
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

        val rotation = image.imageInfo.rotationDegrees
        return if (rotation != 0) {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true).also {
                if (it !== raw) raw.recycle()
            }
        } else {
            raw
        }
    }

    // quality=1 で圧縮 → デコードして「ノイズ入り Bitmap」を返す
    private fun compressToNoisy(bitmap: Bitmap): Bitmap {
        val buf = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 1, buf)
        val bytes = buf.toByteArray()
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun saveAndShowThumbnail(bitmap: Bitmap) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "NOISE_$timestamp.jpg"

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/NoiseCam")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: run {
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.save_error), Toast.LENGTH_SHORT).show()
                    bitmap.recycle()
                }
                return
            }

        try {
            resolver.openOutputStream(uri)!!.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
            }
            resolver.openFileDescriptor(uri, "rw")!!.use { pfd ->
                ExifInterface(pfd.fileDescriptor).apply {
                    setAttribute(ExifInterface.TAG_MAKE, "NoiseCam")
                    setAttribute(ExifInterface.TAG_MODEL, "NoiseCam")
                    setAttribute(ExifInterface.TAG_SOFTWARE, "NoiseCam")
                    saveAttributes()
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            lastSavedUri = uri
            runOnUiThread { showThumbnail(bitmap) }
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            bitmap.recycle()
            runOnUiThread {
                Toast.makeText(this, getString(R.string.save_error), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showThumbnail(bitmap: Bitmap) {
        thumbnailView.setImageBitmap(bitmap)
        thumbnailView.visibility = View.VISIBLE
        thumbnailView.alpha = 0f
        thumbnailView.animate()
            .alpha(1f)
            .setDuration(200)
            .start()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
