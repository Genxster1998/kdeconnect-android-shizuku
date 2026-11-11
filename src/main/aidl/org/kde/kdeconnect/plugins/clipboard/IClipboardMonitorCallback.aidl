package org.kde.kdeconnect.plugins.clipboard;

import android.os.ParcelFileDescriptor;

interface IClipboardMonitorCallback {
    void onClipboardTextChanged(String text, boolean isSensitive);

    void onClipboardFileChanged(in ParcelFileDescriptor fd, String uri, String mimeType, String displayName, long size, boolean isSensitive);
}
