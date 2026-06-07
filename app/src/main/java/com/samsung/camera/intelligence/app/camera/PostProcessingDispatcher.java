package com.samsung.camera.intelligence.app.camera;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;

import com.samsung.camera.intelligence.models.ToolRecommendation;
import com.samsung.camera.intelligence.models.ToolRecommendationResult;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class PostProcessingDispatcher {

    public interface Listener {
        void onPostProcessingMessage(String message);
    }

    public interface RemotePostProcessingService {
        ExecutionResult execute(String toolName, Map<String, Object> parameters, String inputImagePath);
    }

    public static class ExecutionResult {
        public final boolean success;
        public final String outputImagePath;
        public final String message;

        public ExecutionResult(boolean success, String outputImagePath, String message) {
            this.success = success;
            this.outputImagePath = outputImagePath;
            this.message = message;
        }
    }

    public static class ExecutionSummary {
        public int successCount;
        public int failedCount;
        public int pendingCount;
        public String latestOutputPath;
    }

    private final Listener listener;
    private RemotePostProcessingService remoteService;
    private final List<ToolRecommendation> pendingQueue = new ArrayList<>();

    public PostProcessingDispatcher(Listener listener) {
        this(listener, null);
    }

    public PostProcessingDispatcher(Listener listener, RemotePostProcessingService remoteService) {
        this.listener = listener;
        this.remoteService = remoteService;
    }

    public void setRemoteService(RemotePostProcessingService remoteService) {
        this.remoteService = remoteService;
    }

    public List<ToolRecommendation> getPendingQueueSnapshot() {
        return new ArrayList<>(pendingQueue);
    }

    public ExecutionSummary dispatchFrom(ToolRecommendationResult result, String inputImagePath) {
        ExecutionSummary summary = new ExecutionSummary();
        if (result == null) {
            return summary;
        }
        if (inputImagePath == null || inputImagePath.trim().isEmpty()) {
            listener.onPostProcessingMessage("No source image available for post-processing");
            summary.failedCount = 1;
            return summary;
        }

        String currentInput = inputImagePath;
        List<ToolRecommendation> queue = new ArrayList<>();
        if (result.getPostProcessingTools() != null) {
            queue.addAll(result.getPostProcessingTools());
        }

        for (ToolRecommendation tool : queue) {
            String name = tool.getToolName();
            if (name == null) {
                continue;
            }

            if (!(name.startsWith("Gallery_") || name.startsWith("PhotoEditor_"))) {
                continue;
            }

            ExecutionResult local = executeLocal(name, tool.getParameters(), currentInput);
            if (local != null) {
                if (local.success) {
                    summary.successCount++;
                    summary.latestOutputPath = local.outputImagePath;
                    currentInput = local.outputImagePath != null ? local.outputImagePath : currentInput;
                } else {
                    summary.failedCount++;
                }
                listener.onPostProcessingMessage(local.message);
                continue;
            }

            if (remoteService != null) {
                ExecutionResult remote = remoteService.execute(name,
                        tool.getParameters() == null ? Collections.emptyMap() : tool.getParameters(),
                        currentInput);
                if (remote != null && remote.success) {
                    summary.successCount++;
                    summary.latestOutputPath = remote.outputImagePath;
                    currentInput = remote.outputImagePath != null ? remote.outputImagePath : currentInput;
                    listener.onPostProcessingMessage(remote.message);
                } else {
                    summary.failedCount++;
                    String msg = remote == null ? "Remote service returned null" : remote.message;
                    listener.onPostProcessingMessage("Remote post-processing failed: " + msg);
                }
            } else {
                summary.pendingCount++;
                pendingQueue.add(tool);
                listener.onPostProcessingMessage("Queued (service missing): " + name);
            }
        }

        if (summary.pendingCount > 0) {
            listener.onPostProcessingMessage("Pending post tools: " + summary.pendingCount + " (no remote service configured)");
        }
        return summary;
    }

    private ExecutionResult executeLocal(String toolName, Map<String, Object> parameters, String inputImagePath) {
        if ("Gallery_AutoFit".equals(toolName)) {
            return applyAutoFit(inputImagePath);
        }
        if ("Gallery_Crop".equals(toolName) || "PhotoEditor_SmartCrop".equals(toolName)) {
            String ratio = readString(parameters, "aspect_ratio", "auto");
            return applyCenterCrop(inputImagePath, ratio);
        }
        if ("Gallery_AutoTilt".equals(toolName)) {
            return new ExecutionResult(true, inputImagePath, "AutoTilt executed (no-op fallback)");
        }
        return null;
    }

    private ExecutionResult applyAutoFit(String path) {
        Bitmap src = BitmapFactory.decodeFile(path);
        if (src == null) {
            return new ExecutionResult(false, null, "AutoFit failed: cannot decode input image");
        }

        Bitmap dst = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(dst);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        // Mild enhancement: small contrast + brightness lift.
        float contrast = 1.08f;
        float brightness = 8f;
        ColorMatrix cm = new ColorMatrix(new float[]{
                contrast, 0, 0, 0, brightness,
                0, contrast, 0, 0, brightness,
                0, 0, contrast, 0, brightness,
                0, 0, 0, 1, 0
        });
        paint.setColorFilter(new ColorMatrixColorFilter(cm));
        canvas.drawBitmap(src, 0, 0, paint);

        String out = outputPath(path, "autofit");
        boolean ok = writeJpeg(dst, out);
        src.recycle();
        dst.recycle();
        if (!ok) {
            return new ExecutionResult(false, null, "AutoFit failed: cannot write output");
        }
        return new ExecutionResult(true, out, "AutoFit applied: " + out);
    }

    private ExecutionResult applyCenterCrop(String path, String aspectRatio) {
        Bitmap src = BitmapFactory.decodeFile(path);
        if (src == null) {
            return new ExecutionResult(false, null, "Crop failed: cannot decode input image");
        }

        float targetRatio = parseRatio(aspectRatio);
        if (targetRatio <= 0f) {
            targetRatio = 4f / 3f;
        }

        int w = src.getWidth();
        int h = src.getHeight();
        float srcRatio = (float) w / (float) h;

        int cropW = w;
        int cropH = h;
        if (srcRatio > targetRatio) {
            cropW = Math.round(h * targetRatio);
        } else {
            cropH = Math.round(w / targetRatio);
        }
        int x = (w - cropW) / 2;
        int y = (h - cropH) / 2;

        Bitmap cropped = Bitmap.createBitmap(src, x, y, cropW, cropH);
        String out = outputPath(path, "crop");
        boolean ok = writeJpeg(cropped, out);
        src.recycle();
        cropped.recycle();
        if (!ok) {
            return new ExecutionResult(false, null, "Crop failed: cannot write output");
        }
        return new ExecutionResult(true, out, "Crop applied: " + out);
    }

    private static float parseRatio(String raw) {
        if (raw == null) {
            return -1f;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty() || "auto".equals(value) || "original".equals(value)) {
            return -1f;
        }
        if (!value.contains(":")) {
            return -1f;
        }
        String[] p = value.split(":");
        if (p.length != 2) {
            return -1f;
        }
        try {
            float a = Float.parseFloat(p[0]);
            float b = Float.parseFloat(p[1]);
            if (a <= 0f || b <= 0f) {
                return -1f;
            }
            return a / b;
        } catch (Exception ignore) {
            return -1f;
        }
    }

    private static String readString(Map<String, Object> map, String key, String fallback) {
        if (map == null) {
            return fallback;
        }
        Object value = map.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private static String outputPath(String inputPath, String suffix) {
        File in = new File(inputPath);
        File parent = in.getParentFile();
        String name = in.getName();
        int dot = name.lastIndexOf('.');
        String stem = dot >= 0 ? name.substring(0, dot) : name;
        String ext = dot >= 0 ? name.substring(dot) : ".jpg";
        return new File(parent, stem + "_" + suffix + ext).getAbsolutePath();
    }

    private static boolean writeJpeg(Bitmap bmp, String path) {
        try (FileOutputStream fos = new FileOutputStream(path)) {
            return bmp.compress(Bitmap.CompressFormat.JPEG, 92, fos);
        } catch (Exception e) {
            return false;
        }
    }
}
