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

    /**
     * Called when the activity is created. Sets up the UI and initializes required components.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge() // Enable edge-to-edge display
        setContentView(R.layout.activity_main)
        setupWindowInsets() // Adjust the layout for system bars (e.g., status bar)
        initializeViews() // Initialize all view components
        setupPhotoPickerLauncher() // Set up the photo picker for video selection
    }

    /**
     * Configures the layout to accommodate system bars, such as the status bar or notch.
     */
    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    /**
     * Initializes and binds the UI components from the layout to the class variables.
     */
    private fun initializeViews() {
        ipInput = findViewById(R.id.ipAddrInput)
        portInput = findViewById(R.id.pInput)
        csvNameInput = findViewById(R.id.csvName)
        sendTextView = findViewById(R.id.sendTextView)
        receiveTextView = findViewById(R.id.receiveTextView)
        errorTextView = findViewById(R.id.errorTextView)
    }

    /**
     * Sets up the photo picker for selecting videos using the Activity Result API.
     */
    private fun setupPhotoPickerLauncher() {
        photoPickerLauncher =
            registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
                uri?.let {
                    fileDescriptor = contentResolver.openFileDescriptor(it, "r")
                    mediaRetriever.setDataSource(fileDescriptor?.fileDescriptor)
                }
            }
    }

    /**
     * Extracts a video frame at the current index and increments the frame counter.
     * @return The Bitmap representing the frame, or null if an error occurs.
     */
    private fun yieldFrameFromVideo(): Bitmap? {
        return try {
            mediaRetriever.getFrameAtIndex(videoFrame++)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /**
     * Saves the provided CSV data to the Downloads directory.
     * @param context The application context.
     * @param fileName The name of the file to save, without extension.
     * @param data The content of the CSV file as a list of lists.
     */
    private fun saveCsvToDownloads(context: Context, fileName: String, data: List<List<String>>) {
        val csvContent = buildCsvContent(data)
        val resolver = context.contentResolver

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveCsvForScopedStorage(resolver, fileName, csvContent)
        } else {
            saveCsvForLegacyStorage(fileName, csvContent)
        }
        Toast.makeText(context, "CSV file saved as $fileName.csv", Toast.LENGTH_SHORT).show()
    }

    /**
     * Saves the CSV file using scoped storage for devices running Android Q and above.
     * @param resolver The ContentResolver to use for file creation.
     * @param fileName The name of the file to save, without extension.
     * @param csvContent The CSV content to write to the file.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveCsvForScopedStorage(
        resolver: android.content.ContentResolver,
        fileName: String,
        csvContent: String
    ) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$fileName.csv")
            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        uri?.let {
            resolver.openOutputStream(it)?.use { outputStream ->
                outputStream.write(csvContent.toByteArray())
            }
        } ?: run {
            showError("Failed to create file in Downloads.")
        }

        Toast.makeText(applicationContext, "CSV file saved as $fileName.csv", Toast.LENGTH_SHORT)
            .show()
    }

    private fun saveCsvForLegacyStorage(fileName: String, csvContent: String) {
        /**
         * Saves the CSV file to the legacy Downloads directory.
         * @param fileName The name of the file to save, without extension.
         * @param csvContent The CSV content to write to the file.
         */
        val downloadsDir =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
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
        /**
         * Converts a list of data into CSV format.
         * @param data The data to convert, represented as a list of lists of strings.
         * @return A string representing the data in CSV format.
         */
        return data.joinToString("\n") { row -> row.joinToString(";") }
    }

    private suspend fun handleImageAndSend(
        outputStream: java.io.OutputStream,
        sendCounter: Int,
        stream: ByteArrayOutputStream
    ): Boolean {
        /**
         * Captures a video frame, compresses it, and sends it over the output stream.
         * @param outputStream The stream to send the image data through.
         * @param sendCounter The current count of sent frames.
         * @param stream A ByteArrayOutputStream for compressing the image data.
         * @return True if the operation succeeds, false otherwise.
         */
        val image = yieldFrameFromVideo() ?: run {
            showError("Image capture failed")
            return false
        }

        return compressAndSendImage(outputStream, sendCounter, stream, image)
    }

    private suspend fun compressAndSendImage(
        outputStream: java.io.OutputStream,
        sendCounter: Int,
        stream: ByteArrayOutputStream,
        image: Bitmap
    ): Boolean {
        /**
         * Compresses an image and sends it over the output stream.
         * @param outputStream The stream to send the image data through.
         * @param sendCounter The current count of sent frames.
         * @param stream A ByteArrayOutputStream for compressing the image data.
         * @param image The image to compress and send.
         * @return True if the operation succeeds, false otherwise.
         */
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

        val sizeHeader =
            imageByteArray.size.toString().padStart(10, '0') // Pad size for consistency
        withContext(Dispatchers.IO) {
            outputStream.write(sizeHeader.toByteArray(Charsets.UTF_8)) // Send the size header first
            outputStream.flush()
            outputStream.write(imageByteArray) // Send the image data
            outputStream.flush()
        }
        times += listOf(listOf("Send", "Frame", "$sendCounter", System.nanoTime().toString()))
        return true
    }

    private suspend fun receiveData(
        inputStream: java.io.InputStream,
        buffer: ByteArray,
        receiveCounter: Int
    ) {
        /**
         * Reads data from the input stream and updates the times log with the received information.
         * @param inputStream The stream to read data from.
         * @param buffer The buffer to store received data.
         * @param receiveCounter The current count of received frames.
         */
        try {
            withContext(Dispatchers.IO) {
                val bytesRead = inputStream.read(buffer)
                val response = String(buffer, 0, bytesRead)
                times += listOf(
                    listOf(
                        "Receive",
                        response,
                        "$receiveCounter",
                        System.nanoTime().toString()
                    )
                )
            }
        } catch (e: Exception) {
            showError("Error in listener: ${e.message}")
        }
    }

    /**
     * Displays an error message on the UI.
     * @param message The error message to display.
     */
    private fun showError(message: String) {
        CoroutineScope(Dispatchers.Main).launch {
            errorTextView.text = message
        }
    }

    /**
     * Updates the sendTextView to display the current message.
     * @param message The message to display.
     */
    private fun updateSendText(message: String) {
        CoroutineScope(Dispatchers.Main).launch {
            sendTextView.text = message
        }
    }

    /**
     * Called when the "Pick" button is clicked. Launches the photo picker for selecting a video.
     */
    fun btnPickClick(view: View) {
        photoPickerLauncher.launch(PickVisualMediaRequest(mediaType = ActivityResultContracts.PickVisualMedia.VideoOnly))
    }

    /**
     * Called when the "Start" button is clicked. Initiates the process of sending video frames and receiving data.
     */
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

    /**
     * Called when the "Stop" button is clicked. Toggles the stop flag to terminate operations.
     */
    fun btnStopClick(view: View) {
        stopButtonPressed = !stopButtonPressed
    }

    /**
     * Called when the "Save" button is clicked. Saves the recorded times log to a CSV file.
     */
    fun btnSaveClick(view: View) {
        saveCsvToDownloads(this, csvNameInput.text.toString(), times)
    }
}
