#ifndef CAMERA_NATIVE_MIMIC_CORE_H
#define CAMERA_NATIVE_MIMIC_CORE_H

#include <array>
#include <cstdint>
#include <string>
#include <vector>

namespace camera_native {

constexpr int MIMIC_LUT_SIZE = 33;

struct SearchPair {
    int index;
    float similarity;
};

struct ExposureMappingResult {
    int iso;
    int shutterIndex;
    float ev;
    int wbModeCode;
    int wbKelvin;
    int meteringModeCode;
};

std::vector<int32_t> generate_lut_pixels(float contrast, float highlights,
                                         float shadows, float saturation,
                                         float highlightWarmth, float shadowTint);

std::vector<int32_t> identity_lut_pixels();

std::vector<int32_t> compose_lut_pixels(const int32_t* basePixels, int width, int height,
                                        float contrast, float highlights,
                                        float shadows, float saturation,
                                        float highlightWarmth, float shadowTint);

std::vector<int32_t> apply_lut_pixels(const int32_t* imagePixels, int width, int height,
                                      const int32_t* lutPixels, int lutWidth, int lutHeight);

std::array<float, 6> extract_tone_params(const int32_t* pixels, int width, int height);

std::vector<float> build_tone_curve(float contrast, float highlights,
                                    float shadows, int maxPoints);

std::vector<float> identity_tone_curve(int maxPoints);

std::array<float, 9> build_saturation_matrix(float saturation);

std::vector<SearchPair> master_match_topk(const float* query, int dim,
                                          const float* flatEmbeddings, int count,
                                          const int* candidates, int candidateCount,
                                          int topK);

ExposureMappingResult map_exposure(float fixedAperture, int baseIso, int maxUsableIso,
                                   float minShutterSeconds, float maxShutterSeconds,
                                   float refAperture, const std::string& refShutterSpeed,
                                   int refIso, int refWbKelvin,
                                   const std::string& sensorType,
                                   float currentNoise,
                                   const std::string& currentLighting,
                                   const std::string& currentMotion);

}  // namespace camera_native

#endif  // CAMERA_NATIVE_MIMIC_CORE_H
