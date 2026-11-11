/*
 * SPDX-FileCopyrightText: 2014 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.clipboard

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.helpers.FilesHelper
import org.kde.kdeconnect.plugins.Plugin
import org.kde.kdeconnect.plugins.PluginFactory.LoadablePlugin
import org.kde.kdeconnect.plugins.clipboard.ClipboardListener.ClipboardFile
import org.kde.kdeconnect.plugins.clipboard.ClipboardListener.ClipboardObserver
import org.kde.kdeconnect.ui.PluginSettingsFragment
import org.kde.kdeconnect_tp.R
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.Executors

@LoadablePlugin
class ClipboardPlugin : Plugin() {
    override val displayName: String
        get() = context.resources.getString(R.string.pref_plugin_clipboard)

    override val description: String
        get() = context.resources.getString(R.string.pref_plugin_clipboard_desc)

    override val hasSettings: Boolean = true

    override val settingsScope = SettingsScope.PER_DEVICE

    override fun getSettingsFragment(activity: Activity): PluginSettingsFragment {
        return PluginSettingsFragment.newInstance(pluginKey, device.deviceId, R.xml.clipboardplugin_preferences)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val clipboardFileExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "kdeconnect-clipboard-files").apply { isDaemon = true }
    }

    override fun onPacketReceived(np: NetworkPacket): Boolean {
        val content = np.getString("content")
        when (np.type) {
            PACKET_TYPE_CLIPBOARD -> {
                if (isDeviceLocalUri(content)) {
                    Log.w(TAG, "Ignoring remote content:// clipboard text; it is only valid on the sending device")
                    return false
                }
                ClipboardListener.instance(context).setText(content)
                return true
            }
            PACKET_TYPE_CLIPBOARD_CONNECT -> {
                val packetTime = np.getLong("timestamp")
                // If the packetTime is 0, it means the timestamp is unknown (so do nothing).
                if (packetTime == 0L || packetTime < ClipboardListener.instance(context).updateTimestamp) {
                    return false
                }
                if ("content" in np) { // change clipboard if content is in NetworkPacket
                    if (isDeviceLocalUri(content)) {
                        Log.w(TAG, "Ignoring remote content:// clipboard text; it is only valid on the sending device")
                    } else {
                        ClipboardListener.instance(context).setText(content)
                    }
                }
                return true
            }
            PACKET_TYPE_CLIPBOARD_FILE -> {
                if (!np.hasPayload() || !np.has("filename")) {
                    Log.w(TAG, "Ignoring clipboard file without a payload or filename")
                    np.payload?.close()
                    return false
                }
                receiveClipboardFile(np)
                return true
            }
            else -> throw UnsupportedOperationException("Unknown packet type: ${np.type}")
        }
    }

    private val observer: ClipboardObserver = object : ClipboardObserver {
        override fun clipboardChanged(content: String, contentType: ClipboardListener.ClipboardContentType) {
            if (contentType == ClipboardListener.ClipboardContentType.Password &&
                preferences.getBoolean(
                    context.getString(R.string.clipboard_preference_key_skip_sensitive),
                    false,
                )
            ) {
                return
            }
            propagateClipboard(content)
        }

        override fun clipboardFileChanged(
            file: ClipboardFile,
            contentType: ClipboardListener.ClipboardContentType,
        ) {
            if (contentType == ClipboardListener.ClipboardContentType.Password &&
                preferences.getBoolean(
                    context.getString(R.string.clipboard_preference_key_skip_sensitive),
                    false,
                )
            ) {
                return
            }
            propagateClipboardFile(file)
        }
    }

    @VisibleForTesting
    fun propagateClipboard(content: String) {
        if (ClipboardListener.isImageContentUri(context, content)) {
            Log.w(TAG, "Refusing to sync local image content:// URI as clipboard text")
            return
        }

        val np = NetworkPacket(PACKET_TYPE_CLIPBOARD)
        np["content"] = content
        device.sendPacket(np)
    }

    /**
     * Sends the clipboard image using the KDE Connect clipboard-file protocol.
     *
     * The remote client must advertise kdeconnect.clipboard.file. Old clients
     * (for example Soduto) do not, and in that case the image is skipped instead
     * of leaking a device-local content:// URI as clipboard text.
     */
    @VisibleForTesting
    fun propagateClipboardFile(file: ClipboardFile) {
        if (!file.file.isFile || file.size <= 0) {
            Log.w(TAG, "Not syncing missing or empty clipboard file ${file.displayName}")
            return
        }

        if (!device.supportsPacketType(PACKET_TYPE_CLIPBOARD_FILE)) {
            Log.i(TAG, "Remote client cannot receive clipboard files; not sending ${file.displayName}")
            return
        }

        val packet = NetworkPacket(PACKET_TYPE_CLIPBOARD_FILE).apply {
            this["filename"] = file.displayName
            // Let the peer drop a stale resend (this runs again on every
            // reconnect) instead of clobbering newer clipboard content.
            this["timestamp"] = ClipboardListener.instance(context).updateTimestamp
            payload = NetworkPacket.Payload(file.file.inputStream(), file.size)
        }
        device.sendPacket(packet)
    }

    private fun sendConnectPacket() {
        val listener = ClipboardListener.instance(context)
        val file = listener.currentFile
        if (file != null) {
            if (listener.currentContentType == ClipboardListener.ClipboardContentType.Password &&
                preferences.getBoolean(
                    context.getString(R.string.clipboard_preference_key_skip_sensitive),
                    false,
                )
            ) {
                return
            }
            propagateClipboardFile(file)
            return
        }

        val content = listener.currentContent ?: return // Send clipboard only if it had been initialized
        if (listener.currentContentType == ClipboardListener.ClipboardContentType.Password &&
            preferences.getBoolean(
                context.getString(R.string.clipboard_preference_key_skip_sensitive),
                false,
            )
        ) {
            return
        }
        val np = NetworkPacket(PACKET_TYPE_CLIPBOARD_CONNECT)
        np["timestamp"] = listener.updateTimestamp
        np["content"] = content
        device.sendPacket(np)
    }

    override fun onCreate() {
        ClipboardListener.instance(context).registerObserver(observer)
        sendConnectPacket()
    }

    override fun onDestroy() {
        ClipboardListener.instance(context).removeObserver(observer)
        clipboardFileExecutor.shutdownNow()
    }

    override val supportedPacketTypes: Array<String> =
        arrayOf(PACKET_TYPE_CLIPBOARD, PACKET_TYPE_CLIPBOARD_CONNECT, PACKET_TYPE_CLIPBOARD_FILE)

    override val outgoingPacketTypes: Array<String> =
        arrayOf(PACKET_TYPE_CLIPBOARD, PACKET_TYPE_CLIPBOARD_CONNECT, PACKET_TYPE_CLIPBOARD_FILE)

    override fun getUiButtons(): List<PluginUiButton> {
        return if (!canSyncAutomatically(context)) {
            listOf(PluginUiButton(context.getString(R.string.send_clipboard), R.drawable.ic_baseline_content_paste_24) { _: Activity? ->
                userInitiatedSendClipboard()
            })
        } else {
            emptyList()
        }
    }

    override fun getUiMenuEntries(): List<PluginUiMenuEntry> {
        return if (canSyncAutomatically(context)) {
            // If automatic sync is supported, add the manual action less prominently, as a menu entry instead of a button.
            listOf(PluginUiMenuEntry(context.getString(R.string.send_clipboard)) { _: Activity? ->
                userInitiatedSendClipboard()
            })
        } else {
            emptyList()
        }
    }

    private fun userInitiatedSendClipboard() {
        if (!isDeviceInitialized) return

        val listener = ClipboardListener.instance(context)

        val file = listener.currentFile
        if (file != null) {
            // Don't check if the content is sensitive, just send it.
            propagateClipboardFile(file)
            Toast.makeText(context, R.string.pref_plugin_clipboard_sent, Toast.LENGTH_SHORT).show()
            return
        }

        val currentContent = listener.currentContent
        if (currentContent != null) {
            if (ClipboardListener.isImageContentUri(context, currentContent)) {
                return
            }
            propagateClipboard(currentContent)
            Toast.makeText(context, R.string.pref_plugin_clipboard_sent, Toast.LENGTH_SHORT).show()
            return
        }

        // The listener has not seen a clipboard change yet (for example on Android 10
        // before Shizuku has connected). Read it directly if we are allowed to.
        val clipboardManager = context.getSystemService<ClipboardManager>()!!
        if (!clipboardManager.hasPrimaryClip()) return

        val item: ClipData.Item = clipboardManager.primaryClip!!.getItemAt(0)
        val uri = item.uri
        if (uri != null && ClipboardListener.isImageContentUri(context, uri.toString())) {
            Log.w(TAG, "Clipboard image is still being prepared; not sending its content:// URI")
            return
        }

        val content = item.coerceToText(context).toString()
        // Don't check if the content is sensitive, just send it
        propagateClipboard(content)
        Toast.makeText(context, R.string.pref_plugin_clipboard_sent, Toast.LENGTH_SHORT).show()
    }

    private fun receiveClipboardFile(np: NetworkPacket) {
        val packetTime = np.getLong("timestamp")
        val localTime = ClipboardListener.instance(context).updateTimestamp
        if (packetTime > 0 && packetTime < localTime) {
            Log.w(TAG, "Ignoring clipboard file with older timestamp: $packetTime vs $localTime")
            np.payload?.close()
            return
        }

        if (np.payloadSize > MAX_CLIPBOARD_FILE_BYTES) {
            Log.w(TAG, "Refusing oversized clipboard file: ${np.payloadSize} bytes")
            np.payload?.close()
            return
        }

        val fileName = sanitizeFileName(np.getString("filename", "clipboard_file"))
        val mimeType = FilesHelper.getMimeTypeFromFile(fileName)

        try {
            clipboardFileExecutor.execute {
                receiveClipboardFileInBackground(np, fileName, mimeType)
            }
        } catch (t: Throwable) {
            // The executor is shut down (plugin destroyed); do not leak the payload.
            Log.w(TAG, "Cannot receive clipboard file, plugin is shutting down", t)
            np.payload?.close()
        }
    }

    private fun receiveClipboardFileInBackground(np: NetworkPacket, fileName: String, mimeType: String) {
        var target: File? = null
        try {
            val directory = File(context.cacheDir, CLIPBOARD_CACHE_DIRECTORY)
            if (!directory.exists() && !directory.mkdirs()) {
                throw IOException("Cannot create clipboard cache directory")
            }
            pruneClipboardCache(directory)

            target = uniqueFile(directory, fileName)
            val input = np.payload?.inputStream ?: throw IOException("Clipboard payload has no input stream")

            val received = input.use { inputStream ->
                target.outputStream().use { outputStream ->
                    copyWithLimit(inputStream, outputStream)
                }
            }
            if (received <= 0) {
                throw IOException("Received an empty clipboard file")
            }

            val receivedFile = target
            mainHandler.post {
                ClipboardListener.instance(context).setFile(receivedFile, mimeType)
            }
        } catch (t: Throwable) {
            target?.delete()
            Log.e(TAG, "Could not receive clipboard file", t)
        } finally {
            np.payload?.close()
        }
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

    private fun sanitizeFileName(name: String): String {
        val basename = name.substringAfterLast('/').substringAfterLast('\\').trim()
        val sanitized = basename.replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_")
        return sanitized.take(MAX_FILE_NAME_LENGTH).ifBlank { "clipboard_file" }
    }

    private fun uniqueFile(directory: File, fileName: String): File {
        var candidate = File(directory, fileName)
        val baseName = candidate.nameWithoutExtension
        val extension = candidate.extension
        var suffix = 1

        while (candidate.exists()) {
            val name = if (extension.isEmpty()) {
                "$baseName ($suffix)"
            } else {
                "$baseName ($suffix).$extension"
            }
            candidate = File(directory, name)
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

    private fun isDeviceLocalUri(content: String): Boolean {
        val normalized = content.trim().lowercase(Locale.US)
        return normalized.startsWith("content://") || normalized.startsWith("file://")
    }

    companion object {
        private const val TAG = "ClipboardPlugin"

        /**
         * Packet containing just clipboard contents, sent when a device updates its clipboard.
         *
         * The body should look like so:
         * {
         * "content": "password"
         * }
         */
        const val PACKET_TYPE_CLIPBOARD = "kdeconnect.clipboard"

        /**
         * Packet containing file data, sent when a device updates its clipboard and the top element is an image/file.
         *
         * The body should look like so:
         * {
         * "filename": "image.jpg"
         * }
         */
        const val PACKET_TYPE_CLIPBOARD_FILE = "kdeconnect.clipboard.file"

        /**
         * Packet containing clipboard contents and a timestamp that the contents were last updated, sent
         * on first connection
         *
         * The timestamp is milliseconds since epoch. It can be 0, which indicates that the clipboard
         * update time is currently unknown.
         *
         * The body should look like so:
         * {
         * "timestamp": 542904563213,
         * "content": "password"
         * }
         */
        const val PACKET_TYPE_CLIPBOARD_CONNECT = "kdeconnect.clipboard.connect"

        private const val CLIPBOARD_CACHE_DIRECTORY = "clipboard"
        private const val CLIPBOARD_CACHE_MAX_AGE_MILLIS = 24L * 60 * 60 * 1000
        private const val MAX_FILE_NAME_LENGTH = 120
        private const val COPY_BUFFER_SIZE = 256 * 1024
        private const val MAX_CLIPBOARD_FILE_BYTES = 50L * 1024 * 1024

        fun canSyncAutomatically(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                return true
            }
            // Check if hack to read clipboard changes from logs is enabled
            return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED
        }
    }
}
