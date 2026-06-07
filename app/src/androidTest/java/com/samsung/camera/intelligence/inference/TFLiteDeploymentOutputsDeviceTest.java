package com.samsung.camera.intelligence.inference;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import android.content.Context;
import android.content.res.AssetManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RunWith(AndroidJUnit4.class)
public class TFLiteDeploymentOutputsDeviceTest {

    @Test
    public void loadAndRunAllDeploymentOutputModelsFromAssets() throws Exception {
        Context testContext = InstrumentationRegistry.getInstrumentation().getContext();
        Context targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AssetManager assets = testContext.getAssets();

        String[] assetNames = assets.list("");
        assertNotNull("Failed to list androidTest assets", assetNames);

        List<String> modelNames = new ArrayList<>();
        for (String name : assetNames) {
            if (name != null && name.endsWith(".tflite")) {
                modelNames.add(name);
            }
        }

        assertFalse("No .tflite models found in androidTest assets", modelNames.isEmpty());

        for (String modelName : modelNames) {
            File tempModel = copyAssetToTempFile(assets, targetContext, modelName);
            try (TFLiteInferenceEngine engine = new TFLiteInferenceEngine(tempModel.getAbsolutePath(), false)) {
                int inputSize = engine.getInputSize();
                ByteBuffer input = ByteBuffer.allocateDirect(1 * 3 * inputSize * inputSize * 4)
                        .order(ByteOrder.nativeOrder());

                Map<String, float[]> outputs = engine.run(input);
                assertNotNull("Inference output is null for " + modelName, outputs);
                assertFalse("Inference output is empty for " + modelName, outputs.isEmpty());
            } finally {
                // Best-effort cleanup for repeated test runs.
                if (tempModel.exists()) {
                    tempModel.delete();
                }
            }
        }
    }

    private static File copyAssetToTempFile(AssetManager assets, Context context, String assetName) throws Exception {
        File cacheDir = context.getCacheDir();
        if (cacheDir == null) {
            cacheDir = context.getFilesDir();
        }
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }
        File outFile = new File(cacheDir, assetName);
        try (InputStream in = assets.open(assetName);
             FileOutputStream out = new FileOutputStream(outFile, false)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
        }
        return outFile;
    }
}
