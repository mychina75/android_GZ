package com.samsung.camera.intelligence.app.camera;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;

import androidx.camera.core.ImageProxy;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

public final class YuvBitmapConverter {

    private YuvBitmapConverter() {
    }

    public static Bitmap toBitmap(ImageProxy image) {
        byte[] nv21 = yuv420ToNv21(image);
        YuvImage yuv = new YuvImage(nv21, ImageFormat.NV21, image.getWidth(), image.getHeight(), null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        yuv.compressToJpeg(new Rect(0, 0, image.getWidth(), image.getHeight()), 90, out);
        byte[] jpeg = out.toByteArray();
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
    }

    private static byte[] yuv420ToNv21(ImageProxy image) {
        ImageProxy.PlaneProxy[] planes = image.getPlanes();
        ByteBuffer yBuffer = planes[0].getBuffer();
        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();

        int ySize = yBuffer.remaining();
        int uSize = uBuffer.remaining();
        int vSize = vBuffer.remaining();

        byte[] nv21 = new byte[ySize + uSize + vSize];
        yBuffer.get(nv21, 0, ySize);

        int chromaOffset = ySize;
        byte[] uBytes = new byte[uSize];
        byte[] vBytes = new byte[vSize];
        uBuffer.get(uBytes);
        vBuffer.get(vBytes);

        int pixelStride = planes[1].getPixelStride();
        int rowStride = planes[1].getRowStride();
        int width = image.getWidth();
        int height = image.getHeight();
        int chromaHeight = height / 2;
        int chromaWidth = width / 2;

        if (pixelStride == 2) {
            for (int row = 0; row < chromaHeight; row++) {
                int uRowStart = row * rowStride;
                int vRowStart = row * planes[2].getRowStride();
                for (int col = 0; col < chromaWidth; col++) {
                    int uvIndex = col * pixelStride;
                    nv21[chromaOffset++] = vBytes[vRowStart + uvIndex];
                    nv21[chromaOffset++] = uBytes[uRowStart + uvIndex];
                }
            }
            return nv21;
        }

        for (int row = 0; row < chromaHeight; row++) {
            int uRowStart = row * rowStride;
            int vRowStart = row * planes[2].getRowStride();
            for (int col = 0; col < chromaWidth; col++) {
                nv21[chromaOffset++] = vBytes[vRowStart + col];
                nv21[chromaOffset++] = uBytes[uRowStart + col];
            }
        }
        return nv21;
    }
}
