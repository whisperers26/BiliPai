package com.android.purebilibili.feature.login

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import com.android.purebilibili.core.ui.components.AppTextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** CameraX scanner shared by Bilibili login authorization and BiliPai session transfer. */
@Composable
fun BiliPaiTransferScanner(
    onCode: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleShot: Boolean = false,
    acceptAnyQr: Boolean = false,
    onError: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnCode by rememberUpdatedState(onCode)
    val currentOnError by rememberUpdatedState(onError)
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
    }
    val galleryLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bitmap = runCatching { decodeUriBitmap(context, uri, maxDimension = 2048) }.getOrNull()
        val text = bitmap?.let {
            if (acceptAnyQr) BiliPaiQrDecoder.decodeBitmap(it, acceptAny = true)
            else BiliPaiQrDecoder.decodeBitmap(it)
        }
        if (text != null) {
            currentOnCode(text)
        } else {
            currentOnError("未能从所选图片中识别到受支持的二维码，请换一张图片或直接拍照扫描")
        }
    }
    fun launchGalleryPicker() {
        galleryLauncher.launch(PickVisualMediaRequest(
            ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    LaunchedEffect(Unit) {
        if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (!granted) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
                androidx.compose.material3.Text("需要摄像头权限才能扫描二维码", color = MaterialTheme.colorScheme.onSurfaceVariant)
                AppTextButton(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    androidx.compose.material3.Text("允许使用摄像头")
                }
                AppTextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { androidx.compose.material3.Text("打开权限设置") }
                AppTextButton(onClick = { launchGalleryPicker() }) {
                    androidx.compose.material3.Text("从相册识别")
                }
            }
        }
        return
    }

    val previewView = remember { PreviewView(context) }
    DisposableEffect(lifecycleOwner, previewView, singleShot, acceptAnyQr) {
        val executor = Executors.newSingleThreadExecutor()
        val active = AtomicBoolean(true)
        val delivered = AtomicBoolean(false)
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var boundProvider: ProcessCameraProvider? = null
        var boundPreview: Preview? = null
        var boundAnalysis: ImageAnalysis? = null
        fun reportError() {
            mainExecutor.execute {
                if (active.get()) currentOnError("无法启动扫码，请确认设备有可用摄像头后重试")
            }
        }
        val listener = Runnable {
            if (!active.get()) return@Runnable
            try {
                val provider = providerFuture.get()
                if (!provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                    reportError()
                    return@Runnable
                }
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                var lastDeliveredAt = 0L
                var lastAnalyzedAt = 0L
                var failedFrames = 0
                analysis.setAnalyzer(executor) { image ->
                    try {
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (active.get() && (!singleShot || !delivered.get()) &&
                            now - lastDeliveredAt >= 700L && now - lastAnalyzedAt >= 200L
                        ) {
                            lastAnalyzedAt = now
                            val bytes = image.toLuminanceBytes()
                            if (bytes != null) {
                                val code = if (acceptAnyQr)
                                    BiliPaiQrDecoder.decodeRaw(bytes, image.width, image.height, image.imageInfo.rotationDegrees)
                                    else BiliPaiQrDecoder.decode(bytes, image.width, image.height, image.imageInfo.rotationDegrees)
                                failedFrames = 0
                                code?.let {
                                    if (!singleShot || delivered.compareAndSet(false, true)) {
                                        lastDeliveredAt = now
                                        mainExecutor.execute { if (active.get()) currentOnCode(it) }
                                    }
                                }
                            } else throw IllegalStateException("Invalid luminance frame")
                        }
                    } catch (_: Exception) {
                        // Bad frames must not leak ImageProxy or stall CameraX's analysis pipeline.
                        failedFrames++
                        if (failedFrames >= 3 && delivered.compareAndSet(false, true)) {
                            mainExecutor.execute {
                                if (active.get()) currentOnError("相机画面解析失败，请关闭后重新打开扫码")
                            }
                        }
                    } finally {
                        image.close()
                    }
                }
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                boundProvider = provider
                boundPreview = preview
                boundAnalysis = analysis
            } catch (_: Exception) {
                reportError()
            }
        }
        providerFuture.addListener(listener, mainExecutor)
        onDispose {
            active.set(false)
            boundAnalysis?.clearAnalyzer()
            boundPreview?.let { preview ->
                boundAnalysis?.let { analysis -> boundProvider?.unbind(preview, analysis) }
            }
            executor.shutdown()
        }
    }
    androidx.compose.foundation.layout.Box(modifier = modifier) {
        AndroidView(factory = { previewView }, modifier = Modifier.matchParentSize())
        AppTextButton(
            onClick = { launchGalleryPicker() },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) { androidx.compose.material3.Text("从相册识别") }
    }
}

/** Loads an image Uri into a bitmap capped at [maxDimension] to keep ZXing fast. */
private fun decodeUriBitmap(
    context: android.content.Context,
    uri: Uri,
    maxDimension: Int,
): android.graphics.Bitmap? {
    val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        ?: return null
    var sample = 1
    while (options.outWidth / (sample * 2) >= maxDimension / 2 || options.outHeight / (sample * 2) >= maxDimension / 2) {
        sample *= 2
    }
    val bounds = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
    return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
}

private fun androidx.camera.core.ImageProxy.toLuminanceBytes(): ByteArray? {
    val yPlane = planes.getOrNull(0) ?: return null
    val output = ByteArray(width * height)
    val yBuffer = yPlane.buffer.duplicate()
    val start = yBuffer.position()
    for (row in 0 until height) {
        for (col in 0 until width) {
            val index = start + row * yPlane.rowStride + col * yPlane.pixelStride
            if (index >= yBuffer.limit()) return null
            output[row * width + col] = yBuffer.get(index)
        }
    }
    return output
}
