package com.example.nettest

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ImageCapture
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.math.pow

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileWriter
import java.io.OutputStream

class MainActivity : AppCompatActivity() {
    private lateinit var ipInput: EditText
    private lateinit var portInput: EditText
    private lateinit var csvName: EditText
    private lateinit var mInput: EditText
    private lateinit var receiveTextView: TextView
    private lateinit var sendTextView: TextView
    private lateinit var errorTextView: TextView

    private var videoFrame : Int = 0
    private lateinit var photoPickerLauncher: ActivityResultLauncher<PickVisualMediaRequest>
    private var fileDescriptor : ParcelFileDescriptor? = null
    private val mediaRetriever = MediaMetadataRetriever()
    private lateinit var cameraProvider: ProcessCameraProvider
    private lateinit var imageCapture: ImageCapture
    private lateinit var cameraExecutor: ExecutorService
    private val cameraHelper = CameraHelper(this)
    private var sensorWidth: Float = 0.0f
    private var focalLength: Float = 0.0f
    private var times = listOf(listOf("Type", "Var", "Count", "Timestamp"))
    companion object {
        private const val CAMERA_PERMISSION_REQUEST_CODE = 100
    }

    private var stopButtonPressed = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        ipInput = findViewById(R.id.ipAddrInput)
        portInput = findViewById(R.id.pInput)
        csvName = findViewById(R.id.csvName)
        sendTextView = findViewById(R.id.sendTextView)
        receiveTextView = findViewById(R.id.receiveTextView)
        errorTextView = findViewById(R.id.errorTextView)

