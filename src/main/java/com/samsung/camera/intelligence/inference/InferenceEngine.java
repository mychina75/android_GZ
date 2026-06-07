package com.samsung.camera.intelligence.inference;

import java.nio.ByteBuffer;
import java.util.Map;

/**
 * Small runtime interface shared by TFLite and ONNX-backed engines.
 */
public interface InferenceEngine extends AutoCloseable {

    Map<String, float[]> run(ByteBuffer inputBuffer);

    default Map<String, float[]> run(ByteBuffer image, ByteBuffer rawImage, ByteBuffer motionFrame) {
        return run(image);
    }

    default Map<String, float[]> runCombined(ByteBuffer sceneImage, ByteBuffer rawImage,
                                             ByteBuffer motionFrame, ByteBuffer segImage) {
        return run(sceneImage);
    }

    default Map<String, float[]> runCombined(ByteBuffer sceneImage, ByteBuffer rawImage,
                                             ByteBuffer motionFrame, ByteBuffer segImage,
                                             boolean includeSegmentationOutput) {
        return runCombined(sceneImage, rawImage, motionFrame, segImage);
    }

    int getInputCount();

    int getSegInputSize();

    boolean isCombinedSceneSegModel();

    int getInputSize();

    String[] getOutputNames();

    @Override
    void close();
}
