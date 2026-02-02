package ai.openclaw.android.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.util.Size
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.core.content.ContextCompat
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Camera Server - HTTP API pentru camera tabletei
 * 
 * Endpoints:
 * - GET  /camera/info          - Info camere disponibile
 * - POST /camera/snap          - Face o poză
 * - POST /camera/clip/start    - Începe înregistrare video
 * - POST /camera/clip/stop     - Oprește înregistrare
 * - GET  /camera/gallery       - Listează fișiere media
 * - GET  /camera/file/{name}   - Descarcă fișier
 * - DELETE /camera/file/{name} - Șterge fișier
 * 
 * Port: 8893
 */
class CameraServer(
    private val context: Context,
    private val port: Int = 8893
) : NanoHTTPD(port) {
    
    private val json = Json { prettyPrint = true }
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var cameraProvider: ProcessCameraProvider? = null
    
    private val outputDirectory: File by lazy {
        File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "OpenClawCamera").apply {
            mkdirs()
        }
    }
    
    @Serializable
    data class CameraInfo(
        val available: Boolean,
        val hasFront: Boolean,
        val hasBack: Boolean,
        val supportedResolutions: List<ResolutionInfo>,
        val outputDirectory: String
    )
    
    @Serializable
    data class ResolutionInfo(
        val width: Int,
        val height: Int,
        val aspectRatio: String
    )
    
    @Serializable
    data class SnapResponse(
        val success: Boolean,
        val filename: String?,
        val path: String?,
        val size: Long?,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    @Serializable
    data class ClipResponse(
        val success: Boolean,
        val status: String, // idle, recording
        val filename: String? = null,
        val duration: Long? = null,
        val error: String? = null
    )
    
    @Serializable
    data class GalleryResponse(
        val files: List<MediaFileInfo>,
        val totalSize: Long,
        val count: Int
    )
    
    @Serializable
    data class MediaFileInfo(
        val name: String,
        val path: String,
        val size: Long,
        val type: String, // image, video
        val created: Long,
        val thumbnail: String? = null
    )
    
    init {
        initializeCamera()
    }
    
    private fun initializeCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(context))
    }
    
    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: return
        
        // Preview (opțional, pentru debug)
        val preview = Preview.Builder()
            .build()
        
        // Image Capture
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
        
        // Video Capture (Android 11+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val recorder = Recorder.Builder()
                .setExecutor(ContextCompat.getMainExecutor(context))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)
        }
        
        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                context as androidx.lifecycle.LifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageCapture,
                videoCapture
            )
        } catch (e: Exception) {
            android.util.Log.e("CameraServer", "Use case binding failed: ${e.message}")
        }
    }
    
    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method
        
        return try {
            when {
                uri == "/camera/info" && method == Method.GET ->
                    handleInfo()
                uri == "/camera/snap" && method == Method.POST ->
                    handleSnap()
                uri == "/camera/clip/start" && method == Method.POST ->
                    handleClipStart()
                uri == "/camera/clip/stop" && method == Method.POST ->
                    handleClipStop()
                uri == "/camera/gallery" && method == Method.GET ->
                    handleGallery()
                uri.startsWith("/camera/file/") && method == Method.GET ->
                    handleFileDownload(uri.substringAfterLast("/"))
                uri.startsWith("/camera/file/") && method == Method.DELETE ->
                    handleFileDelete(uri.substringAfterLast("/"))
                method == Method.OPTIONS ->
                    handleOptions()
                else ->
                    newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "{\"error\":\"Not Found\"}")
            }
        } catch (e: Exception) {
            android.util.Log.e("CameraServer", "Error: ${e.message}")
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "{\"error\":\"${e.message}\"}")
        }
    }
    
    private fun handleInfo(): Response {
        val info = CameraInfo(
            available = cameraProvider != null,
            hasFront = hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA),
            hasBack = hasCamera(CameraSelector.DEFAULT_BACK_CAMERA),
            supportedResolutions = listOf(
                ResolutionInfo(1920, 1080, "16:9"),
                ResolutionInfo(1280, 720, "16:9"),
                ResolutionInfo(640, 480, "4:3")
            ),
            outputDirectory = outputDirectory.absolutePath
        )
        return newJsonResponse(info)
    }
    
    private fun hasCamera(selector: CameraSelector): Boolean {
        return try {
            cameraProvider?.hasCamera(selector) ?: false
        } catch (e: Exception) {
            false
        }
    }
    
    private fun handleSnap(): Response {
        val imageCapture = imageCapture ?: return newFixedLengthResponse(
            Response.Status.SERVICE_UNAVAILABLE,
            MIME_PLAINTEXT,
            "{\"error\":\"Camera not initialized\"}"
        )
        
        return try {
            val filename = "IMG_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
            val file = File(outputDirectory, filename)
            
            val outputOptions = ImageCapture.OutputFileOptions.Builder(file).build()
            
            // Capture sincron (pentru HTTP)
            var response: Response? = null
            val latch = java.util.concurrent.CountDownLatch(1)
            
            imageCapture.takePicture(
                outputOptions,
                executor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        val result = SnapResponse(
                            success = true,
                            filename = filename,
                            path = file.absolutePath,
                            size = file.length()
                        )
                        response = newJsonResponse(result)
                        latch.countDown()
                    }
                    
                    override fun onError(exception: ImageCaptureException) {
                        response = newFixedLengthResponse(
                            Response.Status.INTERNAL_ERROR,
                            MIME_PLAINTEXT,
                            "{\"error\":\"${exception.message}\"}"
                        )
                        latch.countDown()
                    }
                }
            )
            
            latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
            response ?: newFixedLengthResponse(Response.Status.REQUEST_TIMEOUT, MIME_PLAINTEXT, "{\"error\":\"Timeout\"}")
            
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "{\"error\":\"${e.message}\"}")
        }
    }
    
    private fun handleClipStart(): Response {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return newJsonResponse(ClipResponse(success = false, status = "idle", error = "Requires Android 12+"))
        }
        
        val videoCapture = videoCapture ?: return newJsonResponse(
            ClipResponse(success = false, status = "idle", error = "Video capture not available")
        )
        
        return try {
            if (recording != null) {
                return newJsonResponse(ClipResponse(success = false, status = "recording", error = "Already recording"))
            }
            
            val filename = "VID_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mp4"
            val file = File(outputDirectory, filename)
            
            val mediaStoreOutputOptions = MediaStoreOutputOptions.Builder(
                context.contentResolver,
                android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            ).build()
            
            recording = (videoCapture.output as Recorder).prepareRecording(context, mediaStoreOutputOptions)
                .start(ContextCompat.getMainExecutor(context)) { event ->
                    when (event) {
                        is VideoRecordEvent.Start -> {
                            android.util.Log.i("CameraServer", "Recording started")
                        }
                        is VideoRecordEvent.Finalize -> {
                            if (!event.hasError()) {
                                android.util.Log.i("CameraServer", "Recording saved: ${event.outputResults.outputUri}")
                            } else {
                                android.util.Log.e("CameraServer", "Recording error: ${event.error} - ${event.cause}")
                            }
                            recording = null
                        }
                    }
                }
            
            newJsonResponse(ClipResponse(success = true, status = "recording", filename = filename))
            
        } catch (e: Exception) {
            newJsonResponse(ClipResponse(success = false, status = "idle", error = e.message))
        }
    }
    
    private fun handleClipStop(): Response {
        return try {
            recording?.stop()
            recording?.close()
            recording = null
            
            newJsonResponse(ClipResponse(success = true, status = "idle"))
        } catch (e: Exception) {
            newJsonResponse(ClipResponse(success = false, status = "idle", error = e.message))
        }
    }
    
    private fun handleGallery(): Response {
        val files = outputDirectory.listFiles()?.map { file ->
            MediaFileInfo(
                name = file.name,
                path = file.absolutePath,
                size = file.length(),
                type = if (file.extension.lowercase() in listOf("jpg", "jpeg", "png")) "image" else "video",
                created = file.lastModified()
            )
        }?.sortedByDescending { it.created } ?: emptyList()
        
        val totalSize = files.sumOf { it.size }
        
        return newJsonResponse(GalleryResponse(files = files, totalSize = totalSize, count = files.size))
    }
    
    private fun handleFileDownload(filename: String): Response {
        val file = File(outputDirectory, filename)
        
        return if (file.exists()) {
            val mimeType = when (file.extension.lowercase()) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "mp4" -> "video/mp4"
                else -> "application/octet-stream"
            }
            
            newChunkedResponse(Response.Status.OK, mimeType, file.inputStream())
        } else {
            newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "{\"error\":\"File not found\"}")
        }
    }
    
    private fun handleFileDelete(filename: String): Response {
        val file = File(outputDirectory, filename)
        
        return if (file.exists() && file.delete()) {
            newJsonResponse(mapOf("success" to true, "deleted" to filename))
        } else {
            newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "{\"error\":\"File not found or could not delete\"}")
        }
    }
    
    private fun handleOptions(): Response {
        val response = newFixedLengthResponse(Response.Status.NO_CONTENT, "", "")
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type")
        return response
    }
    
    private fun newJsonResponse(data: Any): Response {
        val jsonString = when (data) {
            is String -> data
            else -> json.encodeToString(data)
        }
        val response = newFixedLengthResponse(Response.Status.OK, "application/json", jsonString)
        response.addHeader("Access-Control-Allow-Origin", "*")
        return response
    }
    
    override fun stop() {
        recording?.stop()
        recording?.close()
        recording = null
        cameraProvider?.unbindAll()
        executor.shutdown()
        scope.cancel()
        super.stop()
    }
}