package com.example.simplegallery

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : ComponentActivity() {
    @Volatile private var insetTop = 0f
    @Volatile private var insetBottom = 0f

    private lateinit var webView: WebView

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {
            if (hasMediaPermission()) {
                loadApp()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)

        @Suppress("SetJavaScriptEnabled")
        webView.settings.javaScriptEnabled = true

        webView.settings.domStorageEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.settings.allowFileAccess = true
        webView.settings.allowContentAccess = true

        webView.webChromeClient = WebChromeClient()

        webView.webViewClient = object : WebViewClient() {

            override fun shouldInterceptRequest(
                view: WebView,
                url: String
            ): WebResourceResponse? {

                if (url.startsWith("https://gallery.local/thumb/")) {

                    val id = url
                        .substringAfterLast("/")
                        .toLongOrNull()

                    if (id != null) {
                        return createThumbnailResponse(id)
                    }
                }

                return super.shouldInterceptRequest(view, url)
            }
        }

        webView.addJavascriptInterface(
            GalleryBridge(),
            "Android"
        )

        setContentView(webView)

        ViewCompat.setOnApplyWindowInsetsListener(webView) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                        WindowInsetsCompat.Type.displayCutout()
            )
            val density = resources.displayMetrics.density
            insetTop = bars.top / density
            insetBottom = bars.bottom / density

            webView.evaluateJavascript(
                "typeof setInsets === 'function' && setInsets($insetTop, $insetBottom)",
                null
            )
            insets
        }
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {

                override fun handleOnBackPressed() {

                    webView.evaluateJavascript(
                        """
                        typeof handleAndroidBack === 'function'
                            ? handleAndroidBack()
                            : false
                        """.trimIndent()
                    ) { result ->

                        if (result == "false") {
                            finish()
                        }
                    }
                }
            }
        )

        if (hasMediaPermission()) {
            loadApp()
        } else {
            requestMediaPermission()
        }
    }

    private fun loadApp() {
        webView.loadUrl("file:///android_asset/index.html")
    }

    private fun hasMediaPermission(): Boolean {

        return if (Build.VERSION.SDK_INT >= 33) {

            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_MEDIA_IMAGES
            ) == PackageManager.PERMISSION_GRANTED &&

                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.READ_MEDIA_VIDEO
                    ) == PackageManager.PERMISSION_GRANTED

        } else {

            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestMediaPermission() {

        if (Build.VERSION.SDK_INT >= 33) {

            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO
                )
            )

        } else {

            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE
                )
            )
        }
    }

    private fun createThumbnailResponse(id: Long): WebResourceResponse? {

        return try {

            val imageUri = Uri.withAppendedPath(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                id.toString()
            )

            val videoUri = Uri.withAppendedPath(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                id.toString()
            )

            val bitmap =
                tryLoadThumbnail(imageUri)
                    ?: tryLoadThumbnail(videoUri)
                    ?: return null

            val output = ByteArrayOutputStream()

            bitmap.compress(
                Bitmap.CompressFormat.JPEG,
                82,
                output
            )

            bitmap.recycle()

            WebResourceResponse(
                "image/jpeg",
                null,
                output.toByteArray().inputStream()
            )

        } catch (_: Exception) {
            null
        }
    }

    private fun tryLoadThumbnail(uri: Uri): Bitmap? {

        return try {

            contentResolver.loadThumbnail(
                uri,
                Size(400, 400),
                null
            )

        } catch (_: Exception) {

            null
        }
    }

    inner class GalleryBridge {
        @JavascriptInterface
        @Suppress("unused")
        fun getInsets(): String =
            """{"top":$insetTop,"bottom":$insetBottom}"""

        @JavascriptInterface
        @Suppress("unused")
        fun getMedia(): String {

            val result = JSONArray()

            val projection = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.MEDIA_TYPE,
                MediaStore.MediaColumns.DATE_TAKEN,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.MIME_TYPE
            )

            val selection = """
            (
                ${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?
                OR
                ${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?
                )
            AND
            ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?
            """.trimIndent()

            val selectionArgs = arrayOf(
                MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
                "DCIM/Camera/%"
            )

            val sortOrder =
                "${MediaStore.MediaColumns.DATE_TAKEN} DESC, " +
                        "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"

            val collection =
                MediaStore.Files.getContentUri("external")

            contentResolver.query(
                collection,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->

                val idColumn =
                    cursor.getColumnIndexOrThrow(
                        MediaStore.MediaColumns._ID
                    )

                val typeColumn =
                    cursor.getColumnIndexOrThrow(
                        MediaStore.Files.FileColumns.MEDIA_TYPE
                    )
                val dateTakenColumn =
                    cursor.getColumnIndexOrThrow(
                        MediaStore.MediaColumns.DATE_TAKEN
                    )

                val modifiedColumn =
                    cursor.getColumnIndexOrThrow(
                        MediaStore.MediaColumns.DATE_MODIFIED
                    )

                while (cursor.moveToNext()) {

                    val id = cursor.getLong(idColumn)
                    val type = cursor.getInt(typeColumn)

                    val dateTaken =
                        cursor.getLong(dateTakenColumn)

                    val modified =
                        cursor.getLong(modifiedColumn)

                    val isVideo =
                        type ==
                                MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO

                    val uri = if (isVideo) {

                        Uri.withAppendedPath(
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                            id.toString()
                        )

                    } else {

                        Uri.withAppendedPath(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            id.toString()
                        )
                    }

                    val date =
                        if (dateTaken > 0) {
                            dateTaken
                        } else {
                            modified * 1000
                        }

                    result.put(
                        JSONObject().apply {

                            put(
                                "id",
                                id
                            )

                            put(
                                "uri",
                                uri.toString()
                            )

                            put(
                                "thumbnail",
                                "https://gallery.local/thumb/$id"
                            )

                            put(
                                "type",
                                if (isVideo) {
                                    "video"
                                } else {
                                    "image"
                                }
                            )

                            put(
                                "date",
                                date
                            )
                        }
                    )
                }
            }

            return result.toString()
        }
    }
}