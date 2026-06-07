// JNI bridge for the camera_native trigger pipeline.
//
// Java side: com.samsung.camera.intelligence.trigger.NativeBridge.
// Signals cross the boundary as two parallel arrays (String[] keys, float[]
// values) and scores come back as a float[] in canonical trigger order, so the
// Java layer is a thin data passthrough with no algorithm logic.
#include <jni.h>

#include <array>
#include <string>
#include <vector>

#include "cv_stats.h"
#include "mimic_core.h"
#include "trigger_engine.h"

namespace {

camera_native::Signals buildSignals(JNIEnv* env, jobjectArray keys, jfloatArray values) {
    camera_native::Signals sig;
    if (keys == nullptr || values == nullptr) return sig;
    jsize n = env->GetArrayLength(keys);
    jsize vn = env->GetArrayLength(values);
    jsize count = n < vn ? n : vn;
    jfloat* vals = env->GetFloatArrayElements(values, nullptr);
    sig.reserve(static_cast<size_t>(count));
    for (jsize i = 0; i < count; ++i) {
        auto* jstr = static_cast<jstring>(env->GetObjectArrayElement(keys, i));
        if (jstr == nullptr) continue;
        const char* c = env->GetStringUTFChars(jstr, nullptr);
        sig[std::string(c)] = vals[i];
        env->ReleaseStringUTFChars(jstr, c);
        env->DeleteLocalRef(jstr);
    }
    env->ReleaseFloatArrayElements(values, vals, JNI_ABORT);
    return sig;
}

jfloatArray toJFloatArray(JNIEnv* env, const std::vector<float>& v) {
    jfloatArray out = env->NewFloatArray(static_cast<jsize>(v.size()));
    if (!v.empty()) {
        env->SetFloatArrayRegion(out, 0, static_cast<jsize>(v.size()), v.data());
    }
    return out;
}

std::vector<float> fromJFloatArray(JNIEnv* env, jfloatArray a) {
    std::vector<float> v;
    if (a == nullptr) return v;
    jsize n = env->GetArrayLength(a);
    v.resize(static_cast<size_t>(n));
    if (n > 0) env->GetFloatArrayRegion(a, 0, n, v.data());
    return v;
}

jintArray toJIntArray(JNIEnv* env, const std::vector<int32_t>& v) {
    jintArray out = env->NewIntArray(static_cast<jsize>(v.size()));
    if (!v.empty()) {
        env->SetIntArrayRegion(out, 0, static_cast<jsize>(v.size()), reinterpret_cast<const jint*>(v.data()));
    }
    return out;
}

std::vector<int> fromJIntArray(JNIEnv* env, jintArray a) {
    std::vector<int> v;
    if (a == nullptr) return v;
    jsize n = env->GetArrayLength(a);
    v.resize(static_cast<size_t>(n));
    if (n > 0) env->GetIntArrayRegion(a, 0, n, reinterpret_cast<jint*>(v.data()));
    return v;
}

template <size_t N>
jfloatArray toJFloatArray(JNIEnv* env, const std::array<float, N>& v) {
    jfloatArray out = env->NewFloatArray(static_cast<jsize>(N));
    env->SetFloatArrayRegion(out, 0, static_cast<jsize>(N), v.data());
    return out;
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_com_samsung_camera_intelligence_trigger_NativeBridge_nTriggerCount(JNIEnv*, jclass) {
    return static_cast<jint>(camera_native::trigger_names().size());
}

JNIEXPORT jobjectArray JNICALL
Java_com_samsung_camera_intelligence_trigger_NativeBridge_nTriggerNames(JNIEnv* env, jclass) {
    const auto& names = camera_native::trigger_names();
    jclass strCls = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(names.size()), strCls, nullptr);
    for (jsize i = 0; i < static_cast<jsize>(names.size()); ++i) {
        env->SetObjectArrayElement(arr, i, env->NewStringUTF(names[i].c_str()));
    }
    return arr;
}

JNIEXPORT jfloatArray JNICALL
Java_com_samsung_camera_intelligence_trigger_NativeBridge_nScore(
        JNIEnv* env, jclass, jobjectArray keys, jfloatArray values) {
    camera_native::Signals sig = buildSignals(env, keys, values);
    std::vector<float> scores = camera_native::score(sig);
    return toJFloatArray(env, scores);
}

JNIEXPORT jfloatArray JNICALL
Java_com_samsung_camera_intelligence_trigger_NativeBridge_nArbitrate(
        JNIEnv* env, jclass, jfloatArray scores, jfloatArray thresholds) {
    std::vector<float> s = fromJFloatArray(env, scores);
    std::vector<float> t = fromJFloatArray(env, thresholds);
    camera_native::arbitrate(s, t);
    return toJFloatArray(env, s);
}

JNIEXPORT jlong JNICALL
Java_com_samsung_camera_intelligence_trigger_NativeBridge_nNewHysteresis(
        JNIEnv*, jclass, jint enterFrames, jint exitFrames, jfloat releaseRatio) {
    auto* h = new camera_native::Hysteresis(enterFrames, exitFrames, releaseRatio);
    return reinterpret_cast<jlong>(h);
}

JNIEXPORT jfloatArray JNICALL
Java_com_samsung_camera_intelligence_trigger_NativeBridge_nStabilize(
        JNIEnv* env, jclass, jlong handle, jfloatArray scores, jfloatArray thresholds) {
    auto* h = reinterpret_cast<camera_native::Hysteresis*>(handle);
    std::vector<float> s = fromJFloatArray(env, scores);
    std::vector<float> t = fromJFloatArray(env, thresholds);
    if (h != nullptr) h->stabilize(s, t);
    return toJFloatArray(env, s);
}

JNIEXPORT void JNICALL
Java_com_samsung_camera_intelligence_trigger_NativeBridge_nFreeHysteresis(
        JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<camera_native::Hysteresis*>(handle);
}

JNIEXPORT jfloatArray JNICALL
Java_com_samsung_camera_intelligence_trigger_NativeBridge_nBokeh(
        JNIEnv* env, jclass, jintArray argb, jint width, jint height,
        jfloat bx, jfloat by, jfloat bw, jfloat bh) {
    jint* pixels = env->GetIntArrayElements(argb, nullptr);
    camera_native::BokehResult r = camera_native::bokeh_stats(
            reinterpret_cast<int32_t*>(pixels), width, height, bx, by, bw, bh);
    env->ReleaseIntArrayElements(argb, pixels, JNI_ABORT);
    std::vector<float> out = {r.bgSharpRatio, r.bgBlurStrength};
    return toJFloatArray(env, out);
}

JNIEXPORT jfloatArray JNICALL
Java_com_samsung_camera_intelligence_trigger_NativeBridge_nLensCorner(
        JNIEnv* env, jclass, jintArray argb, jint width, jint height) {
    jint* pixels = env->GetIntArrayElements(argb, nullptr);
    camera_native::LensResult r = camera_native::lens_corner(
            reinterpret_cast<int32_t*>(pixels), width, height);
    env->ReleaseIntArrayElements(argb, pixels, JNI_ABORT);
    std::vector<float> out = {r.probability, static_cast<float>(r.numBlockedPatches)};
    return toJFloatArray(env, out);
}

JNIEXPORT jintArray JNICALL
Java_com_samsung_camera_intelligence_trigger_NativeBridge_nGenerateLut(
        JNIEnv* env, jclass, jfloat contrast, jfloat highlights, jfloat shadows,
        jfloat saturation, jfloat highlightWarmth, jfloat shadowTint) {
    return toJIntArray(env, camera_native::generate_lut_pixels(
            contrast, highlights, shadows, saturation, highlightWarmth, shadowTint));
}

JNIEXPORT jintArray JNICALL
Java_com_samsung_camera_intelligence_trigger_NativeBridge_nIdentityLut(
        JNIEnv* env, jclass) {
    return toJIntArray(env, camera_native::identity_lut_pixels());
}

JNIEXPORT jintArray JNICALL
Java_com_samsung_camera_intelligence_trigger_NativeBridge_nComposeLut(
        JNIEnv* env, jclass, jintArray basePixels, jint width, jint height,
        jfloat contrast, jfloat highlights, jfloat shadows,
        jfloat saturation, jfloat highlightWarmth, jfloat shadowTint) {
    jint* src = env->GetIntArrayElements(basePixels, nullptr);
    std::vector<int32_t> out = camera_native::compose_lut_pixels(
            reinterpret_cast<int32_t*>(src), width, height,
            contrast, highlights, shadows, saturation, highlightWarmth, shadowTint);
    env->ReleaseIntArrayElements(basePixels, src, JNI_ABORT);
    return toJIntArray(env, out);
}

JNIEXPORT jintArray JNICALL
Java_com_samsung_camera_intelligence_trigger_NativeBridge_nApplyLut(
        JNIEnv* env, jclass, jintArray imagePixels, jint width, jint height,
        jintArray lutPixels, jint lutWidth, jint lutHeight) {
    jint* image = env->GetIntArrayElements(imagePixels, nullptr);
    jint* lut = env->GetIntArrayElements(lutPixels, nullptr);
    std::vector<int32_t> out = camera_native::apply_lut_pixels(
            reinterpret_cast<int32_t*>(image), width, height,
            reinterpret_cast<int32_t*>(lut), lutWidth, lutHeight);
    env->ReleaseIntArrayElements(imagePixels, image, JNI_ABORT);
    env->ReleaseIntArrayElements(lutPixels, lut, JNI_ABORT);
    return toJIntArray(env, out);
}

JNIEXPORT jfloatArray JNICALL
Java_com_samsung_camera_intelligence_trigger_NativeBridge_nExtractToneParams(
        JNIEnv* env, jclass, jintArray argb, jint width, jint height) {
    jint* pixels = env->GetIntArrayElements(argb, nullptr);
    auto out = camera_native::extract_tone_params(
            reinterpret_cast<int32_t*>(pixels), width, height);
    env->ReleaseIntArrayElements(argb, pixels, JNI_ABORT);
    return toJFloatArray(env, out);
}

JNIEXPORT jfloatArray JNICALL
Java_com_samsung_camera_intelligence_trigger_NativeBridge_nBuildToneCurve(
        JNIEnv* env, jclass, jfloat contrast, jfloat highlights, jfloat shadows,
        jint maxPoints) {
    return toJFloatArray(env, camera_native::build_tone_curve(contrast, highlights, shadows, maxPoints));
}

JNIEXPORT jfloatArray JNICALL
Java_com_samsung_camera_intelligence_trigger_NativeBridge_nIdentityToneCurve(
        JNIEnv* env, jclass, jint maxPoints) {
    return toJFloatArray(env, camera_native::identity_tone_curve(maxPoints));
}

JNIEXPORT jfloatArray JNICALL
Java_com_samsung_camera_intelligence_trigger_NativeBridge_nBuildSaturationMatrix(
        JNIEnv* env, jclass, jfloat saturation) {
    auto out = camera_native::build_saturation_matrix(saturation);
    return toJFloatArray(env, out);
}

JNIEXPORT jfloatArray JNICALL
Java_com_samsung_camera_intelligence_trigger_NativeBridge_nMasterMatchTopK(
        JNIEnv* env, jclass, jfloatArray query, jfloatArray flatEmbeddings,
        jint count, jint dim, jintArray candidateIndices, jint topK) {
    std::vector<float> queryVec = fromJFloatArray(env, query);
    std::vector<float> embVec = fromJFloatArray(env, flatEmbeddings);
    std::vector<int> candidateVec = fromJIntArray(env, candidateIndices);
    const int* candidates = candidateVec.empty() ? nullptr : candidateVec.data();
    std::vector<camera_native::SearchPair> pairs = camera_native::master_match_topk(
            queryVec.data(), dim, embVec.data(), count, candidates,
            static_cast<int>(candidateVec.size()), topK);
    std::vector<float> packed;
    packed.reserve(pairs.size() * 2);
    for (const auto& pair : pairs) {
        packed.push_back(static_cast<float>(pair.index));
        packed.push_back(pair.similarity);
    }
    return toJFloatArray(env, packed);
}

JNIEXPORT jfloatArray JNICALL
Java_com_samsung_camera_intelligence_trigger_NativeBridge_nMapExposure(
        JNIEnv* env, jclass,
        jfloat fixedAperture, jint baseIso, jint maxUsableIso,
        jfloat minShutterSeconds, jfloat maxShutterSeconds,
        jfloat refAperture, jstring refShutterSpeed, jint refIso, jint refWbKelvin,
        jstring sensorType, jfloat currentNoise,
        jstring currentLighting, jstring currentMotion) {
    const char* shutterChars = refShutterSpeed != nullptr ? env->GetStringUTFChars(refShutterSpeed, nullptr) : nullptr;
    const char* sensorChars = sensorType != nullptr ? env->GetStringUTFChars(sensorType, nullptr) : nullptr;
    const char* lightingChars = currentLighting != nullptr ? env->GetStringUTFChars(currentLighting, nullptr) : nullptr;
    const char* motionChars = currentMotion != nullptr ? env->GetStringUTFChars(currentMotion, nullptr) : nullptr;
    camera_native::ExposureMappingResult out = camera_native::map_exposure(
            fixedAperture, baseIso, maxUsableIso,
            minShutterSeconds, maxShutterSeconds,
            refAperture,
            shutterChars != nullptr ? std::string(shutterChars) : std::string("1/125"),
            refIso,
            refWbKelvin,
            sensorChars != nullptr ? std::string(sensorChars) : std::string("full_frame"),
            currentNoise,
            lightingChars != nullptr ? std::string(lightingChars) : std::string(),
            motionChars != nullptr ? std::string(motionChars) : std::string());
    if (shutterChars != nullptr) env->ReleaseStringUTFChars(refShutterSpeed, shutterChars);
    if (sensorChars != nullptr) env->ReleaseStringUTFChars(sensorType, sensorChars);
    if (lightingChars != nullptr) env->ReleaseStringUTFChars(currentLighting, lightingChars);
    if (motionChars != nullptr) env->ReleaseStringUTFChars(currentMotion, motionChars);
    std::vector<float> packed = {
            static_cast<float>(out.iso),
            static_cast<float>(out.shutterIndex),
            out.ev,
            static_cast<float>(out.wbModeCode),
            static_cast<float>(out.wbKelvin),
            static_cast<float>(out.meteringModeCode)
    };
    return toJFloatArray(env, packed);
}

}  // extern "C"
