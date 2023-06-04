package dev.dai.cameraxcomposeresolutionselectorsample

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.camera.core.AspectRatio
import androidx.camera.core.AspectRatio.Ratio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCapture.OutputFileResults
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.MeteringPoint
import androidx.camera.core.Preview
import androidx.camera.core.TorchState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionSelector.ALLOWED_RESOLUTIONS_SLOW
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.concurrent.futures.await
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.launch

private const val FILENAME = "yyyy-MM-dd-HH-mm-ss-SSS"
private const val PHOTO_TYPE = "image/jpeg"
private const val MIN_SCALE_RATIO = 1.0f

@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val providerState = produceState(
        initialValue = null as ProcessCameraProvider?,
        key1 = context,
        key2 = lifecycleOwner,
        producer = {
            value = ProcessCameraProvider.getInstance(context).await()
        }
    )
    val snackBarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        snackbarHost = { SnackbarHost(snackBarHostState) }
    ) { innerPadding ->
        val provider = providerState.value
        if (provider != null) {
            if (provider.availableCameraInfos.isEmpty()) {
                Text(
                    text = stringResource(id = R.string.message_not_found_available_camera),
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .wrapContentSize()
                )
            } else {
                CameraContent(
                    provider = provider,
                    modifier = Modifier.padding(innerPadding),
                    onImageSaveSuccess = { uri ->
                        val message = if (uri != null) {
                            context.getString(R.string.message_success_save_image, uri.toString())
                        } else {
                            context.getString(R.string.message_failed_save_image)
                        }
                        scope.launch { snackBarHostState.showSnackbar(message) }
                    },
                    onImageSaveFailed = {
                        Log.e("CameraScreen", it.message, it)
                        scope.launch {
                            snackBarHostState.showSnackbar(context.getString(R.string.message_failed_save_image))
                        }
                    },
                    onError = {
                        Log.e("CameraScreen", it.cause?.message, it.cause)
                        when (it.type) {
                            CameraState.ErrorType.RECOVERABLE -> Unit
                            CameraState.ErrorType.CRITICAL -> {
                                val message = context.getString(
                                    if (it.code == CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED) {
                                        R.string.message_error_do_not_disturb_mode_enabled
                                    } else {
                                        R.string.message_failed_binding_camera
                                    }
                                )
                                scope.launch { snackBarHostState.showSnackbar(message) }
                            }
                        }
                    }
                )
            }
        } else {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .wrapContentSize()
            )
        }
    }
}

