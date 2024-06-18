package io.github.nicholaslu.fluorite

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.LifecycleOwner
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.time.Instant
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.ExecutionException


class MainActivity : RosActivity() {
    val TAG = "fluo"
    lateinit var previewView: PreviewView
    lateinit var imageCapture: ImageCapture
    lateinit var node: CompressedImageNode
    private val stream = ByteArrayOutputStream()
    val frameRate = 24
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        previewView = findViewById(R.id.previewView)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        startCamera()
        node = CompressedImageNode("android_camera")
//        executor.addNode(node)
        scheduleImageCapture()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                bindPreview(cameraProvider)
            } catch (e: ExecutionException) {
                // Handle any errors (including cancellation) here.
            } catch (e: InterruptedException) {
            }
        }, ContextCompat.getMainExecutor(this))
    }

    fun bindPreview(cameraProvider: ProcessCameraProvider) {
        val preview = Preview.Builder()
            .build()
        imageCapture = ImageCapture.Builder()
            .setResolutionSelector(ResolutionSelector.Builder().setResolutionFilter { supportedSizes, rotationDegrees ->
                supportedSizes.filter { it.width <= 1920 && it.height <= 1920}}
                    .build())
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setJpegQuality(30)
            .build()

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()
        preview.setSurfaceProvider(previewView.surfaceProvider)

        cameraProvider.bindToLifecycle(
            (this as LifecycleOwner),
            cameraSelector,
            preview,
            imageCapture
        )
    }

    private fun scheduleImageCapture() {
        val handler = Handler(Looper.getMainLooper())
        val timer = Timer()
        val task = object : TimerTask() {
            override fun run() {
                handler.post { takePicture() }
            }
        }
        timer.schedule(task, 100, (1000/frameRate).toLong())
    }

    private fun takePicture() {
        imageCapture.takePicture(ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageCapturedCallback() {
            @RequiresApi(Build.VERSION_CODES.O)
            override fun onCaptureSuccess(image: ImageProxy) {
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
//                saveImage(bytes)
                image.close()
                val msg = sensor_msgs.msg.CompressedImage()
                msg.header.stamp = getStamp()
                msg.header.frameId = "pixel"
                msg.format = "jpg"
                msg.data = bytes.asList()
                node.publish_msg(msg)
                stream.reset()
//                Toast.makeText(this@MainActivity, "Msg sent", Toast.LENGTH_SHORT).show()
            }

            override fun onError(exception: ImageCaptureException) {
                Toast.makeText(this@MainActivity, "Image capture failed: ${exception.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun saveImage(bytes: ByteArray) {
//        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
//        try {
//            bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 10, stream)
//        } catch (e: IOException) {
//            e.printStackTrace()
//        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun getStamp(delta: Long = 0): builtin_interfaces.msg.Time {
        val instant: Instant = Instant.now() - Duration.ofMillis(delta)
        val msg = builtin_interfaces.msg.Time()
        msg.sec = instant.epochSecond.toInt()
        msg.nanosec = instant.nano
        return msg
    }

}