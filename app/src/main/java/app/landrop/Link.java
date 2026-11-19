package app.landrop;

import android.net.Uri;

/** واجهة مشتركة لطرق النقل المباشر (Nearby / بلوتوث). الـ Listener ممكن يتنادى من أي Thread. */
interface Link {
    interface Listener {
        void onDevice(String id, String name);
        void onDeviceLost(String id);
        void onStatus(String text);
        void onConnected(String name);
        void onDisconnected(String name);
        void onProgress(String label, int percent);
        void onReceived(String fileName, String from);
        void onConfirm(String name, Runnable accept, Runnable reject);
    }

    void start();
    void rescan();
    void stop();
    void connect(String id);
    boolean send(Uri uri, String name, long size);
}
