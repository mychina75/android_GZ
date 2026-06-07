package com.samsung.camera.intelligence.app.util;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Tiny helper that publishes image files into the system Gallery so the user
 * can browse them directly from the stock Gallery app.
 *
 * <p>On API 29+ we use {@link MediaStore} with {@code RELATIVE_PATH} (no
 * runtime storage permission required). On older devices (we support API 26+)
 * we fall back to writing into the shared Pictures directory and trigger a
 * {@link MediaScannerConnection#scanFile} so the file appears in the Gallery.
 */
public final class MediaStoreSaver {

    public static final String ALBUM_NAME = "IntelligentCamera";
    private static final String TAG = "MediaStoreSaver";

    private MediaStoreSaver() {}

    /**
     * Copy {@code source} into the public {@code Pictures/IntelligentCamera/}
     * album with {@code displayName} and {@code mimeType}. Returns the public
     * {@link Uri} (Q+) or {@code null} on failure.
     */
    public static Uri saveImage(Context ctx, File source, String displayName, String mimeType) {
        if (source == null || !source.exists()) return null;
        try (InputStream in = new FileInputStream(source)) {
            return saveImage(ctx, in, displayName, mimeType);
        } catch (Exception e) {
            Log.w(TAG, "saveImage from file failed: " + source, e);
            return null;
        }
    }

    /**
     * Copy {@code in} into the public album. Caller retains ownership of the
     * stream and is responsible for closing it.
     */
    public static Uri saveImage(Context ctx, InputStream in, String displayName, String mimeType) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return saveViaMediaStoreQ(ctx, in, displayName, mimeType);
        }
        return saveViaLegacy(ctx, in, displayName, mimeType);
    }

    private static Uri saveViaMediaStoreQ(Context ctx, InputStream in,
                                          String displayName, String mimeType) {
        ContentResolver cr = ctx.getContentResolver();
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Images.Media.DISPLAY_NAME, displayName);
        cv.put(MediaStore.Images.Media.MIME_TYPE, mimeType);
        cv.put(MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/" + ALBUM_NAME);
        cv.put(MediaStore.Images.Media.IS_PENDING, 1);

        Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
        if (uri == null) {
            Log.w(TAG, "MediaStore.insert returned null for " + displayName);
            return null;
        }
        try (OutputStream out = cr.openOutputStream(uri)) {
            if (out == null) throw new java.io.IOException("openOutputStream null");
            copy(in, out);
        } catch (Exception e) {
            Log.w(TAG, "saveViaMediaStoreQ write failed: " + displayName, e);
            cr.delete(uri, null, null);
            return null;
        }
        ContentValues clear = new ContentValues();
        clear.put(MediaStore.Images.Media.IS_PENDING, 0);
        cr.update(uri, clear, null, null);
        return uri;
    }

    private static Uri saveViaLegacy(Context ctx, InputStream in,
                                     String displayName, String mimeType) {
        File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES), ALBUM_NAME);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "Failed to create album dir: " + dir);
            return null;
        }
        File out = new File(dir, displayName);
        try (OutputStream os = new FileOutputStream(out)) {
            copy(in, os);
        } catch (Exception e) {
            Log.w(TAG, "saveViaLegacy write failed: " + out, e);
            return null;
        }
        // Best-effort: ask the media scanner to pick it up.
        try {
            MediaScannerConnection.scanFile(ctx,
                    new String[]{out.getAbsolutePath()},
                    new String[]{mimeType},
                    null);
        } catch (Exception ignore) {}
        return Uri.fromFile(out);
    }

    private static void copy(InputStream in, OutputStream out) throws java.io.IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        out.flush();
    }
}
