package com.example.myapplication

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import com.example.myapplication.ui.theme.MyApplicationTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private var webView: WebView? = null

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingParams: WebChromeClient.FileChooserParams? = null

    private var cameraImageUri: Uri? = null

    private companion object {
        private const val DEFAULT_URL =
            "http://10.0.2.2:3000/?authToken=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJlbWFpbEFkZHJlc3MiOiJheXVzaEBzaGVsbHkuY29tIn0.AlzznDNkEQ2MxmnlsqhEXJ9ASf9DA3czuEBfAwice9U&homebodyUserUuid=865e1b8e-5026-4991-9bee-036a4272c64c&emailAddress=ayush%40shelly.com&buildingName=9+Shelly+St"

        // Enforced in the Photo Picker UI (where supported)
        private const val MAX_IMAGES = 3
    }

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) openFileChooser(pendingParams) else cleanupChooser(null)
        }

    // Android Photo Picker (multi-select with max)
    private val pickMultipleImagesLauncher =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_IMAGES)) { uris ->
            cleanupChooser(if (uris.isEmpty()) null else uris.toTypedArray())
        }

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uris = handleChooserResult(result.resultCode, result.data)
            cleanupChooser(uris)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            MyApplicationTheme {
                AndroidView(
                    factory = { context ->
                        WebView(context).apply {
                            webView = this

                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = true
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            settings.userAgentString =
                                "${settings.userAgentString} MyAppWebView/1.0"

                            webViewClient = WebViewClient()
                            webChromeClient = object : WebChromeClient() {
                                override fun onShowFileChooser(
                                    webView: WebView?,
                                    filePathCallback: ValueCallback<Array<Uri>>?,
                                    fileChooserParams: FileChooserParams?
                                ): Boolean {
                                    // Cancel any previous callback
                                    this@MainActivity.filePathCallback?.onReceiveValue(null)

                                    this@MainActivity.filePathCallback = filePathCallback
                                    pendingParams = fileChooserParams

                                    val mightUseCamera =
                                        fileChooserParams?.isCaptureEnabled == true ||
                                            (isImageRequest(fileChooserParams) && !allowsMultiple(fileChooserParams))

                                    if (mightUseCamera && !hasCameraPermission()) {
                                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                    } else {
                                        openFileChooser(fileChooserParams)
                                    }
                                    return true
                                }
                            }

                            loadUrl(DEFAULT_URL)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    override fun onDestroy() {
        webView?.destroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        webView?.let { view ->
            if (view.canGoBack()) view.goBack() else super.onBackPressed()
        } ?: super.onBackPressed()
    }

    private fun cleanupChooser(uris: Array<Uri>?) {
        filePathCallback?.onReceiveValue(uris)
        filePathCallback = null
        pendingParams = null
        cameraImageUri = null
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun allowsMultiple(params: WebChromeClient.FileChooserParams?): Boolean =
        params?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE

    private fun isImageRequest(params: WebChromeClient.FileChooserParams?): Boolean {
        val types = params?.acceptTypes?.filter { it.isNotBlank() } ?: emptyList()
        if (types.isEmpty()) return true
        return types.any { it.contains("image", ignoreCase = true) || it == "*/*" }
    }

    private fun openFileChooser(params: WebChromeClient.FileChooserParams?) {
        val allowMultiple = allowsMultiple(params)
        val captureEnabled = params?.isCaptureEnabled == true
        val imageRequest = isImageRequest(params)

        val requestedMimeType = params?.acceptTypes
            ?.firstOrNull()
            ?.takeIf { it.isNotBlank() }

        val mimeType = requestedMimeType ?: if (imageRequest) "image/*" else "*/*"

        // 1) capture=true (your takePhoto flow): open camera directly
        if (captureEnabled) {
            if (launchCameraOrNull()) return
        }

        // 2) multiple images requested: use Photo Picker with max selection
        // (better UX: user sees they can only pick up to MAX_IMAGES)
        if (imageRequest && allowMultiple) {
            pickMultipleImagesLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
            return
        }

        // 3) single image requested: prefer camera directly
        if (imageRequest && !allowMultiple) {
            if (launchCameraOrNull()) return

            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            }
            fileChooserLauncher.launch(intent)
            return
        }

        // 4) other files: picker + multiple if requested
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType
            if (allowMultiple) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        fileChooserLauncher.launch(intent)
    }

    /**
     * Returns true if camera launch was started, false if we should fall back to a picker.
     */
    private fun launchCameraOrNull(): Boolean {
        val cameraIntent = createCameraIntent() ?: return false
        return try {
            fileChooserLauncher.launch(cameraIntent)
            true
        } catch (_: ActivityNotFoundException) {
            cameraImageUri = null
            false
        } catch (_: Exception) {
            cameraImageUri = null
            false
        }
    }

    private fun createCameraIntent(): Intent? {
        val uri = createImageUri() ?: return null
        cameraImageUri = uri

        return Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }

    private fun handleChooserResult(resultCode: Int, data: Intent?): Array<Uri>? {
        if (resultCode != RESULT_OK) return null

        val uris = mutableListOf<Uri>()
        val clip = data?.clipData

        when {
            clip != null -> {
                for (i in 0 until clip.itemCount) {
                    uris += clip.getItemAt(i).uri
                }
            }
            data?.data != null -> uris += data.data!!
            cameraImageUri != null -> uris += cameraImageUri!!
        }

        return uris.takeIf { it.isNotEmpty() }?.toTypedArray()
    }

    private fun createImageUri(): Uri? {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: cacheDir
        val image = File.createTempFile("IMG_${timeStamp}_", ".jpg", storageDir)
        return FileProvider.getUriForFile(
            this,
            "${applicationContext.packageName}.fileprovider",
            image
        )
    }
}
