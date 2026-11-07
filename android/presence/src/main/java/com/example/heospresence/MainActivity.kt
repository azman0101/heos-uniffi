package com.example.heospresence

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlin.math.roundToInt

internal const val TAG = "Presence"
private const val WIDTH = 1280
private const val HEIGHT = 720

/**
 * Test de faisabilité : ouvre la caméra, détecte les visages sur l'appareil et décale les niveaux
 * des arrières selon la place. Aucune image n'est enregistrée ni envoyée. À la sortie de l'écran,
 * la caméra est fermée et les niveaux d'origine sont remis.
 */
class MainActivity : Activity() {
    private lateinit var preview: TextureView
    private lateinit var faceBoxes: FaceOverlay
    private lateinit var status: TextView

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            // Un visage à 3 m reste petit dans l'image
            .setMinFaceSize(0.03f)
            .build(),
    )

    private var thread: HandlerThread? = null
    private var camera: CameraDevice? = null
    private var reader: ImageReader? = null
    private var analyzing = false
    private val tracker = SeatTracker()
    private var lastSeat: Seat? = null
    private var soundState = ""
    private lateinit var rear: RearLevels

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rear = RearLevels(this) { state -> runOnUiThread { soundState = state } }
        preview = TextureView(this).apply { scaleX = -1f } // effet miroir
        faceBoxes = FaceOverlay(this)
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0xB0000000.toInt())
            setPadding(32, 16, 32, 16)
            textSize = 24f
        }
        setContentView(
            FrameLayout(this).apply {
                setBackgroundColor(Color.BLACK)
                addView(preview)
                addView(faceBoxes)
                addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
            },
        )
    }

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            status.text = "Autorisation caméra requise"
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
            return
        }
        rear.start()
        whenPreviewReady { startCamera() }
    }

    override fun onPause() {
        stopCamera()
        lastSeat = null
        rear.restore()
        super.onPause()
    }

    override fun onDestroy() {
        detector.close()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, results: IntArray) {
        if (results.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            status.text = "Autorisation caméra refusée"
        }
        // Accordée : onResume suit la fermeture de la boîte de dialogue
    }

    private fun whenPreviewReady(start: () -> Unit) {
        if (preview.isAvailable) return start()
        preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) = start()
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) = Unit
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) = Unit
        }
    }

    @SuppressLint("MissingPermission") // vérifiée dans onResume
    private fun startCamera() {
        if (camera != null) return
        val manager = getSystemService(CameraManager::class.java)
        val id = manager.cameraIdList.firstOrNull() ?: return fail("Aucune caméra détectée")
        val handler = Handler(HandlerThread("camera").also { it.start(); thread = it }.looper)
        val images = ImageReader.newInstance(WIDTH, HEIGHT, ImageFormat.YUV_420_888, 3)
        images.setOnImageAvailableListener(::analyze, handler)
        reader = images
        Log.i(TAG, "ouverture de la caméra $id")

        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                camera = device
                val texture = preview.surfaceTexture ?: return
                texture.setDefaultBufferSize(WIDTH, HEIGHT)
                val surfaces = listOf(Surface(texture), images.surface)
                @Suppress("DEPRECATION")
                device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        surfaces.forEach(request::addTarget)
                        session.setRepeatingRequest(request.build(), null, handler)
                        Log.i(TAG, "flux démarré ${WIDTH}x$HEIGHT")
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) =
                        fail("Configuration du flux refusée")
                }, handler)
            }

            override fun onDisconnected(device: CameraDevice) {
                device.close()
                camera = null
                fail("Caméra déconnectée")
            }

            override fun onError(device: CameraDevice, error: Int) {
                device.close()
                camera = null
                // 1 = déjà utilisée, 2 = trop de clients, 3 = désactivée
                fail("Erreur caméra $error")
            }
        }, handler)
    }

    private fun stopCamera() {
        camera?.close()
        camera = null
        reader?.close()
        reader = null
        thread?.quitSafely()
        thread = null
        analyzing = false
        Log.i(TAG, "caméra fermée")
    }

    /** Analyse une image à la fois : les autres sont jetées pour rester temps réel. */
    private fun analyze(source: ImageReader) {
        val image = source.acquireLatestImage() ?: return
        if (analyzing) return image.close()
        analyzing = true
        val start = SystemClock.elapsedRealtime()
        detector.process(InputImage.fromMediaImage(image, 0))
            .addOnSuccessListener { faces ->
                show(faces.sortedByDescending { it.boundingBox.width() }, SystemClock.elapsedRealtime() - start)
            }
            .addOnFailureListener { Log.e(TAG, "détection échouée", it) }
            .addOnCompleteListener {
                image.close()
                analyzing = false
            }
    }

    private fun show(faces: List<Face>, ms: Long) {
        // Miroir : -1 = à gauche et +1 = à droite pour une personne qui regarde la TV
        val boxes = faces.map {
            val b = it.boundingBox
            RectF(1f - b.right / WIDTH.toFloat(), b.top / HEIGHT.toFloat(), 1f - b.left / WIDTH.toFloat(), b.bottom / HEIGHT.toFloat())
        }
        faceBoxes.boxes = boxes
        faceBoxes.invalidate()
        val main = boxes.firstOrNull()
        val x = main?.let { it.centerX() * 2 - 1 }
        val text = if (main == null) {
            "Aucun visage · $ms ms"
        } else {
            val size = (main.width() * 100).roundToInt()
            "Visages : ${boxes.size} · X %+.2f · largeur $size %% · $ms ms".format(x)
        }
        val seat = tracker.update(x, SystemClock.elapsedRealtime())
        if (seat != lastSeat) {
            lastSeat = seat
            rear.apply(seat)
        }
        val sound = rear.displayDb(seat)?.let { (left, right) ->
            "Place : ${seat.label} · Surround G %+d dB · D %+d dB".format(left, right)
        } ?: "Place : ${seat.label}"
        status.text = "$text\n$sound · $soundState"
        Log.i(TAG, text)
    }

    private fun fail(message: String) {
        Log.e(TAG, message)
        runOnUiThread { status.text = message }
    }
}

/** Cadres en coordonnées normalisées (0..1), déjà en miroir. */
private class FaceOverlay(context: Context) : View(context) {
    var boxes: List<RectF> = emptyList()
    private val paint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    override fun onDraw(canvas: Canvas) {
        boxes.forEach {
            canvas.drawRect(it.left * width, it.top * height, it.right * width, it.bottom * height, paint)
        }
    }
}
