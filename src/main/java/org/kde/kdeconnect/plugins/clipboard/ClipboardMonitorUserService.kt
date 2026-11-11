package org.kde.kdeconnect.plugins.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import org.kde.kdeconnect_tp.BuildConfig
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ClipboardMonitorUserService @JvmOverloads constructor(
    context: Context? = null
) : IClipboardMonitorService.Stub() {

    private val context: Context? = context ?: runCatching {
        Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication")
            .invoke(null) as? Context
    }.getOrNull()


    @Volatile
    private var callback: IClipboardMonitorCallback? = null

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var process: Process? = null

    @Volatile
    private var lastDeliveredClipboard: ClipboardSnapshot? = null

    private val lock = Any()

    private sealed class ClipboardSnapshot {
        data class Text(
            val text: String,
            val isSensitive: Boolean,
        ) : ClipboardSnapshot()

        data class File(
            val uri: String,
            val mimeType: String,
            val displayName: String,
            val size: Long,
            val isSensitive: Boolean,
        ) : ClipboardSnapshot()
    }

    private data class ClipboardReadResult(
        val snapshot: ClipboardSnapshot,
        val descriptor: ParcelFileDescriptor? = null,
    )

    private fun clipboardLogcatFilter(): String {
        return if (android.os.Build.VERSION.SDK_INT > android.os.Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            "E ClipboardService"
        } else {
            "ClipboardService:E"
        }
    }

    private fun clipboardLogcatSince(): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
    }

    override fun start(callback: IClipboardMonitorCallback?) {
        synchronized(lock) {
            this.callback = callback

            val existing = worker
            if (existing != null && existing.isAlive) {
                Log.d(TAG, "start(): worker already alive")
                return
            }

            Log.d(TAG, "start(): launching logcat watcher")
            worker = Thread(
                { runLoop() },
                "kdeconnect-clipboard-monitor"
            ).apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun runLoop() {
        while (!Thread.currentThread().isInterrupted) {
            var localProcess: Process? = null

            try {
                Log.d(
                    TAG,
                    "runLoop(): starting watcher uid=${android.os.Process.myUid()} pid=${android.os.Process.myPid()}"
                )

                localProcess = ProcessBuilder(
                    "logcat",
                    "-T", clipboardLogcatSince(),
                    clipboardLogcatFilter(),
                    "*:S"
                )
                    .redirectErrorStream(true)
                    .start()

                synchronized(lock) {
                    process = localProcess
                }

                BufferedReader(InputStreamReader(localProcess.inputStream)).useLines { lines ->
                    for (line in lines) {
                        if (Thread.currentThread().isInterrupted) break
                        if (!line.contains(BuildConfig.APPLICATION_ID)) continue

                        Log.d(TAG, "Observed denial line: $line")

                        val clipboard = tryReadClipboardPrivileged()
                        if (clipboard == null) {
                            Log.w(TAG, "Privileged clipboard read returned null")
                            continue
                        }

                        if (clipboard.snapshot == lastDeliveredClipboard) {
                            Log.d(TAG, "Clipboard content unchanged; suppressing duplicate callback")
                            clipboard.descriptor?.close()
                            continue
                        }

                        lastDeliveredClipboard = clipboard.snapshot

                        try {
                            when (val snapshot = clipboard.snapshot) {
                                is ClipboardSnapshot.Text -> {
                                    callback?.onClipboardTextChanged(snapshot.text, snapshot.isSensitive)
                                    Log.d(TAG, "Delivered clipboard text to app callback")
                                }
                                is ClipboardSnapshot.File -> {
                                    callback?.onClipboardFileChanged(
                                        clipboard.descriptor,
                                        snapshot.uri,
                                        snapshot.mimeType,
                                        snapshot.displayName,
                                        snapshot.size,
                                        snapshot.isSensitive,
                                    )
                                    Log.d(TAG, "Delivered clipboard file to app callback")
                                }
                            }
                        } catch (t: Throwable) {
                            Log.w(TAG, "Callback delivery failed", t)
                        } finally {
                            // The binder call duplicates the descriptor for the app.
                            clipboard.descriptor?.close()
                        }
                    }
                }

                try {
                    localProcess.waitFor()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }

                if (!Thread.currentThread().isInterrupted) {
                    try {
                        Thread.sleep(1000)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "runLoop() failure", t)
                if (!Thread.currentThread().isInterrupted) {
                    try {
                        Thread.sleep(1500)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
            } finally {
                try {
                    localProcess?.destroy()
                } catch (_: Throwable) {
                }

                synchronized(lock) {
                    if (process === localProcess) {
                        process = null
                    }
                }
            }
        }
    }

    private fun tryReadClipboardPrivileged(): ClipboardReadResult? {
        val binder = getClipboardBinder() ?: run {
            Log.w(TAG, "Clipboard binder is null")
            return null
        }

        val clipboard = getClipboardInterface(binder) ?: run {
            Log.w(TAG, "IClipboard interface is null")
            return null
        }

        val methods = clipboard.javaClass.methods
            .filter { it.name == "getPrimaryClip" }
            .sortedByDescending { it.parameterCount }

        for (pkg in listOf("com.android.shell", BuildConfig.APPLICATION_ID)) {
            for (method in methods) {
                val args = buildArgsFor(method.parameterTypes, pkg) ?: continue
                try {
                    val clip = method.invoke(clipboard, *args) as? ClipData ?: continue
                    val sensitive = isSensitive(clip)

                    val file = clipToFile(clip, sensitive)
                    if (file != null) {
                        Log.d(
                            TAG,
                            "Privileged clipboard read succeeded with file ${file.mimeType} via ${method.parameterTypes.joinToString { it.simpleName }} pkg=$pkg"
                        )
                        return ClipboardReadResult(file, openDescriptor(Uri.parse(file.uri)))
                    }

                    val text = clipToText(clip) ?: continue
                    Log.d(
                        TAG,
                        "Privileged clipboard read succeeded via ${method.parameterTypes.joinToString { it.simpleName }} pkg=$pkg"
                    )
                    return ClipboardReadResult(ClipboardSnapshot.Text(text, sensitive))
                } catch (_: Throwable) {
                }
            }
        }

        return null
    }

    private fun clipToFile(clip: ClipData, isSensitive: Boolean): ClipboardSnapshot.File? {
        if (clip.itemCount <= 0) return null

        val item = clip.getItemAt(0)
        val uri = item.uri ?: return null
        val mimeType = resolveMimeType(uri, clip.description) ?: return null
        if (!mimeType.startsWith("image/")) return null

        val displayName = queryDisplayName(uri)
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: defaultFileName(mimeType)

        return ClipboardSnapshot.File(
            uri = uri.toString(),
            mimeType = mimeType,
            displayName = displayName,
            size = querySize(uri),
            isSensitive = isSensitive,
        )
    }

    private fun resolveMimeType(uri: Uri, description: ClipDescription?): String? {
        val resolverType = try {
            context?.contentResolver?.getType(uri)
        } catch (_: Throwable) {
            null
        }

        if (resolverType?.startsWith("image/") == true) {
            return resolverType
        }

        if (description != null && description.hasMimeType("image/*")) {
            return description.getMimeType(0)
        }

        return resolverType
    }

    private fun queryDisplayName(uri: Uri): String? {
        val resolver = context?.contentResolver ?: return null

        try {
            resolver.query(
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

        return null
    }

    private fun querySize(uri: Uri): Long {
        val resolver = context?.contentResolver ?: return -1

        try {
            resolver.query(
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

    private fun openDescriptor(uri: Uri): ParcelFileDescriptor? {
        // ContentResolver cannot be used for content:// URIs here: the op
        // package name of every Context in this process stays the app's
        // package (createPackageContext inherits it), while the process runs
        // as the shell uid, so the activity manager rejects the provider
        // acquisition with "calling package ... does not match caller's
        // uid 2000". The `content` tool instead runs with the genuine
        // com.android.shell package, which matches this uid and is the
        // package the privileged clipboard read granted the clip's URIs to.
        if (ContentResolver.SCHEME_CONTENT == uri.scheme) {
            return openDescriptorViaContentRead(uri)
        }

        return try {
            context?.contentResolver?.openFileDescriptor(uri, "r")
        } catch (t: Throwable) {
            Log.w(TAG, "Cannot open clipboard URI for descriptor transfer", t)
            null
        }
    }

    private fun openDescriptorViaContentRead(uri: Uri): ParcelFileDescriptor? {
        var staging: File? = null
        try {
            staging = File.createTempFile("kdeconnect_clipboard_", ".tmp", File("/data/local/tmp"))
            val process = ProcessBuilder("content", "read", "--uri", uri.toString())
                .redirectOutput(staging)
                .start()
            val status = process.waitFor()
            if (status != 0 || staging.length() <= 0L) {
                Log.w(TAG, "content read of clipboard URI $uri failed with status $status")
                staging.delete()
                return null
            }

            // The descriptor keeps the file readable after the unlink; the
            // space is reclaimed once the app has closed its copy.
            return ParcelFileDescriptor.open(staging, ParcelFileDescriptor.MODE_READ_ONLY).also {
                staging.delete()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Cannot transfer clipboard URI via content read", t)
            staging?.delete()
            return null
        }
    }

    private fun defaultFileName(mimeType: String): String {
        val extension = MimeTypeMap.getSingleton()
            .getExtensionFromMimeType(mimeType)
            ?.takeIf { it.isNotBlank() }
            ?: "png"
        return "clipboard_${System.currentTimeMillis()}.$extension"
    }

    private fun isSensitive(clip: ClipData): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) return false
        return clip.description.extras
            ?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) == true
    }

    private fun getClipboardBinder(): IBinder? {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val getService = serviceManager.getDeclaredMethod("getService", String::class.java)
        return getService.invoke(null, "clipboard") as? IBinder
    }

    private fun getClipboardInterface(binder: IBinder): Any? {
        val stub = Class.forName("android.content.IClipboard\$Stub")
        val asInterface = stub.getDeclaredMethod("asInterface", IBinder::class.java)
        return asInterface.invoke(null, binder)
    }

    private fun buildArgsFor(paramTypes: Array<Class<*>>, opPkg: String): Array<Any?>? {
        val args = arrayOfNulls<Any>(paramTypes.size)

        for (i in paramTypes.indices) {
            val type = paramTypes[i]

            args[i] = when {
                type == String::class.java -> {
                    if (i == 0) opPkg else null
                }
                type.name == "int" || type == Int::class.javaObjectType -> 0
                type.name == "long" || type == Long::class.javaObjectType -> 0L
                type.name == "boolean" || type == Boolean::class.javaObjectType -> false
                !type.isPrimitive -> null
                else -> return null
            }
        }

        return args
    }

    private fun clipToText(clip: ClipData): String? {
        if (clip.itemCount <= 0) return null
        val item = clip.getItemAt(0)

        // Never hand a device-local image URI to the app: it cannot access it
        // reliably and syncing it as text is what created clipboard echo loops.
        if (item.uri != null && looksLikeImageUri(item.uri, clip.description)) {
            return null
        }

        return item.text?.toString()
            ?: item.htmlText
            ?: item.uri?.toString()
            ?: item.intent?.toUri(0)
    }

    private fun looksLikeImageUri(uri: Uri, description: ClipDescription?): Boolean {
        val mimeType = resolveMimeType(uri, description)
        if (mimeType?.startsWith("image/") == true) return true

        val extension = uri.lastPathSegment
            ?.substringAfterLast('.', "")
            ?.lowercase(Locale.US)
            ?: return false
        return extension in setOf("png", "jpg", "jpeg", "gif", "webp", "heic", "heif", "bmp")
    }

    override fun stop() {
        synchronized(lock) {
            try {
                worker?.interrupt()
            } catch (_: Throwable) {
            }

            try {
                process?.destroy()
            } catch (_: Throwable) {
            }

            worker = null
            process = null
            callback = null
            lastDeliveredClipboard = null
            Log.d(TAG, "stop(): stopped")
        }
    }

    override fun destroy() {
        Log.d(TAG, "destroy()")
        stop()
        System.exit(0)
    }

    companion object {
        private const val TAG = "KDEClipboardService"
    }
}
