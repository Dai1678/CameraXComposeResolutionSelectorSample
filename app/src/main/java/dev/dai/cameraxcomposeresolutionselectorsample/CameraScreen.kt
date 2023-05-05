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
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCapture.OutputFileResults
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.MeteringPoint
import androidx.camera.core.Preview
import androidx.camera.core.TorchState
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionSelector.HIGH_RESOLUTION_FLAG_ON
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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

@OptIn(ExperimentalMaterial3Api::class)
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
    var bindingState: BindingState by remember(context, provider, lifecycleOwner) {
        mutableStateOf(BindingState.Initial)
    }
    val imageCapture = remember(context, provider, lifecycleOwner) {
        ImageCapture.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setHighResolutionEnabledFlag(HIGH_RESOLUTION_FLAG_ON)
                    .build()
            )
            .build()
    }
    var isLoading by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { PreviewView(it) },
            modifier = Modifier
                .fillMaxSize()
                .aspectRatio(4f / 3f),
            update = { previewView ->
                if (bindingState is BindingState.Initial) {
                    bindingState = try {
                        provider.unbindAll()
                        val camera = provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            imageCapture,
                            Preview.Builder()
                                .build()
                                .apply {
                                    setSurfaceProvider(previewView.surfaceProvider)
                                }
                        )

                        val gestureDetector = GestureDetector(
                            previewView.context,
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
                            previewView.context,
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

                        BindingState.Success(camera)
                    } catch (e: Exception) {
                        BindingState.Failed
                    }
                }
            }
        )

        if (isLoading) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier
                    .fillMaxSize()
                    .wrapContentSize()
            )
        }

        when (val state = bindingState) {
            BindingState.Initial -> { isLoading = true }

            BindingState.Failed -> {
                isLoading = false
                Text(
                    text = stringResource(id = R.string.message_failed_binding_camera),
                    modifier = Modifier
                        .fillMaxSize()
                        .wrapContentSize()
                )
            }

            is BindingState.Success -> {
                val cameraState = state.camera.cameraInfo.cameraState.observeAsState()
                val torchState = state.camera.cameraInfo.torchState.observeAsState(TorchState.OFF)
                val hasFlashUnit = state.camera.cameraInfo.hasFlashUnit()

                cameraState.value?.let {
                    when (it.type) {
                        CameraState.Type.PENDING_OPEN -> {
                            isLoading = false
                            Text(
                                text = stringResource(id = R.string.message_pending_open_camera),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .wrapContentSize()
                            )
                        }

                        CameraState.Type.OPEN -> {
                            isLoading = false
                            CameraUiController(
                                cameraControl = state.camera.cameraControl,
                                hasFlashUnit = hasFlashUnit,
                                torchState = torchState.value,
                                takePhoto = {
                                    isLoading = true
                                    takePhoto(
                                        context,
                                        imageCapture,
                                        onImageSaved = { results ->
                                            isLoading = false
                                            onImageSaveSuccess(results.savedUri)
                                        },
                                        onError = { exception ->
                                            isLoading = false
                                            onImageSaveFailed(exception)
                                        }
                                    )
                                }
                            )
                        }

                        else -> { isLoading = true }
                    }

                    it.error?.let { stateError ->
                        onError(stateError)
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraUiController(
    cameraControl: CameraControl,
    hasFlashUnit: Boolean,
    torchState: Int,
    takePhoto: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        if (hasFlashUnit) {
            IconButton(
                onClick = {
                    cameraControl.enableTorch(torchState == TorchState.OFF)
                },
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
                    modifier = Modifier.size(32.dp)
                )
            }
        }

        IconButton(
            onClick = takePhoto,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(32.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_shutter),
                contentDescription = null,
                modifier.size(64.dp)
            )
        }
    }
}

private sealed interface BindingState {
    object Initial : BindingState
    object Failed : BindingState
    data class Success(val camera: Camera) : BindingState
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
    onImageSaved: (OutputFileResults) -> Unit,
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
                onImageSaved(outputFileResults)
            }

            override fun onError(exception: ImageCaptureException) {
                onError(exception)
            }
        }
    )
}
