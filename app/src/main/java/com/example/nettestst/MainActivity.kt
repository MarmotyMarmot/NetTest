package com.example.nettestst

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.net.Socket
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileWriter

class MainActivity : AppCompatActivity() {

    private lateinit var ipInput: EditText
    private lateinit var portInput: EditText
    private lateinit var csvNameInput: EditText
    private lateinit var receiveTextView: TextView
    private lateinit var sendTextView: TextView
    private lateinit var errorTextView: TextView

    private var videoFrame: Int = 0
    private lateinit var photoPickerLauncher: ActivityResultLauncher<PickVisualMediaRequest>
    private var fileDescriptor: ParcelFileDescriptor? = null
    private val mediaRetriever = MediaMetadataRetriever()
    private var times = listOf(listOf("Type", "Var", "Count", "Timestamp"))

    private var stopButtonPressed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge() // Enable edge-to-edge display
        setContentView(R.layout.activity_main)
        setupWindowInsets() // Adjust the layout for system bars (e.g., status bar)
        initializeViews() // Initialize all view components
        setupPhotoPickerLauncher() // Set up the photo picker for video selection
    }

    private fun setupWindowInsets() {
        // Adjust padding based on system bar insets (e.g., for notch or status bar)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    private fun initializeViews() {
        // Bind UI components to variables
        ipInput = findViewById(R.id.ipAddrInput)
        portInput = findViewById(R.id.pInput)
        csvNameInput = findViewById(R.id.csvName)
        sendTextView = findViewById(R.id.sendTextView)
        receiveTextView = findViewById(R.id.receiveTextView)
        errorTextView = findViewById(R.id.errorTextView)
    }

    private fun setupPhotoPickerLauncher() {
        // Initialize the photo picker for selecting videos
        photoPickerLauncher = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            uri?.let {
                fileDescriptor = contentResolver.openFileDescriptor(it, "r")
                mediaRetriever.setDataSource(fileDescriptor?.fileDescriptor)
            }
        }
    }

    private fun yieldFrameFromVideo(): Bitmap? {
        return try {
            // Extract a frame from the video at the current frame index
            mediaRetriever.getFrameAtIndex(videoFrame++)
        } catch (e: IllegalArgumentException) {
            null // Return null if an invalid frame index is encountered
        }
    }

    private fun saveCsvToDownloads(context: Context, fileName: String, data: List<List<String>>) {
        val csvContent = buildCsvContent(data)
        val resolver = context.contentResolver

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveCsvForScopedStorage(resolver, fileName, csvContent) // Use scoped storage for Android Q and above
        } else {
            saveCsvForLegacyStorage(fileName, csvContent) // Use legacy storage for older versions
        }
        Toast.makeText(context, "CSV file saved as $fileName.csv", Toast.LENGTH_SHORT).show()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveCsvForScopedStorage(resolver: android.content.ContentResolver, fileName: String, csvContent: String) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$fileName.csv")
            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        uri?.let {
            resolver.openOutputStream(it)?.use { outputStream ->
                outputStream.write(csvContent.toByteArray()) // Write the CSV content to the file
            }
        } ?: run {
            showError("Failed to create file in Downloads.")
        }

        Toast.makeText(applicationContext, "CSV file saved as $fileName.csv", Toast.LENGTH_SHORT).show()
    }

    private fun saveCsvForLegacyStorage(fileName: String, csvContent: String) {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val file = File(downloadsDir, "$fileName.csv")
        try {
            FileWriter(file).use { writer ->
                writer.write(csvContent) // Write CSV to legacy storage
            }
            println("File saved to Downloads: ${file.absolutePath}")
        } catch (e: Exception) {
            showError("Error saving file: ${e.message}")
        }
    }

    private fun buildCsvContent(data: List<List<String>>): String {
        // Convert the list of data into CSV format
        return data.joinToString("\n") { row -> row.joinToString(";") }
    }

    private suspend fun handleImageAndSend(outputStream: java.io.OutputStream, sendCounter: Int, stream: ByteArrayOutputStream): Boolean {
        val image = yieldFrameFromVideo() ?: run {
            showError("Image capture failed")
            return false
        }

        return compressAndSendImage(outputStream, sendCounter, stream, image)
    }

    private suspend fun compressAndSendImage(outputStream: java.io.OutputStream, sendCounter: Int, stream: ByteArrayOutputStream, image: Bitmap): Boolean {
        val isCompressed = image.compress(Bitmap.CompressFormat.JPEG, 85, stream)
        if (!isCompressed) {
            showError("Image compression failed")
            return false
        }

        val imageByteArray = stream.toByteArray()
        if (imageByteArray.isEmpty()) {
            showError("Invalid image size")
            return false
        }

        val sizeHeader = imageByteArray.size.toString().padStart(10, '0') // Pad size for consistency
        withContext(Dispatchers.IO){
            outputStream.write(sizeHeader.toByteArray(Charsets.UTF_8)) // Send the size header first
            outputStream.flush()
            outputStream.write(imageByteArray) // Send the image data
            outputStream.flush()
        }
        times += listOf(listOf("Send", "Frame", "$sendCounter", System.nanoTime().toString()))
        return true
    }

    private suspend fun receiveData(inputStream: java.io.InputStream, buffer: ByteArray, receiveCounter: Int) {
        try {
            withContext(Dispatchers.IO){
                val bytesRead = inputStream.read(buffer)
                val response = String(buffer, 0, bytesRead)
                times += listOf(listOf("Receive", response, "$receiveCounter", System.nanoTime().toString()))
            }
        } catch (e: Exception) {
            showError("Error in listener: ${e.message}")
        }
    }

    // Utility function to show errors in the UI
    private fun showError(message: String) {
        CoroutineScope(Dispatchers.Main).launch {
            errorTextView.text = message
        }
    }

    // Update the UI to reflect the current message
    private fun updateSendText(message: String) {
        CoroutineScope(Dispatchers.Main).launch {
            sendTextView.text = message
        }
    }

    fun btnPickClick(view: View) {
        // Trigger the photo picker for selecting a video
        photoPickerLauncher.launch(PickVisualMediaRequest(mediaType = ActivityResultContracts.PickVisualMedia.VideoOnly))
    }

    fun btnStartClick(view: View) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val portNumber = portInput.text.toString().toInt()
                val ipAddress = ipInput.text.toString()
                updateSendText("Connecting to $ipAddress:$portNumber")

                val client = Socket(ipAddress, portNumber) // Establish socket connection
                val outputStream = client.outputStream
                val inputStream = client.inputStream
                val stream = ByteArrayOutputStream()
                var sendCounter = 0
                var receiveCounter = 0
                val buffer = ByteArray(4096)

                while (!stopButtonPressed) {
                    if (!handleImageAndSend(outputStream, sendCounter++, stream)) break
                    receiveData(inputStream, buffer, receiveCounter++)
                }

                updateSendText("Video sent")
                client.close() // Close the connection
                stream.close() // Close the output stream

            } catch (e: Exception) {
                showError("Error: ${e.message}")
            }
        }
    }

    fun btnStopClick(view: View) {
        stopButtonPressed = !stopButtonPressed // Toggle the stop flag
    }

    fun btnSaveClick(view: View) {
        saveCsvToDownloads(this, csvNameInput.text.toString(), times) // Save CSV data to Downloads
    }
}
