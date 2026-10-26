package org.ntust.app.tigerduck.ui.screen.classtable.sharing

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.TorchState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.ntust.app.tigerduck.core.strings.R
import org.ntust.app.tigerduck.util.logSummary
import java.util.concurrent.Executors

/**
 * The import page's camera: CameraX's preview fills the area and ZXing reads each frame, on the
 * device alone, so scanning sends nothing anywhere. A share code it sees goes to [onScanned],
 * which says whether the import took it; see [RepeatedQrCodeFilter] for a code kept in view. It
 * asks for the camera when the page opens, and with access refused says so, with a way to the
 * app's settings, as the iOS page does. Without a camera the area stays empty, as on iOS; a
 * camera that does not start offers Retry. Reading a code from a picture is `QrCodeImageReader`.
 * [torch] hears of the camera bound, for the page's flashlight toggle, which this does not draw.
 */
@Composable
fun QrScannerCamera(onScanned: (String) -> ClassTableImportModel.ScanAnswer, torch: ScannerTorch, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
        LaunchedEffect(torch) { torch.unavailable() }
        return
    }
    // Saved, so turning the phone does not ask again.
    var granted by rememberSaveable { mutableStateOf(hasCameraPermission(context)) }
    var refused by rememberSaveable { mutableStateOf(false) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        refused = !it
    }
    LaunchedEffect(Unit) { if (!granted && !refused) request.launch(Manifest.permission.CAMERA) }
    // Back from the app's settings page, access may have been granted.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = hasCameraPermission(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    when {
        granted -> CameraPreview(onScanned = onScanned, torch = torch, modifier = modifier)
        refused -> CameraMessage(
            message = stringResource(R.string.class_table_import_camera_denied),
            action = stringResource(R.string.action_go_to_settings),
            onAction = { openAppSettings(context) },
            modifier = modifier,
        )
    }
    LaunchedEffect(granted, refused) { if (!granted && refused) torch.unavailable() }
}

@Composable
private fun CameraMessage(message: String?, action: String, onAction: () -> Unit, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (message != null) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
            }
            FilledTonalButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun CameraPreview(onScanned: (String) -> ClassTableImportModel.ScanAnswer, torch: ScannerTorch, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latest by rememberUpdatedState(onScanned)
    var surfaceRequest by remember { mutableStateOf<SurfaceRequest?>(null) }
    // A camera that did not start, as when another app holds it, offers Retry, which starts over.
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner, attempt) {
        val executor = Executors.newSingleThreadExecutor()
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val session = CameraSession()
        val providerFuture = try {
            ProcessCameraProvider.getInstance(context)
        } catch (e: RuntimeException) {
            Log.w(TAG, "No camera provider: ${e.logSummary()}")
            null
        }
        if (providerFuture == null) failed = true
        providerFuture?.addListener({
            val cameraProvider = try {
                providerFuture.get()
            } catch (e: Exception) {
                Log.w(TAG, "No camera provider: ${e.logSummary()}")
                if (session.isActive) {
                    failed = true
                    torch.unavailable()
                }
                return@addListener
            }
            session.start {
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider { request -> surfaceRequest = request }
                }
                // Codes go to the import on the main thread, where it keeps its state; one it
                // could not take yet, as while a dialog is over the scanner, is read again.
                val handOff = QrCodeHandOff(RepeatedQrCodeFilter(), post = mainExecutor::execute) { text -> latest(text) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(executor, QrFrameReader(handOff)) }
                val camera = try {
                    cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector(cameraProvider), preview, analysis)
                } catch (e: Exception) {
                    Log.w(TAG, "The camera did not start: ${e.logSummary()}")
                    failed = true
                    torch.unavailable()
                    null
                }
                // The torch follows what the camera reports, which turns it off when the camera
                // closes, as in the background, and opens it again with the torch off.
                val torchState = camera?.cameraInfo?.torchState
                val torchObserver = Observer<Int> { torch.stateChanged(on = it == TorchState.ON) }
                if (camera != null) {
                    torch.bound(
                        if (camera.cameraInfo.hasFlashUnit()) { on -> camera.cameraControl.enableTorch(on) } else null,
                    )
                    torchState?.observe(lifecycleOwner, torchObserver)
                }
                return@start {
                    // A frame still being read, or a code posted, must not reach the page that left.
                    handOff.close()
                    torchState?.removeObserver(torchObserver)
                    // Off before the camera goes, so it never stays lit after the page.
                    torch.unbinding()
                    cameraProvider.unbind(preview, analysis)
                }
            }
        }, mainExecutor)
        onDispose {
            session.dispose()
            executor.shutdown()
        }
    }
    if (failed) {
        CameraMessage(
            message = null,
            action = stringResource(R.string.action_retry),
            onAction = {
                failed = false
                surfaceRequest = null
                attempt++
            },
            modifier = modifier,
        )
        return
    }
    surfaceRequest?.let { request ->
        CameraXViewfinder(surfaceRequest = request, contentScale = ContentScale.Crop, modifier = modifier.fillMaxSize())
    }
}

/** The back camera, or on a device without one, the front one or whichever there is. */
private fun cameraSelector(provider: ProcessCameraProvider): CameraSelector = listOf(
    CameraSelector.DEFAULT_BACK_CAMERA,
    CameraSelector.DEFAULT_FRONT_CAMERA,
).firstOrNull { selector ->
    try {
        provider.hasCamera(selector)
    } catch (_: Exception) {
        false
    }
} ?: provider.availableCameraInfos.firstOrNull()?.cameraSelector ?: CameraSelector.DEFAULT_BACK_CAMERA

/** Reads each frame's brightness with ZXing, and hands each code to [handOff]; see [RepeatedQrCodeFilter] for a code kept in view. */
private class QrFrameReader(private val handOff: QrCodeHandOff) : ImageAnalysis.Analyzer {
    private val reader = QRCodeReader()
    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
    )

    override fun analyze(image: ImageProxy) {
        image.use {
            val plane = it.planes[0]
            val buffer = plane.buffer
            val data = ByteArray(buffer.remaining()).also { bytes -> buffer.get(bytes) }
            // The luminance plane as the sensor gives it: ZXing reads a code at any angle.
            val source = PlanarYUVLuminanceSource(data, plane.rowStride, it.height, 0, 0, it.width, it.height, false)
            val text = try {
                reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text
            } catch (_: ReaderException) {
                null
            } catch (e: RuntimeException) {
                // A frame of anything the camera points at: no picture may stop the scanner.
                Log.w(TAG, "A frame did not read: ${e.logSummary()}")
                null
            } finally {
                reader.reset()
            }
            if (text != null) handOff.read(text, System.currentTimeMillis())
        }
    }
}

private fun hasCameraPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
    )
}

private const val TAG = "QrScannerCamera"
