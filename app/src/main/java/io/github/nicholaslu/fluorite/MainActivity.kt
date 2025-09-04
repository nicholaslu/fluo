package io.github.nicholaslu.fluorite

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.util.Rational
import android.util.Size
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.widget.SwitchCompat
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.common.util.concurrent.ListenableFuture
import sensor_msgs.msg.CompressedImage
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.min


class MainActivity : RosActivity() {
    lateinit var formats: Array<String>
    var format = 0
    var publishing = false
    lateinit var previewView: PreviewView
    lateinit var playButton: Button
    lateinit var lensButton: Button
    lateinit var formatSpinner: Spinner
    lateinit var resSpinner: Spinner
    lateinit var namespaceSwitch: SwitchCompat
    lateinit var cameraExecutor: ExecutorService
    lateinit var node: CompressedImageNode
    lateinit var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>
    lateinit var camera: CameraSelector
    lateinit var frontCamera: CameraSelector
    lateinit var backCamera: CameraSelector
    lateinit var frontResolutions: Array<String>
    lateinit var backResolutions: Array<String>
    lateinit var frontResolutionsArrayAdapter: ArrayAdapter<String>
    lateinit var backResolutionsArrayAdapter: ArrayAdapter<String>
    var selectedWidth = 0
    var selectedHeight = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
//        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        window.decorView.windowInsetsController?.apply {
            hide(WindowInsets.Type.systemBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        previewView = findViewById(R.id.previewView)

        playButton = findViewById(R.id.playButton)
        playButton.setOnClickListener {
            onClickPlayButton()
        }

        lensButton = findViewById(R.id.lensButton)
        lensButton.setOnClickListener {
            onClickFlipCamera()
        }

        formatSpinner = findViewById(R.id.formatSpinner)
        formats = arrayOf(
            getString(R.string.format_jpeg),
            getString(R.string.format_png),
            getString(R.string.format_webp),
            getString(R.string.format_webp_lossless)
        )
        val formatArrayAdapter = ArrayAdapter(
            this,
            R.layout.spinner_transparent,
            formats
        ).apply {
            setDropDownViewResource(R.layout.spinner_transparent_dropdown)
        }
        formatSpinner.adapter = formatArrayAdapter
        formatSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                format = position
                Log.d(TAG, "Format changed: ${formats[format]}")
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                TODO("Not yet implemented")
            }
        }

        resSpinner = findViewById(R.id.resSpinner)
        resSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                if (::camera.isInitialized) {
                    when (camera) {
                        backCamera -> {
                            val (w, h) = backResolutions[position].split("x").map { it.toInt() }
                            selectedWidth = w
                            selectedHeight = h
                            restartCamera()
                            Log.d(TAG, "Back camera set to resolution $w x $h")
                        }

                        frontCamera -> {
                            val (w, h) = frontResolutions[position].split("x").map { it.toInt() }
                            selectedWidth = w
                            selectedHeight = h
                            restartCamera()
                            Log.d(TAG, "Front camera set to resolution $w x $h")
                        }

                        else -> {
                            Log.d(TAG, "Camera unknown")
                        }
                    }
                } else {
                    Log.d(TAG, "Camera not initialized")
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                TODO("Not yet implemented")
            }
        }

        namespaceSwitch = findViewById(R.id.namespaceSwitch)
        namespaceSwitch.setOnCheckedChangeListener { _, useNamespace ->
            if (useNamespace) {
                node.changeTopic("compressed", getDeviceName())
            } else {
                node.changeTopic("compressed")
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        node = CompressedImageNode(getDeviceName() + "_camera")

        if (allPermissionsGranted()) {
            initCamera()
        } else {
            requestPermissions.launch(REQUIRED_PERMISSIONS)
        }

        if (allPermissionsGranted()) {
            initCamera()
        }
    }

    private fun getDeviceName(): String {
        return Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)
            .replace(Regex("[^a-zA-Z0-9_]"), "")
    }

    private fun getFrameId(): String {
        if (::camera.isInitialized) {
            when (camera) {
                backCamera -> {
                    return getDeviceName() + "/back"
                }

                frontCamera -> {
                    return getDeviceName() + "/front"
                }

                else -> {
                    Log.e(TAG, "Camera unknown")
                    return getDeviceName()
                }
            }
        } else {
            Log.e(TAG, "Camera not initialized")
            return getDeviceName()
        }
    }

    private fun onClickPlayButton() {
        if (publishing) {
            playButton.text = getString(R.string.text_to_start)
            publishing = false
            stopCamera()
        } else {
            playButton.text = getString(R.string.text_to_pause)
            publishing = true
            startCamera()
        }
    }

    private fun onClickFlipCamera() {
        if (::camera.isInitialized) {
            when (camera) {
                backCamera -> {
                    lensButton.text = getText(R.string.lens_state_front)
                    camera = frontCamera
                    val currentSelected = resSpinner.selectedItem
                    val currentIndex = backResolutions.indexOf(currentSelected)
                    resSpinner.adapter = frontResolutionsArrayAdapter
                    var index = frontResolutions.indexOf(currentSelected)
                    if (index == -1) {
                        index = min(currentIndex, frontResolutions.size - 1)
                        Log.d(TAG, "no match resolution, fallback to index $index")
                    }
                    resSpinner.setSelection(index)
                    val (w, h) = (resSpinner.selectedItem as String).split("x").map { it.toInt() }
                    selectedWidth = w
                    selectedHeight = h
                    restartCamera()
                }

                frontCamera -> {
                    lensButton.text = getText(R.string.lens_state_back)
                    camera = backCamera
                    val currentSelected = resSpinner.selectedItem
                    val currentIndex = frontResolutions.indexOf(currentSelected)
                    resSpinner.adapter = backResolutionsArrayAdapter
                    var index = backResolutions.indexOf(currentSelected)
                    if (index == -1) {
                        index = min(currentIndex, backResolutions.size - 1)
                        Log.d(TAG, "no match resolution, fallback to index $index")
                    }
                    resSpinner.setSelection(index)
                    val (w, h) = (resSpinner.selectedItem as String).split("x").map { it.toInt() }
                    selectedWidth = w
                    selectedHeight = h
                    restartCamera()
                }

                else -> {
                    Log.e(TAG, "Camera unknown")
                }
            }
        } else {
            Log.e(TAG, "Camera not initialized")
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun getResolutionsByCameraInfo(cameraInfo: CameraInfo): Array<String> {
        val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        val cameraId = Camera2CameraInfo.from(cameraInfo).cameraId
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val streamConfigurationMap =
            characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val outputSizes = streamConfigurationMap?.getOutputSizes(ImageFormat.YUV_420_888)
        val resolutions =
            outputSizes?.map { size -> "${size.width}x${size.height}" }?.toTypedArray()
        resolutions?.sortWith(compareBy(
            { it.split("x")[0].toInt() },
            { it.split("x")[1].toInt() }
        ))
        return resolutions ?: arrayOf()
    }

    private fun initCameraByProvider(cameraProvider: ProcessCameraProvider){
        cameraProvider.availableCameraInfos.forEach { cameraInfo ->
            if (cameraInfo.lensFacing == CameraSelector.LENS_FACING_FRONT) {
                frontResolutions = getResolutionsByCameraInfo(cameraInfo)
                frontResolutionsArrayAdapter = ArrayAdapter(
                    this,
                    R.layout.spinner_transparent,
                    frontResolutions
                ).apply {
                    setDropDownViewResource(R.layout.spinner_transparent_dropdown)
                }
                frontCamera = cameraInfo.cameraSelector
            } else if (cameraInfo.lensFacing == CameraSelector.LENS_FACING_BACK) {
                backResolutions = getResolutionsByCameraInfo(cameraInfo)
                backResolutionsArrayAdapter = ArrayAdapter(
                    this,
                    R.layout.spinner_transparent,
                    backResolutions
                ).apply {
                    setDropDownViewResource(R.layout.spinner_transparent_dropdown)
                }
                backCamera = cameraInfo.cameraSelector
            }
        }

        // Use back camera by default
        camera = backCamera
        resSpinner.adapter = backResolutionsArrayAdapter
    }

    private fun startCameraByProvider(cameraProvider: ProcessCameraProvider){
        val resSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(selectedWidth, selectedHeight),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            )
            .build()

        val preview = Preview.Builder()
            .setResolutionSelector(resSelector)
            .build().also { it.setSurfaceProvider(previewView.surfaceProvider) }

        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
            .build()

        val videoCapture = VideoCapture.Builder<Recorder>(recorder)
            .setResolutionSelector(resSelector)
            .build()

        val imageAnalysis = ImageAnalysis.Builder()
            .setResolutionSelector(resSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        val viewPort = ViewPort.Builder(
            Rational(selectedHeight, selectedWidth),
            preview.targetRotation
        ).build()

        val useCases = UseCaseGroup.Builder()
            .setViewPort(viewPort)
            .addUseCase(preview)
            .addUseCase(imageAnalysis)
            .addUseCase(videoCapture)
            .build()

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this, camera, useCases
//                this, camera, preview, imageAnalysis, videoCapture
            )
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun initCamera() {
        cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            initCameraByProvider(cameraProvider)
            startCameraByProvider(cameraProvider)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        cameraProviderFuture.get().unbindAll()
    }

    private fun restartCamera() {
        stopCamera()
        startCamera()
    }

    private fun startCamera() {
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            startCameraByProvider(cameraProvider)
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            results.forEach { (permission, granted) ->
                if (!granted) {
                    Toast.makeText(
                        this,
                        "Permissions $permission not granted.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            finish()
        }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private inner class FrameAnalyzer : ImageAnalysis.Analyzer {
        private var baos = ByteArrayOutputStream()
        override fun analyze(image: ImageProxy) {
            if (publishing) {
                val bitmap = image.toBitmap()
                when (format) {
                    0 -> {
                        node.publishCompressedImage(toJpegMsg(bitmap, 30))
                    }

                    1 -> {
                        node.publishCompressedImage(toPngMsg(bitmap, 30))
                    }

                    2 -> {
                        node.publishCompressedImage(toWebpMsg(bitmap, 30))
                    }

                    3 -> {
                        node.publishCompressedImage(toWebpLosslessMsg(bitmap, 30))
                    }
                }
            }
            image.close()
        }

        private fun toPngMsg(bitmap: Bitmap, quality: Int, scale: Double = 1.0): CompressedImage {
            if (scale == 1.0) {
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
            msg.header.frameId = getFrameId()
            msg.header.stamp = getStamp()
            msg.format = "png"
            msg.data = baos.toByteArray().asList()
            baos.reset()
            return msg
        }

        private fun toJpegMsg(bitmap: Bitmap, quality: Int, scale: Double = 1.0): CompressedImage {
            if (scale == 1.0) {
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
            msg.header.frameId = getFrameId()
            msg.header.stamp = getStamp()
            msg.format = "jpeg"
            msg.data = baos.toByteArray().asList()
            baos.reset()
            return msg
        }

        private fun toWebpMsg(bitmap: Bitmap, quality: Int, scale: Double = 1.0): CompressedImage {
            if (scale == 1.0) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    bitmap.compress(Bitmap.CompressFormat.WEBP, quality, baos)
                } else {
                    bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, baos)
                }
            } else if (scale > 1.0) {
                val width = (bitmap.width / scale).toInt()
                val height = (bitmap.height / scale).toInt()
                val cBitmap = Bitmap.createScaledBitmap(bitmap, width, height, true)
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    cBitmap.compress(Bitmap.CompressFormat.WEBP, quality, baos)
                } else {
                    cBitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, baos)
                }
            } else {
                Log.e(TAG, "scale should be larger than 1.0")
                return CompressedImage()
            }
            val msg = CompressedImage()
            msg.header.frameId = getFrameId()
            msg.header.stamp = getStamp()
            msg.format = "webp"
            msg.data = baos.toByteArray().asList()
            baos.reset()
            return msg
        }

        private fun toWebpLosslessMsg(
            bitmap: Bitmap,
            quality: Int,
            scale: Double = 1.0
        ): CompressedImage {
            if (scale == 1.0) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    bitmap.compress(Bitmap.CompressFormat.WEBP, 100, baos)
                } else {
                    bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, quality, baos)
                }
            } else if (scale > 1.0) {
                val width = (bitmap.width / scale).toInt()
                val height = (bitmap.height / scale).toInt()
                val cBitmap = Bitmap.createScaledBitmap(bitmap, width, height, true)
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    cBitmap.compress(Bitmap.CompressFormat.WEBP, 100, baos)
                } else {
                    cBitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, quality, baos)
                }
            } else {
                Log.e(TAG, "scale should be larger than 1.0")
                return CompressedImage()
            }
            val msg = CompressedImage()
            msg.header.frameId = getFrameId()
            msg.header.stamp = getStamp()
            msg.format = "webp"
            msg.data = baos.toByteArray().asList()
            baos.reset()
            return msg
        }
    }

    companion object {
        val REQUIRED_PERMISSIONS = arrayOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO,
        )

        const val TAG = "fluodebug"
    }
}

fun getStamp(delta: Long = 0): builtin_interfaces.msg.Time {
    val msg = builtin_interfaces.msg.Time()
    val instant = Instant.now() - Duration.ofMillis(delta)
    msg.sec = instant.epochSecond.toInt()
    msg.nanosec = instant.nano
    return msg
}