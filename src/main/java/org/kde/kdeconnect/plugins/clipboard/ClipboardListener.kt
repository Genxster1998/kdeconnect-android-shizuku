/*
 * SPDX-FileCopyrightText: 2014 Albert Vaca Cintora <albertvaka@gmail.com>
 * SPDX-FileCopyrightText: 2021 Ilmaz Gumerov <ilmaz1309@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.clipboard

import android.Manifest
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.getSystemService
import org.kde.kdeconnect_tp.BuildConfig
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class ClipboardListener {
    enum class ClipboardContentType {
        Text,
        Password,
        File,
    }

    /**
     * An image (or other file-like clipboard item) that has been copied into
     * this app's cache so it can outlive the source app's URI grant.
     */
    data class ClipboardFile(
        val file: File,
        val displayName: String,
        val mimeType: String,
        val size: Long,
        val contentUri: Uri? = null,
    )

    interface ClipboardObserver {
        fun clipboardChanged(content: String, contentType: ClipboardContentType)

        /**
         * Called when the clipboard contains a file/image instead of plain text.
         *
         * The default no-op keeps existing observers source-compatible.
         */
        fun clipboardFileChanged(file: ClipboardFile, contentType: ClipboardContentType) {}
    }

    private val observers: HashSet<ClipboardObserver> = HashSet()

    private val context: Context
    var currentContent: String? = null
        private set
    var currentContentType: ClipboardContentType = ClipboardContentType.Text
        private set
    var updateTimestamp: Long = 0
        private set

    @Volatile
    var currentFile: ClipboardFile? = null
        private set

    private lateinit var cm: ClipboardManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val fileCacheExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "kdeconnect-clipboard-files").apply { isDaemon = true }
    }

    @Volatile
    private var clipboardGeneration = 0L

    @Volatile
    private var currentSourceUri: String? = null

    @Volatile
    private var shizukuBindingInFlight = false

    @Volatile
    private var shizukuBinderReceived = false

    @Volatile
    private var shizukuPermissionRequestInFlight = false

    @Volatile
    private var shizukuService: IClipboardMonitorService? = null

    @Volatile
    private var readLogsWorker: Thread? = null

    @Volatile
    private var readLogsProcess: Process? = null

    private val privilegedCallback = object : IClipboardMonitorCallback.Stub() {
        override fun onClipboardTextChanged(text: String?, isSensitive: Boolean) {
            if (text.isNullOrEmpty()) return

            Log.d(TAG, "Shizuku monitor delivered clipboard text")
            onPrivilegedClipboardText(text, isSensitive)
        }

        override fun onClipboardFileChanged(
            fd: ParcelFileDescriptor?,
            uri: String?,
            mimeType: String?,
            displayName: String?,
            size: Long,
            isSensitive: Boolean,
        ) {
            if (uri.isNullOrEmpty()) {
                fd?.close()
                return
            }

            Log.d(TAG, "Shizuku monitor delivered clipboard file: $mimeType")
            onPrivilegedClipboardFile(
                uri = Uri.parse(uri),
                mimeType = mimeType,
                displayName = displayName,
                size = size,
                isSensitive = isSensitive,
                source = fd,
            )
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        shizukuBinderReceived = true
        Log.d(TAG, "Shizuku binder received")
        ensureClipboardMonitor()
    }

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "Shizuku user service connected: $name")
            shizukuBindingInFlight = false

            try {
                val remote = IClipboardMonitorService.Stub.asInterface(service)
                shizukuService = remote
                remote?.start(privilegedCallback)
                Log.d(TAG, "Requested remote clipboard monitor start")
                stopReadLogsListener()
            } catch (t: Throwable) {
                Log.w(TAG, "Failed starting Shizuku clipboard monitor", t)
                shizukuService = null
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "Shizuku user service disconnected: $name")
            shizukuService = null
            shizukuBindingInFlight = false
            ensureClipboardMonitor()
        }
    }

    private constructor(ctx: Context) {
        context = ctx.applicationContext

        Handler(Looper.getMainLooper()).post {
            cm = context.getSystemService<ClipboardManager>()!!
            cm.addPrimaryClipChangedListener { this.onClipboardChanged() }
        }
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Log.d(TAG, "Registered Shizuku binder listener")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to register Shizuku binder listener", t)
        }

        ensureClipboardMonitor()
    }

    fun registerObserver(observer: ClipboardObserver) {
        observers.add(observer)
    }

    fun removeObserver(observer: ClipboardObserver) {
        observers.remove(observer)
    }

    private fun hasReadLogsPermission(): Boolean {
        return Build.VERSION.SDK_INT > Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED
    }

    private fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
    }

    private fun isShizukuAvailableAndAuthorized(): Boolean {
        return try {
            Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    private fun ensureClipboardMonitor() {
        val hasReadLogs = hasReadLogsPermission()
        val binderReceived = shizukuBinderReceived
        val shizukuAvailable = if (binderReceived) isShizukuAvailable() else false
        val shizukuAuthorized = if (binderReceived) isShizukuAvailableAndAuthorized() else false

        Log.d(
            TAG,
            "ensureClipboardMonitor: readLogs=$hasReadLogs binderReceived=$binderReceived shizukuAvailable=$shizukuAvailable shizukuAuthorized=$shizukuAuthorized"
        )

        if (shizukuAuthorized) {
            startShizukuClipboardMonitor()
            stopReadLogsListener()
            return
        }

        if (hasReadLogs) {
            startReadLogsListener()
        }

        if (!binderReceived) {
            Log.d(TAG, "Shizuku binder not received yet")
            return
        }

        if (shizukuAvailable) {
            requestShizukuPermission()
        } else {
            Log.d(TAG, "Shizuku binder unavailable")
        }
    }

    private fun requestShizukuPermission() {
        if (shizukuPermissionRequestInFlight) {
            Log.d(TAG, "Shizuku permission request already in flight")
            return
        }

        shizukuPermissionRequestInFlight = true
        Log.d(TAG, "Requesting Shizuku permission")

        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode != SHIZUKU_PERMISSION_REQUEST_CODE) return

                Shizuku.removeRequestPermissionResultListener(this)
                shizukuPermissionRequestInFlight = false

                Log.d(TAG, "Shizuku permission result: $grantResult")

                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    startShizukuClipboardMonitor()
                    stopReadLogsListener()
                }
            }
        }

        Shizuku.addRequestPermissionResultListener(listener)
        try {
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
        } catch (t: Throwable) {
            Shizuku.removeRequestPermissionResultListener(listener)
            shizukuPermissionRequestInFlight = false
            Log.w(TAG, "Shizuku.requestPermission failed", t)
        }
    }

    private fun clipboardLogcatFilter(): String {
        return if (Build.VERSION.SDK_INT > Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            "E ClipboardService"
        } else {
            "ClipboardService:E"
        }
    }

    private fun clipboardLogcatSince(): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
    }

    private fun startReadLogsListener() {
        val existing = readLogsWorker
        if (existing != null && existing.isAlive) return

        readLogsWorker = Thread({
            var localProcess: Process? = null
            try {
                localProcess = Runtime.getRuntime().exec(
                    arrayOf("logcat", "-T", clipboardLogcatSince(), clipboardLogcatFilter(), "*:S")
                )
                readLogsProcess = localProcess

                BufferedReader(InputStreamReader(localProcess.inputStream)).useLines { lines ->
                    lines.forEach { line ->
                        if (line.contains(BuildConfig.APPLICATION_ID)) {
                            Log.d(TAG, "READ_LOGS fallback observed denial; launching floating activity")
                            mainHandler.post {
                                try {
                                    context.startActivity(ClipboardFloatingActivity.getIntent(context, false))
                                } catch (t: Throwable) {
                                    Log.w(TAG, "READ_LOGS fallback launch failed", t)
                                }
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "READ_LOGS fallback watcher failed", t)
            } finally {
                try {
                    localProcess?.destroy()
                } catch (_: Throwable) {
                }

                if (readLogsProcess === localProcess) {
                    readLogsProcess = null
                }
                readLogsWorker = null
            }
        }, "kdeconnect-clipboard-readlogs").apply {
            isDaemon = true
            start()
        }

        Log.d(TAG, "Started READ_LOGS clipboard fallback")
    }

    private fun stopReadLogsListener() {
        try {
            readLogsWorker?.interrupt()
        } catch (_: Throwable) {
        }

        try {
            readLogsProcess?.destroy()
        } catch (_: Throwable) {
        }

        readLogsWorker = null
        readLogsProcess = null
    }

    private fun userServiceArgs(): Shizuku.UserServiceArgs {
        return Shizuku.UserServiceArgs(
            ComponentName(context, ClipboardMonitorUserService::class.java)
        )
            .daemon(false)
            .tag("clipboard_monitor_v5")
            .processNameSuffix("clipboard_monitor_v5")
            .version(5)
            .debuggable(BuildConfig.DEBUG)
    }

    private fun startShizukuClipboardMonitor() {
        if (shizukuService != null || shizukuBindingInFlight) {
            Log.d(TAG, "Shizuku clipboard monitor already started/binding")
            return
        }

        shizukuBindingInFlight = true
        Log.d(TAG, "Binding Shizuku user service")

        try {
            Shizuku.bindUserService(userServiceArgs(), userServiceConnection)
        } catch (t: Throwable) {
            shizukuBindingInFlight = false
            Log.w(TAG, "Shizuku.bindUserService failed", t)
        }
    }

    fun onClipboardChanged() {
        try {
            val clip = cm.primaryClip ?: return
            if (clip.itemCount <= 0) return

            val item = clip.getItemAt(0)
            val uri = item.uri

            // Suppress the callback caused by our own setText()/setFile() calls.
            if (uri != null && uri.toString() == currentSourceUri) return

            val contentType = detectContentType(clip)

            if (uri != null && isImageClip(clip, uri)) {
                val generation = beginFileClip(uri, contentType)
                cacheClipboardFile(
                    uri = uri,
                    mimeType = resolveMimeType(clip, uri),
                    displayName = null,
                    sizeHint = -1,
                    contentType = contentType,
                    generation = generation,
                    source = null,
                )
                return
            }

            val content = item.coerceToText(context).toString()
            handleTextChanged(content, contentType)
        } catch (t: Throwable) {
            // Probably the clipboard was not accessible or did not contain text.
            Log.d(TAG, "Clipboard read failed: ${t.message}")
        }
    }

    fun setText(text: String?) {
        if (!this::cm.isInitialized) return

        if (text == currentContent && currentFile == null && currentContentType == ClipboardContentType.Text) {
            return
        }

        clipboardGeneration++
        currentSourceUri = null
        updateTimestamp = System.currentTimeMillis()
        currentContent = text
        currentFile = null
        currentContentType = ClipboardContentType.Text
        cm.setPrimaryClip(ClipData.newPlainText(null, text))
    }

    /**
     * Puts [file] on the Android clipboard as a content:// URI and suppresses the
     * resulting local change callback.
     */
    fun setFile(file: File, mimeType: String) {
        if (!this::cm.isInitialized) return

        val uri = try {
            FileProvider.getUriForFile(
                context,
                "${BuildConfig.APPLICATION_ID}.fileprovider",
                file,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Cannot expose clipboard file to other apps", t)
            return
        }

        val displayName = file.name
        val clipboardFile = ClipboardFile(
            file = file,
            displayName = displayName,
            mimeType = mimeType,
            size = file.length(),
            contentUri = uri,
        )

        clipboardGeneration++
        currentSourceUri = uri.toString()
        updateTimestamp = System.currentTimeMillis()
        currentContent = null
        currentFile = clipboardFile
        currentContentType = ClipboardContentType.File
        cm.setPrimaryClip(ClipData.newUri(context.contentResolver, displayName, uri))
    }

    fun onPrivilegedClipboardText(text: String, isSensitive: Boolean) {
        mainHandler.post {
            val contentType = if (isSensitive) {
                ClipboardContentType.Password
            } else {
                ClipboardContentType.Text
            }
            handleTextChanged(text, contentType)
        }
    }

    fun onPrivilegedClipboardFile(
        uri: Uri,
        mimeType: String?,
        displayName: String?,
        size: Long,
        isSensitive: Boolean,
        source: ParcelFileDescriptor?,
    ) {
        mainHandler.post {
            val sourceKey = uri.toString()
            if (sourceKey == currentSourceUri) {
                source?.close()
                return@post
            }

            val resolvedMimeType = mimeType
                ?.takeIf { it.isNotBlank() }
                ?: resolveMimeType(null, uri)
                ?: "image/*"

            if (!resolvedMimeType.startsWith("image/")) {
                source?.close()
                return@post
            }

            val contentType = if (isSensitive) {
                ClipboardContentType.Password
            } else {
                ClipboardContentType.File
            }
            val generation = beginFileClip(uri, contentType)

            cacheClipboardFile(
                uri = uri,
                mimeType = resolvedMimeType,
                displayName = displayName,
                sizeHint = size,
                contentType = contentType,
                generation = generation,
                source = source,
            )
        }
    }

    private fun beginFileClip(uri: Uri, contentType: ClipboardContentType): Long {
        val generation = ++clipboardGeneration
        currentSourceUri = uri.toString()
        updateTimestamp = System.currentTimeMillis()
        currentContent = null
        currentFile = null
        currentContentType = contentType
        return generation
    }

    private fun handleTextChanged(text: String, contentType: ClipboardContentType) {
        if (text == currentContent && contentType == currentContentType && currentFile == null) {
            return
        }

        clipboardGeneration++
        currentSourceUri = null
        updateTimestamp = System.currentTimeMillis()
        currentContent = text
        currentFile = null
        currentContentType = contentType

        val snapshot = observers.toList()
        for (observer in snapshot) {
            observer.clipboardChanged(text, contentType)
        }
    }

    private fun cacheClipboardFile(
        uri: Uri,
        mimeType: String?,
        displayName: String?,
        sizeHint: Long,
        contentType: ClipboardContentType,
        generation: Long,
        source: ParcelFileDescriptor?,
    ) {
        fileCacheExecutor.execute {
            var target: File? = null
            try {
                val resolvedMimeType = mimeType
                    ?.takeIf { it.isNotBlank() }
                    ?: resolveMimeType(null, uri)
                    ?: "application/octet-stream"

                val resolvedName = displayName
                    ?.takeIf { it.isNotBlank() }
                    ?: queryDisplayName(uri)
                    ?: defaultFileName(resolvedMimeType)

                val resolvedSize = if (sizeHint >= 0) sizeHint else querySize(uri)
                if (resolvedSize > MAX_CLIPBOARD_FILE_BYTES) {
                    throw IOException("Clipboard file is too large: $resolvedSize bytes")
                }

                val directory = File(context.cacheDir, CLIPBOARD_CACHE_DIRECTORY)
                if (!directory.exists() && !directory.mkdirs()) {
                    throw IOException("Cannot create clipboard cache directory")
                }
                pruneClipboardCache(directory)

                target = File(directory, uniqueFileName(directory, normalizeFileName(resolvedName, resolvedMimeType)))

                val input = source?.let { ParcelFileDescriptor.AutoCloseInputStream(it) }
                    ?: context.contentResolver.openInputStream(uri)
                    ?: throw IOException("Cannot open clipboard URI $uri")

                val copied = input.use { inputStream ->
                    target.outputStream().use { outputStream ->
                        copyWithLimit(inputStream, outputStream)
                    }
                }

                if (copied <= 0) {
                    throw IOException("Clipboard file was empty")
                }

                if (generation != clipboardGeneration) {
                    target.delete()
                    return@execute
                }

                val cachedFile = ClipboardFile(
                    file = target,
                    displayName = resolvedName,
                    mimeType = resolvedMimeType,
                    size = copied,
                )

                mainHandler.post {
                    if (generation != clipboardGeneration) {
                        cachedFile.file.delete()
                        return@post
                    }

                    currentFile = cachedFile
                    currentContent = null
                    currentContentType = contentType

                    val snapshot = observers.toList()
                    for (observer in snapshot) {
                        observer.clipboardFileChanged(cachedFile, contentType)
                    }
                }
            } catch (t: Throwable) {
                target?.delete()
                Log.w(TAG, "Could not cache clipboard image", t)

                if (generation == clipboardGeneration) {
                    mainHandler.post {
                        if (generation == clipboardGeneration) {
                            currentSourceUri = null
                        }
                    }
                }
            }
        }
    }

    private fun isImageClip(clip: ClipData, uri: Uri): Boolean {
        val mimeType = resolveMimeType(clip, uri) ?: return false
        return mimeType.startsWith("image/")
    }

    private fun resolveMimeType(clip: ClipData?, uri: Uri): String? {
        val resolverType = try {
            context.contentResolver.getType(uri)
        } catch (_: Throwable) {
            null
        }

        if (resolverType?.startsWith("image/") == true) {
            return resolverType
        }

        val description = clip?.description
        if (description != null && description.hasMimeType("image/*")) {
            return description.getMimeType(0)
        }

        return resolverType
    }

    private fun queryDisplayName(uri: Uri): String? {
        try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        return cursor.getString(index)?.takeIf { it.isNotBlank() }
                    }
                }
            }
        } catch (_: Throwable) {
        }

        return uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
    }

    private fun querySize(uri: Uri): Long {
        try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (index >= 0 && !cursor.isNull(index)) {
                        return cursor.getLong(index)
                    }
                }
            }
        } catch (_: Throwable) {
        }

        return -1
    }

    private fun copyWithLimit(input: InputStream, output: OutputStream): Long {
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        var total = 0L

        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_CLIPBOARD_FILE_BYTES) {
                throw IOException("Clipboard file exceeds $MAX_CLIPBOARD_FILE_BYTES bytes")
            }
            output.write(buffer, 0, read)
        }

        return total
    }

    private fun defaultFileName(mimeType: String): String {
        val extension = MimeTypeMap.getSingleton()
            .getExtensionFromMimeType(mimeType)
            ?.takeIf { it.isNotBlank() }
            ?: "png"
        return "clipboard_${System.currentTimeMillis()}.$extension"
    }

    private fun normalizeFileName(name: String, mimeType: String): String {
        val sanitized = sanitizeFileName(name)
        if (File(sanitized).extension.isNotBlank()) {
            return sanitized
        }

        val extension = MimeTypeMap.getSingleton()
            .getExtensionFromMimeType(mimeType)
            ?.takeIf { it.isNotBlank() }
            ?: "png"
        return "$sanitized.$extension"
    }

    private fun sanitizeFileName(name: String): String {
        val basename = name.substringAfterLast('/').substringAfterLast('\\').trim()
        val sanitized = basename.replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_")
        return sanitized.take(MAX_FILE_NAME_LENGTH).ifBlank { "clipboard_file" }
    }

    private fun uniqueFileName(directory: File, fileName: String): String {
        var candidate = fileName
        val baseName = File(fileName).nameWithoutExtension
        val extension = File(fileName).extension
        var suffix = 1

        while (File(directory, candidate).exists()) {
            candidate = if (extension.isEmpty()) {
                "$baseName ($suffix)"
            } else {
                "$baseName ($suffix).$extension"
            }
            suffix++
        }

        return candidate
    }

    private fun pruneClipboardCache(directory: File) {
        val cutoff = System.currentTimeMillis() - CLIPBOARD_CACHE_MAX_AGE_MILLIS
        directory.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) {
                file.delete()
            }
        }
    }

    companion object {
        private const val TAG = "KDEClipboard"
        private const val SHIZUKU_PERMISSION_REQUEST_CODE = 1001
        private const val CLIPBOARD_CACHE_DIRECTORY = "clipboard"
        private const val CLIPBOARD_CACHE_MAX_AGE_MILLIS = 24L * 60 * 60 * 1000
        private const val MAX_FILE_NAME_LENGTH = 120
        private const val COPY_BUFFER_SIZE = 256 * 1024
        private const val MAX_CLIPBOARD_FILE_BYTES = 50L * 1024 * 1024

        private var _instance: ClipboardListener? = null

        fun instance(context: Context): ClipboardListener {
            return _instance ?: ClipboardListener(context).also { _instance = it }
        }

        fun detectContentType(clip: ClipData?): ClipboardContentType {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                return ClipboardContentType.Text
            }
            if (clip?.description?.extras
                    ?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) == true
            ) {
                return ClipboardContentType.Password
            }
            return ClipboardContentType.Text
        }

        /**
         * True when [value] is a content:// URI for an image.
         *
         * Such URIs are only meaningful on the device that created them, so they
         * must never be synced as clipboard text.
         */
        fun isImageContentUri(context: Context, value: String): Boolean {
            val uri = try {
                Uri.parse(value)
            } catch (_: Throwable) {
                return false
            }

            if (uri.scheme != ContentResolver.SCHEME_CONTENT) return false

            val mimeType = try {
                context.contentResolver.getType(uri)
            } catch (_: Throwable) {
                null
            }

            if (mimeType?.startsWith("image/") == true) return true

            val extension = uri.lastPathSegment
                ?.substringAfterLast('.', "")
                ?.lowercase(Locale.US)
                ?: return false
            return extension in setOf("png", "jpg", "jpeg", "gif", "webp", "heic", "heif", "bmp")
        }
    }
}