@Composable
private fun CameraContent(
    provider: ProcessCameraProvider,
    modifier: Modifier = Modifier,
    onImageSaveSuccess: (Uri?) -> Unit,
    onImageSaveFailed: (Throwable) -> Unit,
    onError: (CameraState.StateError) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var currentLensFacing: CameraSelector by remember {
        mutableStateOf(
            if (provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                CameraSelector.DEFAULT_BACK_CAMERA
            } else {
                CameraSelector.DEFAULT_FRONT_CAMERA
            }
        )
    }
    val canFlipCamera = provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) &&
            provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
    var currentAspectRatio: Int by remember { mutableStateOf(AspectRatio.RATIO_4_3) }
    val previewView = remember { PreviewView(context) }
    val imageCapture = remember(currentAspectRatio) {
        ImageCapture.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(
                        if (currentAspectRatio == AspectRatio.RATIO_16_9) {
                            AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
                        } else {
                            AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                        }
                    )
                    .setAllowedResolutionMode(ALLOWED_RESOLUTIONS_SLOW)
                    .build()
            )
            .build()
    }
    var bindingCamera: Camera? by remember {
        mutableStateOf(null)
    }
    var isErrorCameraBinding: Boolean by remember { mutableStateOf(false) }
    var isLoading: Boolean by remember { mutableStateOf(false) }
    val cameraState = bindingCamera?.cameraInfo?.cameraState?.observeAsState()
    val torchState = bindingCamera?.cameraInfo?.torchState?.observeAsState(TorchState.OFF)
    val hasFlashUnit = bindingCamera?.cameraInfo?.hasFlashUnit() ?: false

    LaunchedEffect(currentLensFacing, currentAspectRatio) {
        try {
            provider.unbindAll()
            val camera = provider.bindToLifecycle(
                lifecycleOwner,
                currentLensFacing,
                imageCapture,
                Preview.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(
                                if (currentAspectRatio == AspectRatio.RATIO_16_9) {
                                    AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
                                } else {
                                    AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                                }
                            )
                            .build()
                    )
                    .build()
                    .apply {
                        setSurfaceProvider(previewView.surfaceProvider)
                    }
            )

            val gestureDetector = GestureDetector(
                context,
                object : SimpleOnGestureListener() {
                    override fun onDown(e: MotionEvent): Boolean = true

                    override fun onSingleTapUp(e: MotionEvent): Boolean {
                        val meteringPointFactory = previewView.meteringPointFactory
                        val focusPoint = meteringPointFactory.createPoint(e.x, e.y)
                        focus(camera, focusPoint)
                        return true
                    }
                }
            )

            val scaleGestureDetector = ScaleGestureDetector(
                context,
                object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    override fun onScale(detector: ScaleGestureDetector): Boolean {
                        scale(camera, detector.scaleFactor)
                        return true
                    }
                }
            )

            previewView.setOnTouchListener { view, event ->
                var didConsume = scaleGestureDetector.onTouchEvent(event)
                if (!scaleGestureDetector.isInProgress) {
                    didConsume = gestureDetector.onTouchEvent(event)
                }
                if (event.action == MotionEvent.ACTION_UP) {
                    view.performClick()
                }
                didConsume
            }

            bindingCamera = camera
        } catch (e: Exception) {
            Log.e("CameraContent", e.message, e)
            isErrorCameraBinding = true
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            modifier = Modifier
                .then(
                    if (currentAspectRatio == AspectRatio.RATIO_16_9) {
                        Modifier
                            .fillMaxWidth()
                            .wrapContentHeight(Alignment.Top)
                    } else {
                        Modifier.fillMaxSize()
                    }
                )
                .aspectRatio(currentAspectRatio.mapToFloat()),
            factory = {
                previewView.also {
                    // https://issuetracker.google.com/issues/242463987
                    it.clipToOutline = true
                }
            }
        )

        CameraUiController(
            enabled = cameraState?.value?.type == CameraState.Type.OPEN,
            hasFlashUnit = hasFlashUnit,
            torchState = torchState?.value,
            currentAspectRatio = currentAspectRatio,
            canFlipCamera = canFlipCamera,
            onClickTorch = {
                val camera = bindingCamera ?: return@CameraUiController
                camera.cameraControl.enableTorch(torchState?.value == TorchState.OFF)
            },
            onClickAspectRatio = {
                currentAspectRatio = if (currentAspectRatio == AspectRatio.RATIO_16_9) {
                    AspectRatio.RATIO_4_3
                } else {
                    AspectRatio.RATIO_16_9
                }
            },
            onClickShutter = {
                isLoading = true
                takePhoto(
                    context,
                    imageCapture,
                    onImageSaved = {
                        isLoading = false
                        onImageSaveSuccess(it)
                    },
                    onError = {
                        isLoading = false
                        onImageSaveFailed(it)
                    }
                )
            },
            onClickFlipCamera = {
                val camera = bindingCamera ?: return@CameraUiController
                currentLensFacing =
                    if (camera.cameraInfo.lensFacing == CameraSelector.LENS_FACING_BACK) {
                        CameraSelector.DEFAULT_FRONT_CAMERA
                    } else {
                        CameraSelector.DEFAULT_BACK_CAMERA
                    }
            }
        )

        cameraState?.value?.let {
            when (it.type) {
                CameraState.Type.PENDING_OPEN -> {
                    isLoading = false
                    Text(
                        text = stringResource(id = R.string.message_pending_open_camera),
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                            .wrapContentSize()
                    )
                }

                CameraState.Type.OPEN -> {
                    isLoading = false
                }

                else -> {
                    isLoading = true
                }
            }

            it.error?.let { stateError ->
                onError(stateError)
            }
        }

        when {
            isErrorCameraBinding -> {
                Text(
                    text = stringResource(id = R.string.message_failed_binding_camera),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .wrapContentSize()
                )
            }

            isLoading -> {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier
                        .fillMaxSize()
                        .wrapContentSize()
                )
            }
        }
    }
}

