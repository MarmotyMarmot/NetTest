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
        enableEdgeToEdge();
        setContentView(R.layout.activity_main);
        setupUI();
        initializePhotoPicker();
    }

    private fun setupUI() {
        configureInsets();
        initializeUIElements();
    }

    private fun configureInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            insets;
        };
    }

    private fun initializeUIElements() {
        ipInput = findViewById(R.id.ipAddrInput);
        portInput = findViewById(R.id.pInput);
        csvName = findViewById(R.id.csvName);
        sendTextView = findViewById(R.id.sendTextView);
        receiveTextView = findViewById(R.id.receiveTextView);
        errorTextView = findViewById(R.id.errorTextView);
    }

    private fun initializePhotoPicker() {
        photoPickerLauncher = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            uri?.let {
                setupMediaRetriever(it);
            }
        };
    }

    private fun setupMediaRetriever(uri: Uri) {
        fileDescriptor = contentResolver.openFileDescriptor(uri, "r");
        mediaRetriever.setDataSource(fileDescriptor?.fileDescriptor);
    }

    private fun yieldFrameFromVideo(): Bitmap? {
        return try {
            mediaRetriever.getFrameAtIndex(videoFrame++);
        } catch (e: IllegalArgumentException) {
            null;
        }
    }

    private fun startMessageListener(listenerPort: Int) {
        CoroutineScope(Dispatchers.IO).launch {
            setupServerSocket(listenerPort);
        };
    }

    private suspend fun setupServerSocket(listenerPort: Int) {
        try {
            withContext(Dispatchers.IO) {
                ServerSocket(listenerPort).use { serverSocket ->
                    updateUI {
                        receiveTextView.text =
                            "Listening on port $listenerPort IP ${serverSocket.inetAddress}"
                    };
                    handleClientSocket(serverSocket.accept());
                }
            };
        } catch (e: Exception) {
            handleListenerError(e);
        }
    }

    private suspend fun handleClientSocket(clientSocket: Socket) {
        var receiveCounter = 0;

        while (!stopButtonPressed) {
            val receivedMessage =
                withContext(Dispatchers.IO) {
                    clientSocket.inputStream.bufferedReader(Charsets.UTF_8).readLine()
                };
            times.add(listOf("Receive", receivedMessage, "$receiveCounter", System.nanoTime().toString()));
            receiveCounter++;
        }
    }

    private fun handleListenerError(e: Exception) {
        updateUI { errorTextView.append("\nError in listener: ${e.message}"); };
    }

    private fun saveCsvToDownloads(context: Context, fileName: String, data: List<List<String>>) {
        val csvContent = generateCsvContent(data);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveCsvForModernVersions(context, fileName, csvContent);
        } else {
            saveCsvForOlderVersions(fileName, csvContent);
        }
    }

    private fun generateCsvContent(data: List<List<String>>): String {
        return data.joinToString("\n") { row -> row.joinToString(";") };
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveCsvForModernVersions(context: Context, fileName: String, csvContent: String) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$fileName.csv");
            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv");
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        };

        context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)?.let { uri ->
            context.contentResolver.openOutputStream(uri)?.use {
                it.write(csvContent.toByteArray());
            } ?: logError("Failed to create file in Downloads.");
        };
        Toast.makeText(context, "CSV file saved as $fileName.csv", Toast.LENGTH_SHORT).show()
    }

    private fun saveCsvForOlderVersions(fileName: String, csvContent: String) {
        val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "$fileName.csv");
        try {
            FileWriter(file).use { it.write(csvContent); };
        } catch (e: Exception) {
            e.printStackTrace();
        }
        Toast.makeText(this, "CSV file saved as $fileName.csv", Toast.LENGTH_SHORT).show()
    }

    private fun logError(message: String) {
        errorTextView.text = message;
    }

    private fun updateUI(action: () -> Unit) {
        runOnUiThread { action(); };
    }

    private suspend fun startTransmission() {
        val portNumber = portInput.text.toString().toInt();
        val ipAddress = ipInput.text.toString();
        startMessageListener(9797);

        updateUI { sendTextView.text = "Connecting to $ipAddress:$portNumber" };

        withContext(Dispatchers.IO) {
            Socket(ipAddress, portNumber).use { client ->
                transmitVideo(client.outputStream);
            }
        };

        updateUI { sendTextView.text = "Video sent" };
    }

    private suspend fun transmitVideo(outputStream: OutputStream) {
        val stream = ByteArrayOutputStream();
        var sendCounter = 0;

        while (!stopButtonPressed) {
            stream.reset();
            val capturedImage = yieldFrameFromVideo();

            if (capturedImage == null || !prepareImage(capturedImage, stream)) {
                continue;
            }

            sendImage(stream.toByteArray(), outputStream, sendCounter);
            sendCounter++;
        }
    }

    private fun prepareImage(capturedImage: Bitmap, stream: ByteArrayOutputStream): Boolean {
        return if (!capturedImage.compress(Bitmap.CompressFormat.JPEG, 85, stream)) {
            updateUI { logError("Image compression failed") };
            false;
        } else true;
    }

    private fun sendImage(imageByteArray: ByteArray, outputStream: OutputStream, sendCounter: Int) {
        if (imageByteArray.isEmpty()) {
            updateUI { logError("Invalid image size") };
            return;
        }

        val sizeHeader = imageByteArray.size.toString().padStart(10, '0');
        outputStream.write(sizeHeader.toByteArray(Charsets.UTF_8));
        outputStream.write(imageByteArray);
        outputStream.flush();

        times.add(listOf("Send", "Frame", "$sendCounter", System.nanoTime().toString()));
    }

    private fun handleTransmissionError(e: Exception) {
        updateUI { logError("Error: ${e.message}"); };
    }

    fun btnPickClick(view: View) {
        photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly));
    }

    fun btnStartClick(view: View) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                startTransmission();
            } catch (e: Exception) {
                handleTransmissionError(e);
            }
        };
    }

    fun btnStopClick(view: View) {
        stopButtonPressed = !stopButtonPressed;
    }

    fun btnSaveClick(view: View) {
        saveCsvToDownloads(this, csvName.text.toString(), times);
    }
}
