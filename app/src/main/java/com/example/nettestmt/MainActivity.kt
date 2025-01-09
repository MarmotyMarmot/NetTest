package com.example.nettestmt;

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileWriter
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket

class MainActivity : AppCompatActivity() {

    /**
     * Declares UI elements and variables used across multiple functions.
     */
    private lateinit var ipInput: EditText;
    private lateinit var portInput: EditText;
    private lateinit var csvName: EditText;
    private lateinit var receiveTextView: TextView;
    private lateinit var sendTextView: TextView;
    private lateinit var errorTextView: TextView;

    private var videoFrame: Int = 0;
    private lateinit var photoPickerLauncher: ActivityResultLauncher<PickVisualMediaRequest>;
    private var fileDescriptor: ParcelFileDescriptor? = null;
    private val mediaRetriever = MediaMetadataRetriever();
    private val times = mutableListOf(listOf("Type", "Var", "Count", "Timestamp"));

    private var stopButtonPressed = false;

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState);
        enableEdgeToEdge(); // Enables immersive layout
        setContentView(R.layout.activity_main);
        setupUI(); // Set up UI elements and insets
        initializePhotoPicker(); // Initialize media picker for video selection
    }

    /**
     * Sets up the UI by configuring insets and initializing UI elements.
     */
    private fun setupUI() {
        configureInsets(); // Adjust UI padding to handle system bars
        initializeUIElements(); // Find and bind UI elements
    }

    /**
     * Configures window insets to adjust padding for system bars.
     */
    private fun configureInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            insets;
        };
    }

    /**
     * Initializes and binds UI elements to variables.
     */
    private fun initializeUIElements() {
        ipInput = findViewById(R.id.ipAddrInput);
        portInput = findViewById(R.id.pInput);
        csvName = findViewById(R.id.csvName);
        sendTextView = findViewById(R.id.sendTextView);
        receiveTextView = findViewById(R.id.receiveTextView);
        errorTextView = findViewById(R.id.errorTextView);
    }

    /**
     * Initializes the photo picker launcher for selecting videos.
     */
    private fun initializePhotoPicker() {
        photoPickerLauncher =
            registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
                uri?.let {
                    setupMediaRetriever(it); // Set up MediaMetadataRetriever with the selected video URI
                }
            };
    }

    /**
     * Sets up the MediaMetadataRetriever with the provided URI.
     *
     * @param uri The URI of the selected video.
     */
    private fun setupMediaRetriever(uri: Uri) {
        fileDescriptor =
            contentResolver.openFileDescriptor(uri, "r"); // Open file descriptor for the video file
        mediaRetriever.setDataSource(fileDescriptor?.fileDescriptor); // Set data source for MediaMetadataRetriever
    }

    /**
     * Extracts the next frame from the selected video.
     *
     * @return The extracted frame as a Bitmap, or null if retrieval fails.
     */
    private fun yieldFrameFromVideo(): Bitmap? {
        return try {
            mediaRetriever.getFrameAtIndex(videoFrame++); // Extract the next frame from the video
        } catch (e: IllegalArgumentException) {
            null; // Return null if the frame cannot be retrieved
        }
    }

    /**
     * Starts a message listener on the specified port in a coroutine.
     *
     * @param listenerPort The port to listen for incoming messages.
     */
    private fun startMessageListener(listenerPort: Int) {
        CoroutineScope(Dispatchers.IO).launch {
            setupServerSocket(listenerPort); // Start a coroutine for listening to incoming messages
        };
    }

    /**
     * Sets up a server socket to listen for incoming connections.
     *
     * @param listenerPort The port to listen on.
     */
    private suspend fun setupServerSocket(listenerPort: Int) {
        try {
            withContext(Dispatchers.IO) {
                ServerSocket(listenerPort).use { serverSocket ->
                    updateUI {
                        receiveTextView.text =
                            "Listening on port $listenerPort IP ${serverSocket.inetAddress}";
                    };
                    handleClientSocket(serverSocket.accept()); // Accept and handle the first client connection
                }
            };
        } catch (e: Exception) {
            handleListenerError(e); // Handle errors during server socket setup
        }
    }

    /**
     * Handles the incoming client socket connection.
     *
     * @param clientSocket The accepted client socket.
     */
    private suspend fun handleClientSocket(clientSocket: Socket) {
        var receiveCounter = 0;

        while (!stopButtonPressed) {
            val receivedMessage =
                withContext(Dispatchers.IO) {
                    clientSocket.inputStream.bufferedReader(Charsets.UTF_8)
                        .readLine() // Read incoming message
                };
            times.add(
                listOf(
                    "Receive",
                    receivedMessage,
                    "$receiveCounter",
                    System.nanoTime().toString()
                )
            ); // Log received message
            receiveCounter++;
        }
    }

    /**
     * Handles errors during the server socket listener setup.
     *
     * @param e The exception that occurred.
     */
    private fun handleListenerError(e: Exception) {
        updateUI { errorTextView.append("\nError in listener: ${e.message}"); }; // Update UI with the error message
    }

    /**
     * Saves CSV data to the Downloads directory, adapting to the Android version.
     *
     * @param context The application context.
     * @param fileName The name of the CSV file (without extension).
     * @param data The data to save in CSV format.
     */
    private fun saveCsvToDownloads(context: Context, fileName: String, data: List<List<String>>) {
        val csvContent = generateCsvContent(data); // Generate CSV content from data
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveCsvForModernVersions(
                context,
                fileName,
                csvContent
            ); // Save CSV for modern Android versions
        } else {
            saveCsvForOlderVersions(fileName, csvContent); // Save CSV for older Android versions
        }
    }

    /**
     * Generates CSV content from a list of data rows.
     *
     * @param data The data to convert to CSV format.
     * @return The CSV-formatted string.
     */
    private fun generateCsvContent(data: List<List<String>>): String {
        return data.joinToString("\n") { row -> row.joinToString(";") }; // Join data rows into CSV format
    }

    /**
     * Saves a CSV file in the Downloads directory for devices running Android Q and later.
     *
     * @param context The context of the calling activity.
     * @param fileName The name of the CSV file to be saved (without extension).
     * @param csvContent The content of the CSV file.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveCsvForModernVersions(context: Context, fileName: String, csvContent: String) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$fileName.csv");
            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv");
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        };

        context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)?.let { uri ->
            context.contentResolver.openOutputStream(uri)?.use {
                it.write(csvContent.toByteArray()); // Write CSV content to the output stream
            } ?: logError("Failed to create file in Downloads.");
        };
        Toast.makeText(context, "CSV file saved as $fileName.csv", Toast.LENGTH_SHORT).show()
    }

    /**
     * Saves a CSV file in the Downloads directory for devices running Android versions older than Q.
     *
     * @param fileName The name of the CSV file to be saved (without extension).
     * @param csvContent The content of the CSV file.
     */
    private fun saveCsvForOlderVersions(fileName: String, csvContent: String) {
        val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "$fileName.csv");
        try {
            FileWriter(file).use { it.write(csvContent); }; // Write CSV content to a file
        } catch (e: Exception) {
            e.printStackTrace();
        }
        Toast.makeText(this, "CSV file saved as $fileName.csv", Toast.LENGTH_SHORT).show()
    }

    /**
     * Logs an error message to a designated error TextView.
     *
     * @param message The error message to be displayed.
     */
    private fun logError(message: String) {
        errorTextView.text = message; // Update the error TextView with the provided message
    }

    /**
     * Updates the UI by executing a provided action on the main thread.
     *
     * @param action The UI-related action to execute.
     */
    private fun updateUI(action: () -> Unit) {
        runOnUiThread { action(); }; // Update the UI from a background thread
    }

    /**
     * Starts the process of transmitting video frames to a server.
     * This function handles UI updates and socket initialization.
     */
    private suspend fun startTransmission() {
        val portNumber = portInput.text.toString().toInt();
        val ipAddress = ipInput.text.toString();
        startMessageListener(9797); // Start listening for incoming messages on port 9797

        updateUI { sendTextView.text = "Connecting to $ipAddress:$portNumber" };

        withContext(Dispatchers.IO) {
            Socket(ipAddress, portNumber).use { client ->
                transmitVideo(client.outputStream); // Transmit video frames to the server
            }
        };

        updateUI { sendTextView.text = "Video sent" };
    }

    /**
     * Continuously captures video frames, prepares them for transmission,
     * and sends them through the provided output stream.
     *
     * @param outputStream The output stream to transmit video frames.
     */
    private suspend fun transmitVideo(outputStream: OutputStream) {
        val stream = ByteArrayOutputStream();
        var sendCounter = 0;

        while (!stopButtonPressed) {
            stream.reset(); // Clear the output stream
            val capturedImage = yieldFrameFromVideo();

            if (capturedImage == null || !prepareImage(capturedImage, stream)) {
                continue; // Skip this iteration if the frame could not be prepared
            }

            sendImage(stream.toByteArray(), outputStream, sendCounter); // Send the prepared image
            sendCounter++;
        }
    }

    /**
     * Prepares an image by compressing it into a JPEG format and writing it to a stream.
     *
     * @param capturedImage The image to be prepared.
     * @param stream The stream where the compressed image will be written.
     * @return `true` if the image was successfully compressed; otherwise, `false`.
     */
    private fun prepareImage(capturedImage: Bitmap, stream: ByteArrayOutputStream): Boolean {
        return if (!capturedImage.compress(Bitmap.CompressFormat.JPEG, 85, stream)) {
            updateUI { logError("Image compression failed") };
            false; // Return false if compression fails
        } else true;
    }

    /**
     * Sends a prepared image through an output stream, including a size header.
     *
     * @param imageByteArray The byte array of the image to send.
     * @param outputStream The output stream to send the image data.
     * @param sendCounter The frame counter for tracking the sent images.
     */
    private fun sendImage(imageByteArray: ByteArray, outputStream: OutputStream, sendCounter: Int) {
        if (imageByteArray.isEmpty()) {
            updateUI { logError("Invalid image size") };
            return;
        }

        val sizeHeader = imageByteArray.size.toString().padStart(10, '0'); // Create a 10-character size header
        outputStream.write(sizeHeader.toByteArray(Charsets.UTF_8)); // Send the size header
        outputStream.write(imageByteArray); // Send the image data
        outputStream.flush(); // Ensure all data is sent

        times.add(listOf("Send", "Frame", "$sendCounter", System.nanoTime().toString())); // Log the sent frame
    }

    /**
     * Handles errors that occur during video transmission by updating the UI with the error message.
     *
     * @param e The exception that occurred.
     */
    private fun handleTransmissionError(e: Exception) {
        updateUI { logError("Error: ${e.message}"); }; // Handle errors during transmission
    }

    /**
     * Handles the click event for the "Pick" button.
     * Launches the photo picker for selecting a video.
     *
     * @param view The view associated with the button click.
     */
    fun btnPickClick(view: View) {
        photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)); // Launch photo picker for video selection
    }

    /**
     * Handles the click event for the "Start" button.
     * Initiates the video transmission process in a coroutine.
     *
     * @param view The view associated with the button click.
     */
    fun btnStartClick(view: View) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                startTransmission(); // Start video transmission when the start button is clicked
            } catch (e: Exception) {
                handleTransmissionError(e); // Handle errors during the start of transmission
            }
        };
    }

    /**
     * Handles the click event for the "Stop" button.
     * Toggles the state of the stop button to stop video transmission.
     *
     * @param view The view associated with the button click.
     */
    fun btnStopClick(view: View) {
        stopButtonPressed = !stopButtonPressed; // Toggle the stop button state
    }

    /**
     * Handles the click event for the "Save" button.
     * Saves logged data to the Downloads directory as a CSV file.
     *
     * @param view The view associated with the button click.
     */
    fun btnSaveClick(view: View) {
        saveCsvToDownloads(this, csvName.text.toString(), times); // Save logged data as a CSV file
    }
}