        // Check camera permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // Request CAMERA permission if not granted
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_REQUEST_CODE
            )
        } else {
            cameraExecutor = Executors.newSingleThreadExecutor()
            startCameraSetup()
            cameraHelper.startCamera(this)
        }

        val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        val cameraIdList = cameraManager.cameraIdList
        if (cameraIdList.isNotEmpty()){
            val cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraIdList[0])
            sensorWidth = cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width!!
            focalLength = cameraCharacteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.firstOrNull()!!

            //
        }

        photoPickerLauncher =
            registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
                if (uri != null) {
                    fileDescriptor = contentResolver.openFileDescriptor(uri, "r")
                    mediaRetriever.setDataSource(fileDescriptor?.fileDescriptor)
                }
            }
    }

    private fun startCameraSetup() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            // Set up image capture
            imageCapture = ImageCapture.Builder()
                .build()

            // Bind use cases to camera
            cameraHelper.bindCameraUseCases(cameraProvider, imageCapture)

        }, ContextCompat.getMainExecutor(this))
    }

    private suspend fun captureImage(cameraHelper: CameraHelper): Bitmap? {
        return suspendCancellableCoroutine { continuation ->
            cameraHelper.takePhotoAsBitmap{ bitmap ->
                continuation.resume(bitmap)
            }
        }
    }

    private fun yieldFrameFromVideo(): Bitmap? {
        val image = try {
            mediaRetriever.getFrameAtIndex(videoFrame)
        } catch (e: IllegalArgumentException) {
            null
        }
        videoFrame += 1
        return image
    }

    private fun startMessageListener(listenerPort: Int) {
        CoroutineScope(Dispatchers.IO).launch {
            try {

//                val serverSocket = ServerSocket(listenerPort, 50, InetAddress.getByName("0.0.0.0"))
                val serverSocket = ServerSocket(listenerPort)

                withContext(Dispatchers.Main) {
                    receiveTextView.text = "Listening on port $listenerPort IP ${serverSocket.inetAddress}"
                }
                val clientSocket = serverSocket.accept()

                var receiveCounter = 0
                while (!stopButtonPressed) {
                    val inputStream = clientSocket.inputStream
                    val receivedMessage = inputStream.bufferedReader(Charsets.UTF_8).readLine()
                    System.nanoTime()
                    times += listOf(listOf("Receive", receivedMessage, "$receiveCounter", System.nanoTime().toString()))
                    receiveCounter += 1
                }

                serverSocket.close()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    errorTextView.append("\nError in listener: ${e.message}")
                }
            }
        }
    }

    private fun saveCsvToDownloads(context: Context, fileName: String, data: List<List<String>>) {
        val csvContent = buildCsvContent(data)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // For Android 10 and above (Scoped Storage)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "$fileName.csv")
                put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)

            if (uri != null) {
                resolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(csvContent.toByteArray())
                    println("File saved to Downloads: $uri")
                }
            } else {
                println("Failed to create file in Downloads.")
            }
        } else {
            // For Android 9 and below (Legacy storage)
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(downloadsDir, "$fileName.csv")
            try {
                FileWriter(file).use { writer ->
                    writer.write(csvContent)
                }
                println("File saved to Downloads: ${file.absolutePath}")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun buildCsvContent(data: List<List<String>>): String {
        return data.joinToString("\n") { row -> row.joinToString(";") }
    }

    fun btnSaveClick(view: View){
        saveCsvToDownloads(this, "${csvName.text}", times)
    }
    fun btnStartClick(view: View) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val portNumber = portInput.text.toString().toInt()
                val ipAddress = ipInput.text.toString()

                // Start the message listener on a separate port
                val listenerPort = 9797 // Replace with your desired port number
                startMessageListener(listenerPort)

                withContext(Dispatchers.Main) {
                    sendTextView.text = "Connecting to $ipAddress:$portNumber"
                }

                // Connect to the server
                val client = Socket(ipAddress, portNumber)
                val outputStream = client.outputStream
                val stream = ByteArrayOutputStream()

                var sendCounter = 0
                while (!stopButtonPressed) {
                    stream.reset()

                    // Capture image
//                    val capturedImage = captureImage(cameraHelper)
                    times += listOf(listOf("Send", "Frame", "$sendCounter", System.nanoTime().toString()))
                    val capturedImage = yieldFrameFromVideo()

                    if (capturedImage == null) {
                        withContext(Dispatchers.Main) {
                            errorTextView.text = "Image capture failed"
                        }
                        break
                    }

                    // Compress image to JPEG
                    val isCompressed = capturedImage.compress(Bitmap.CompressFormat.JPEG, 85, stream)
                    if (!isCompressed) {
                        withContext(Dispatchers.Main) {
                            errorTextView.text = "Image compression failed"
                        }
                        continue
                    }

                    val imageByteArray = stream.toByteArray()
                    val imageSize = imageByteArray.size

                    // Ensure the image size is valid
                    if (imageSize <= 0) {
                        withContext(Dispatchers.Main) {
                            errorTextView.text = "Invalid image size"
                        }
                        continue
                    }

                    // Prepare and send the size header
                    val sizeHeader = imageSize.toString().padStart(10, '0')
                    outputStream.write(sizeHeader.toByteArray(Charsets.UTF_8))
                    outputStream.flush()
                    outputStream.write(imageByteArray)
                    outputStream.flush()
                    sendCounter += 1
                }
                sendTextView.text = "Video sent"

                // Close resources
                client.close()
                stream.close()

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    errorTextView.text = "Error: ${e.message}"
                }
            }
        }
    }

    fun btnPickClick(view: View){
        photoPickerLauncher.launch(PickVisualMediaRequest(mediaType = ActivityResultContracts.PickVisualMedia.VideoOnly))
    }


    fun btnStopClick(view: View){
        stopButtonPressed = true
    }
//    private suspend fun sendAndReceive(ipAddress: String, portNumber: Int, message: String): String {
//        val client = withContext(Dispatchers.IO) {
//            Socket(ipAddress, portNumber)
//        }
//
//        // Capture image from the camera
//        val stream = ByteArrayOutputStream()
//        withContext(Dispatchers.IO) { return@withContext captureImage(cameraHelper) }?.compress(
//            Bitmap.CompressFormat.PNG,
//            100,
//            stream
//        )
//        val imageByteArray = stream.toByteArray()
//        val bufferSize = imageByteArray.size.toString()
//
//        val response = withContext(Dispatchers.IO) {
//            client.outputStream.write("EST{\"buffer size\":$bufferSize,\"sensor width\":$sensorWidth,\"focal length\":$focalLength}".toByteArray())//message.toByteArray())
//            client.outputStream.flush()
//
//            val inputStream = client.getInputStream()
//            val buffer = ByteArray(4096)
//            val bytesRead = inputStream.read(buffer)
//            val response = String(buffer, 0, bytesRead)
//            return@withContext response
//        }
//
//        withContext(Dispatchers.IO) {
//            client.close()
//        }
//        return response
//    }
}