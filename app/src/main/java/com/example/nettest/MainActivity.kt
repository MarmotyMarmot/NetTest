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
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume

class MainActivity : AppCompatActivity() {
    private lateinit var ipInput: EditText
    private lateinit var portInput: EditText
    private lateinit var mInput: EditText
    private lateinit var rTextView: TextView
    private lateinit var tTextView: TextView

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
        mInput = findViewById(R.id.messInput)
        rTextView = findViewById(R.id.receiveTextView)
        tTextView = findViewById(R.id.timeTextView)

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
            cameraHelper.takePhotoAsBitmap { bitmap ->
                continuation.resume(bitmap)
            }
        }
    }

    fun btnStartClick(view: View){
        CoroutineScope(Dispatchers.Default).launch {
            val portNumber = portInput.text.toString().toInt()
            val ipAddress = ipInput.text.toString()
            val message = mInput.text.toString()
            val displayedText = "Sent $message"
            rTextView.text = displayedText

            val client = withContext(Dispatchers.IO) {Socket(ipAddress, portNumber)}
            val outputStream = client.outputStream
            val inputStream = client.getInputStream()
            var bufferSize: Int
            while(!stopButtonPressed){
                val startTime = System.nanoTime()

                val stream = ByteArrayOutputStream()
//                withContext(Dispatchers.IO) { return@withContext captureImage(cameraHelper) }?.compress(
//                    Bitmap.CompressFormat.PNG,
//                    90,
//                    stream
//                )
                // PROBLEM JEST GDZIEŚ TU, ZDJĘCIE JEST CAŁE A PO KOMPRESJI WIĘKSZOŚĆ ZNIKA
                captureImage(cameraHelper)?.compress(Bitmap.CompressFormat.PNG, 100, stream)

                val imageByteArray = stream.toByteArray()
                bufferSize = imageByteArray.size


                outputStream.write("$bufferSize\n".toByteArray(Charsets.UTF_8))
                outputStream.flush()
                val ackBuffer = ByteArray(3)
                val bytesRead = inputStream.read(ackBuffer)
                if (String(ackBuffer, 0, bytesRead) == "ACK") {
                    // Send the complete image byte array in one go
                    withContext(Dispatchers.IO){
                        outputStream.write(imageByteArray)
                        outputStream.flush()  // Flush to ensure all bytes are sent
                    }

                }
                outputStream.write("END".toByteArray())
                outputStream.flush()

//                withContext(Dispatchers.IO) {
//                    outputStream.write("$bufferSize\n".toByteArray(Charsets.UTF_8))
//                    outputStream.flush()
//                }

//                withContext(Dispatchers.IO) {
//                    val ackBuffer = ByteArray(3)
//                    val bytesRead = inputStream.read(ackBuffer)
//                    if (String(ackBuffer, 0, bytesRead) == "ACK") {
//                        // Send the complete image byte array in one go
//                        outputStream.write(imageByteArray)
//                        outputStream.flush()  // Flush to ensure all bytes are sent
//                    }
//                    outputStream.write("END".toByteArray())
//                    outputStream.flush()
//                }




                val endTime = System.nanoTime()
                val duration = (endTime - startTime) / 1_000_000_000.0
                val operationTime = "Operation took : $duration seconds"

                tTextView.text = operationTime
                rTextView.text = bufferSize.toString()

            }

            withContext(Dispatchers.IO) {
                client.close()
                outputStream.close()
                inputStream.close()
            }
            stopButtonPressed = false
        }
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