@Composable
private fun CameraUiController(
    enabled: Boolean,
    hasFlashUnit: Boolean,
    torchState: Int?,
    @Ratio currentAspectRatio: Int,
    canFlipCamera: Boolean,
    onClickTorch: () -> Unit,
    onClickAspectRatio: () -> Unit,
    onClickShutter: () -> Unit,
    onClickFlipCamera: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        if (hasFlashUnit) {
            IconButton(
                onClick = onClickTorch,
                enabled = enabled,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
            ) {
                Icon(
                    painter = painterResource(
                        id = if (torchState == TorchState.ON) {
                            R.drawable.baseline_flashlight_on
                        } else {
                            R.drawable.baseline_flashlight_off
                        }
                    ),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier.size(32.dp)
                )
            }
        }

        IconButton(
            onClick = onClickAspectRatio,
            enabled = enabled,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(16.dp)
        ) {
            Icon(
                painter = painterResource(
                    id = if (currentAspectRatio == AspectRatio.RATIO_16_9) {
                        R.drawable.ic_crop_wide
                    } else {
                        R.drawable.ic_crop_normal
                    }
                ),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.size(32.dp)
            )
        }

        Icon(
            painter = painterResource(id = R.drawable.ic_shutter),
            contentDescription = null,
            tint = Color.Unspecified,
            modifier = Modifier
                .clickable(enabled = enabled, onClick = onClickShutter)
                .align(Alignment.BottomCenter)
                .padding(32.dp)
                .size(70.dp)
        )

        if (canFlipCamera) {
            IconButton(
                onClick = onClickFlipCamera,
                enabled = enabled,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 52.dp)
                    .size(32.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_flip_camera),
                    contentDescription = null,
                    tint = Color.Unspecified
                )
            }
        }
    }
}

private fun @receiver:Ratio Int.mapToFloat(): Float {
    return when (this) {
        AspectRatio.RATIO_16_9 -> 9f / 16f
        else -> 3f / 4f
    }
}

private fun focus(camera: Camera, meteringPoint: MeteringPoint) {
    val meteringAction = FocusMeteringAction.Builder(meteringPoint).build()
    camera.cameraControl.startFocusAndMetering(meteringAction)
}

private fun scale(camera: Camera, scaleFactor: Float) {
    val zoomState = camera.cameraInfo.zoomState.value ?: return
    val currentZoomRatio = zoomState.zoomRatio
    camera.cameraControl.setZoomRatio(
        // MIN_SCALE_RATIO倍からmaxZoomRatioまでの範囲でズームできる
        min(
            max(
                currentZoomRatio * speedUpZoomBy2X(scaleFactor),
                MIN_SCALE_RATIO
            ),
            zoomState.maxZoomRatio
        )
    )
}

// 等倍でのピンチイン・アウトのズームスピードだと遅いので2倍で操作できるようにする
private fun speedUpZoomBy2X(scaleFactor: Float): Float {
    return if (scaleFactor > 1f) {
        1.0f + (scaleFactor - 1.0f) * 2
    } else {
        1.0f - (1.0f - scaleFactor) * 2
    }
}

private fun takePhoto(
    context: Context,
    imageCapture: ImageCapture,
    onImageSaved: (Uri?) -> Unit,
    onError: (ImageCaptureException) -> Unit
) {
    val fileName = SimpleDateFormat(FILENAME, Locale.JAPAN).format(System.currentTimeMillis())
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
        put(MediaStore.MediaColumns.MIME_TYPE, PHOTO_TYPE)
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX")
        }
    }

    val outputOptions = ImageCapture.OutputFileOptions
        .Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        )
        .build()

    imageCapture.takePicture(
        outputOptions,
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: OutputFileResults) {
                onImageSaved(outputFileResults.savedUri)
            }

            override fun onError(exception: ImageCaptureException) {
                onError(exception)
            }
        }
    )
}
