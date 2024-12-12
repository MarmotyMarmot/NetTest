package com.example.nettest

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
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
class MainActivity : AppCompatActivity() {
    private lateinit var ipInput: EditText
    private lateinit var portInput: EditText
    private lateinit var mInput: EditText
    private lateinit var receiveTextView: TextView
    private lateinit var sendTextView: TextView
    private lateinit var errorTextView: TextView

    private lateinit var cameraProvider: ProcessCameraProvider
    private lateinit var imageCapture: ImageCapture
    private lateinit var cameraExecutor: ExecutorService
    private val cameraHelper = CameraHelper(this)
    private var sensorWidth: Float = 0.0f
    private var focalLength: Float = 0.0f
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

//    fun btnStartClick(view: View){
//        CoroutineScope(Dispatchers.IO).launch {
//            val portNumber = portInput.text.toString().toInt()
//            val ipAddress = ipInput.text.toString()
//            val message = mInput.text.toString()
//            val displayedText = "Sent $message"
//            rTextView.text = displayedText
//
//            val client = withContext(Dispatchers.IO) {Socket(ipAddress, portNumber)}
//            val outputStream = client.outputStream
//            val inputStream = client.getInputStream()
//            var bufferSize: Int
//
//            while(!stopButtonPressed){
//                val startTime = System.nanoTime()
//
//                val stream = ByteArrayOutputStream()
////                withContext(Dispatchers.IO) { return@withContext captureImage(cameraHelper) }?.compress(
////                    Bitmap.CompressFormat.PNG,
////                    90,
////                    stream
////                )
//                // PROBLEM JEST GDZIEŚ TU, ZDJĘCIE JEST CAŁE A PO KOMPRESJI WIĘKSZOŚĆ ZNIKA
//
//                // Robienie zdjęcia zajmuje sekundę, wygląda na to że zatrzymywanie korutyny zajmuje najdłużej
//                val capturedImage = captureImage(cameraHelper)
//
//
//                // Kompresja zajmuje 4 sekundy
//                val optSTime = System.nanoTime()
//
//                capturedImage?.compress(Bitmap.CompressFormat.PNG, 100, stream)
//
//                val optTime = 10.0.pow(-9) * (System.nanoTime() - optSTime)
//                val sOptTime = optTime.toString()
//
//                val imageByteArray = stream.toByteArray()
//                bufferSize = imageByteArray.size
//                val bufferAsStr = bufferSize.toString()
//                val padBuf = bufferAsStr.padStart(10, '0')
//
//                outputStream.write(padBuf.toByteArray())
//
//                outputStream.write(imageByteArray)
//                outputStream.flush()
//
////                withContext(Dispatchers.IO) {
////                    outputStream.write("$bufferSize\n".toByteArray(Charsets.UTF_8))
////                    outputStream.flush()
////                }
//
////                withContext(Dispatchers.IO) {
////                    val ackBuffer = ByteArray(3)
////                    val bytesRead = inputStream.read(ackBuffer)
////                    if (String(ackBuffer, 0, bytesRead) == "ACK") {
////                        // Send the complete image byte array in one go
////                        outputStream.write(imageByteArray)
////                        outputStream.flush()  // Flush to ensure all bytes are sent
////                    }
////                    outputStream.write("END".toByteArray())
////                    outputStream.flush()
////                }
//
//
//
//
//                val endTime = System.nanoTime()
//                val duration = (endTime - startTime) / 1_000_000_000.0
//                val operationTime = "Operation took : $duration seconds"
//
//                tTextView.text = operationTime
//                rTextView.text = bufferSize.toString()
//
//            }
//
//            withContext(Dispatchers.IO) {
//                client.close()
//                outputStream.close()
//                inputStream.close()
//            }
//            stopButtonPressed = false
//        }
//    }

    private fun startMessageListener(listenerPort: Int) {
        CoroutineScope(Dispatchers.IO).launch {
            try {

                val serverSocket = ServerSocket(listenerPort, 50, InetAddress.getByName("0.0.0.0"))
                serverSocket.reuseAddress = true

                withContext(Dispatchers.Main) {
                    receiveTextView.text = "Listening on port $listenerPort IP ${serverSocket.inetAddress}"
                }
                var clientSocket = serverSocket.accept()
                while (true) {
                    clientSocket = serverSocket.accept()
                    if (clientSocket.isConnected) {
                        break
                    }
                }


                while (!stopButtonPressed) {
                    val inputStream = clientSocket.inputStream
                    val receivedMessage = inputStream.bufferedReader(Charsets.UTF_8).readLine()
                    withContext(Dispatchers.Main) {
                        receiveTextView.text = "\nReceived: $receivedMessage"
                    }

                }

                serverSocket.close()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    errorTextView.append("\nError in listener: ${e.message}")
                }
            }
        }
    }


    fun btnStartClick(view: View) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val portNumber = portInput.text.toString().toInt()
                val ipAddress = ipInput.text.toString()

                // Start the message listener on a separate port
                val listenerPort = 9797 // Replace with your desired port number
                startMessageListener(listenerPort)

//                withContext(Dispatchers.Main) {
//                    sendTextView.text = "Connecting to $ipAddress:$portNumber"
//                }
//
//                // Connect to the server
//                val client = Socket(ipAddress, portNumber)
//                val outputStream = client.outputStream
//                val stream = ByteArrayOutputStream()
//
//                while (!stopButtonPressed) {
//                    stream.reset()
//
//                    // Capture image
//                    val capturedImage = captureImage(cameraHelper)
//                    if (capturedImage == null) {
//                        withContext(Dispatchers.Main) {
//                            errorTextView.text = "Image capture failed"
//                        }
//                        continue
//                    }
//
//                    // Compress image to JPEG
//                    val isCompressed = capturedImage.compress(Bitmap.CompressFormat.JPEG, 85, stream)
//                    if (!isCompressed) {
//                        withContext(Dispatchers.Main) {
//                            errorTextView.text = "Image compression failed"
//                        }
//                        continue
//                    }
//
//                    val imageByteArray = stream.toByteArray()
//                    val imageSize = imageByteArray.size
//
//                    // Ensure the image size is valid
//                    if (imageSize <= 0) {
//                        withContext(Dispatchers.Main) {
//                            errorTextView.text = "Invalid image size"
//                        }
//                        continue
//                    }
//
//                    // Prepare and send the size header
//                    val sizeHeader = imageSize.toString().padStart(10, '0')
//                    outputStream.write(sizeHeader.toByteArray(Charsets.UTF_8))
//                    outputStream.flush()
//                    outputStream.write(imageByteArray)
//                    outputStream.flush()

//                    withContext(Dispatchers.Main) {
//                        sendTextView.text = "Image sent: $imageSize bytes"
//                    }
//                }
//
//                // Close resources
//                client.close()
//                stream.close()
//
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    errorTextView.text = "Error: ${e.message}"
                }
            }
        }
    }





//    fun btnStopClick(view: View){
//        stopButtonPressed = true
//    }
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