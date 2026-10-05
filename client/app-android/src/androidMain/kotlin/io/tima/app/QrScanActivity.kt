package io.tima.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode
import io.tima.core.ui.Button
import io.tima.core.ui.ButtonKind
import io.tima.core.ui.Name
import io.tima.core.ui.Secondary
import io.tima.core.words.CurrentWords
import io.tima.core.words.Words
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Сканер кода подключения — «Фраза и устройства → Сканировать код» (решение заказчика
 * 2026-09-30, вариант 1б).
 *
 * ── ЗАЧЕМ СВОЙ, ЕСЛИ ЕСТЬ ШТАТНАЯ КАМЕРА ────────────────────────────────────
 *
 * Код привязки — ссылка `tima://link/v1?…`, и штатная камера телефона открывает по ней
 * приложение сама. Но не у всякого телефона камера читает QR, и не всякая открывает ссылку
 * своей схемы. Разрешение на камеру у приложения уже есть — ради видеозвонков, — так что
 * свой сканер нового вопроса человеку не добавляет. Штатный путь остаётся вторым.
 *
 * ── ПОЧЕМУ CameraX И ZXing ──────────────────────────────────────────────────
 *
 * Работает без сервисов Google (Honor, Huawei), распознавание целиком на устройстве —
 * картинка никуда не уходит. ZXing — чистая Java под Apache 2.0; из него берётся только
 * чтение QR.
 *
 * Результат — тот же, что у ссылки из штатной камеры: строка кода уходит в главное окно, а
 * там — экран «Доверить …?». Код не нашей схемы не принимается: сканер подключения, а не
 * общий.
 *
 * Словарь — ссылкой в конструкторе, как у store (ПЛАН-(Я)-ЯЗЫКА, Я2-беды). Activity создаёт
 * система конструктором без параметров, и Kotlin заводит его сам, когда у всех параметров
 * есть умолчания.
 */
class QrScanActivity(
    private val words: () -> Words = { CurrentWords.value },
) : ComponentActivity() {

    private val scanning: ExecutorService = Executors.newSingleThreadExecutor()
    private val reader = QRCodeReader()
    private val hints = mapOf(
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
    )

    /** Что сказать человеку под видоискателем; `null` — подсказка по умолчанию. */
    private val notice = mutableStateOf<String?>(null)

    @Volatile
    private var done = false

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            start()
        } else {
            Journal.note(LogCode.PERM_DENIED, "сканер кода: камеру не разрешили")
            notice.value = words().auth.scanNoCamera
        }
    }

    private lateinit var preview: PreviewView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preview = PreviewView(this)
        val auth = words().auth
        setContent {
            val said by notice
            io.tima.core.ui.TimaTheme(colors = io.tima.core.ui.TimaColors.light, words = words()) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize())
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.White)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Name(auth.scanTitle)
                    Secondary(said ?: auth.scanHint)
                    Button(label = auth.scanClose, onClick = { finish() }, kind = ButtonKind.Quiet, modifier = Modifier.fillMaxWidth())
                }
            }
            }
        }
        Journal.note(LogCode.SCREEN_OPEN, "scan.code")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            start()
        } else {
            askCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun start() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = runCatching { future.get() }.getOrElse {
                Journal.trouble(LogCode.CALL_DEVICE, "сканер кода: камера не открылась", "почему" to it.message.orEmpty())
                notice.value = words().auth.scanNoCamera
                return@addListener
            }
            val shown = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(scanning) { image -> image.use { read(it) } }
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, shown, analysis)
            }.onFailure {
                Journal.trouble(LogCode.CALL_DEVICE, "сканер кода: камера не открылась", "почему" to it.message.orEmpty())
                notice.value = words().auth.scanNoCamera
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Кадр — яркость без цвета: QR читается по ней, а плоскость Y идёт первой и без копий. */
    private fun read(image: ImageProxy) {
        if (done) return
        val plane = image.planes.firstOrNull() ?: return
        val stride = plane.rowStride
        val width = image.width
        val height = image.height
        val buffer = plane.buffer
        // Последняя строка в буфере бывает короче шага — дописываем нулями до полной.
        val bytes = ByteArray(stride * height)
        buffer.rewind()
        buffer.get(bytes, 0, minOf(buffer.remaining(), bytes.size))
        val text = runCatching {
            val source = PlanarYUVLuminanceSource(bytes, stride, height, 0, 0, width, height, false)
            reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text
        }.getOrElse { if (it !is NotFoundException) null else null } ?: return
        reader.reset()
        if (!text.startsWith(LINK_PREFIX)) {
            notice.value = words().auth.scanNotOurs
            return
        }
        done = true
        Journal.note(LogCode.NET_CHANNEL, "сканер кода: код подключения прочитан")
        runOnUiThread {
            setResult(RESULT_OK, Intent().putExtra(CODE, text))
            finish()
        }
    }

    override fun onDestroy() {
        scanning.shutdown()
        super.onDestroy()
    }

    companion object {
        /** Строка кода в ответе окну. */
        const val CODE = "code"

        /** Код подключения — та же схема, что у ссылки для штатной камеры. */
        private const val LINK_PREFIX = "tima://link/"
    }
}
