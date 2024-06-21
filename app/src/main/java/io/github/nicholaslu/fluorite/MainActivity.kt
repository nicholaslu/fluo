package io.github.nicholaslu.fluorite

import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.activity.enableEdgeToEdge
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import sensor_msgs.msg.CompressedImage
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.time.Instant
import java.time.temporal.TemporalAmount
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.time.toKotlinDuration


class MainActivity : RosActivity() {
    val TAG = "fluo"
    lateinit var previewView: PreviewView
    lateinit var imageCapture: ImageCapture
    lateinit var videoCapture: VideoCapture<Recorder>
    lateinit var cameraExecutor: ExecutorService
    lateinit var node: CompressedImageNode
    val frameRate = 24
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        cameraExecutor = Executors.newSingleThreadExecutor()

        previewView = findViewById(R.id.previewView)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        startCamera()
        node = CompressedImageNode("pixel")
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(findViewById<PreviewView>(R.id.previewView).surfaceProvider)
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                .build()

//            videoCapture = VideoCapture.withOutput(recorder)
            videoCapture = VideoCapture.Builder<Recorder>(recorder)
                .build()

            val imageAnalysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionFilter { supportedSizes, rotationDegrees ->
                            Log.d(TAG, "Supported resolution: $supportedSizes")
                            supportedSizes.filter { it.width >= 1024 && it.height >= 768 }
                        }.build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, FrameAnalyzer())
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, videoCapture, imageAnalysis
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    private inner class FrameAnalyzer : ImageAnalysis.Analyzer {
        private var baos = ByteArrayOutputStream()
        override fun analyze(image: ImageProxy) {
            val bitmap = image.toBitmap()
            node.publishMsg(toJpegMsg(bitmap, 30))
            image.close()
        }

        private fun toPngMsg(bitmap: Bitmap, quality: Int, scale: Double = 1.0) : CompressedImage{
            if (scale == 1.0){
                bitmap.compress(Bitmap.CompressFormat.PNG, quality, baos)
            } else if (scale > 1.0) {
                val width = (bitmap.width / scale).toInt()
                val height = (bitmap.height / scale).toInt()
                val cBitmap = Bitmap.createScaledBitmap(bitmap, width, height, true)
                cBitmap.compress(Bitmap.CompressFormat.PNG, quality, baos)
            } else {
                Log.e(TAG, "scale should be larger than 1.0")
                return CompressedImage()
            }
            val msg = CompressedImage()
            msg.header.frameId = "pixel"
            msg.header.stamp = getStamp()
            msg.format = "png"
            msg.data = baos.toByteArray().asList()
            baos.reset()
            return msg
        }

        private fun toJpegMsg(bitmap: Bitmap, quality: Int, scale: Double = 1.0) : CompressedImage {
            if (scale == 1.0){
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            } else if (scale > 1.0) {
                val width = (bitmap.width / scale).toInt()
                val height = (bitmap.height / scale).toInt()
                val cBitmap = Bitmap.createScaledBitmap(bitmap, width, height, true)
                cBitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            } else {
                Log.e(TAG, "scale should be larger than 1.0")
                return CompressedImage()
            }
            val msg = CompressedImage()
            msg.header.frameId = "pixel"
            msg.header.stamp = getStamp()
            msg.format = "jpeg"
            msg.data = baos.toByteArray().asList()
            baos.reset()
            return msg
        }

        private fun toWebpMsg(bitmap: Bitmap, quality: Int, scale: Double = 1.0) : CompressedImage{
            if (scale == 1.0){
                if(Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE){
                    bitmap.compress(Bitmap.CompressFormat.WEBP, quality, baos)
                } else {
                    bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, baos)
                }
            } else if (scale > 1.0) {
                val width = (bitmap.width / scale).toInt()
                val height = (bitmap.height / scale).toInt()
                val cBitmap = Bitmap.createScaledBitmap(bitmap, width, height, true)
                if(Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE){
                    cBitmap.compress(Bitmap.CompressFormat.WEBP, quality, baos)
                } else {
                    cBitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, baos)
                }
            } else {
                Log.e(TAG, "scale should be larger than 1.0")
                return CompressedImage()
            }
            val msg = CompressedImage()
            msg.header.frameId = "pixel"
            msg.header.stamp = getStamp()
            msg.format = "webp"
            msg.data = baos.toByteArray().asList()
            baos.reset()
            return msg
        }

        private fun toWebpLosslessMsg(bitmap: Bitmap, quality: Int, scale: Double = 1.0) : CompressedImage {
            if (scale == 1.0){
                if(Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE){
                    bitmap.compress(Bitmap.CompressFormat.WEBP, 100, baos)
                } else {
                    bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, quality, baos)
                }
            } else if (scale > 1.0) {
                val width = (bitmap.width / scale).toInt()
                val height = (bitmap.height / scale).toInt()
                val cBitmap = Bitmap.createScaledBitmap(bitmap, width, height, true)
                if(Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE){
                    cBitmap.compress(Bitmap.CompressFormat.WEBP, 100, baos)
                } else {
                    cBitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, quality, baos)
                }
            } else {
                Log.e(TAG, "scale should be larger than 1.0")
                return CompressedImage()
            }
            val msg = CompressedImage()
            msg.header.frameId = "pixel"
            msg.header.stamp = getStamp()
            msg.format = "webp"
            msg.data = baos.toByteArray().asList()
            baos.reset()
            return msg
        }
    }
}

fun getStamp(delta: Long = 0): builtin_interfaces.msg.Time {
    val instant: Instant = Instant.now() - Duration.ofMillis(delta)
    val msg = builtin_interfaces.msg.Time()
    msg.sec = instant.epochSecond.toInt()
    msg.nanosec = instant.nano
    return msg
}