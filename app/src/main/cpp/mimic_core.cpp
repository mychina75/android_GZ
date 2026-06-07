#include "mimic_core.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cctype>
#include <limits>
#include <string>
#include <vector>

namespace camera_native {

namespace {

constexpr float kNeutralContrastStd = 0.18f;
constexpr float kNeutralHighlightsMean = 0.72f;
constexpr float kNeutralShadowsMean = 0.28f;
constexpr float kNeutralSaturation = 0.35f;
constexpr float kNeutralWarmthDeg = 30.0f;
constexpr float kNeutralShadowHueDeg = 220.0f;

constexpr int kDefaultCurvePoints = 32;
constexpr float kGammaExp = 1.0f / 2.2f;

constexpr int kWbAuto = 0;
constexpr int kWbDaylight = 1;
constexpr int kWbCloudy = 2;
constexpr int kWbFluorescent = 3;
constexpr int kWbIncandescent = 4;
constexpr int kWbCustom = 5;

constexpr int kMeteringCenterWeighted = 0;
constexpr int kMeteringMatrix = 1;
constexpr int kMeteringSpot = 2;

constexpr int kDefaultValidIsos[] = {50, 100, 200, 400, 800, 1600, 3200};
constexpr const char* kDefaultValidShutters[] = {
        "1/12000", "1/8000", "1/6000", "1/4000", "1/3200", "1/2500", "1/2000",
        "1/1600", "1/1250", "1/1000", "1/800", "1/640", "1/500", "1/400",
        "1/320", "1/250", "1/200", "1/160", "1/125", "1/100", "1/80", "1/60",
        "1/50", "1/40", "1/30", "1/25", "1/20", "1/15", "1/13", "1/10",
        "1/8", "1/6", "1/5", "1/4", "1/3", "1/2.5", "1/2", "1/1.6", "1/1.3",
        "1", "1.3", "1.6", "2", "2.5", "3", "4", "5", "6", "8", "10",
        "13", "15", "20", "25", "30"
};

inline float clamp(float v, float lo, float hi) {
    if (v < lo) return lo;
    if (v > hi) return hi;
    return v;
}

inline float clamp01(float v) {
    return clamp(v, 0.0f, 1.0f);
}

inline float clamp100(float v) {
    return clamp(v, -100.0f, 100.0f);
}

inline int to_byte(float v) {
    return std::max(0, std::min(255, static_cast<int>(std::lround(v * 255.0f))));
}

inline int32_t pack_argb(int a, int r, int g, int b) {
    return static_cast<int32_t>(((a & 0xFF) << 24)
            | ((r & 0xFF) << 16)
            | ((g & 0xFF) << 8)
            | (b & 0xFF));
}

inline float channel_r(int32_t c) { return ((c >> 16) & 0xFF) / 255.0f; }
inline float channel_g(int32_t c) { return ((c >> 8) & 0xFF) / 255.0f; }
inline float channel_b(int32_t c) { return (c & 0xFF) / 255.0f; }
inline int alpha_of(int32_t c) { return (c >> 24) & 0xFF; }

float smoothstep(float edge0, float edge1, float x) {
    float t = (x - edge0) / (edge1 - edge0);
    t = clamp01(t);
    return t * t * (3.0f - 2.0f * t);
}

float wrap_degrees(float deg) {
    while (deg < 0.0f) deg += 360.0f;
    while (deg >= 360.0f) deg -= 360.0f;
    return deg;
}

float signed_hue_diff(float hue, float neutral) {
    float diff = wrap_degrees(hue - neutral + 180.0f) - 180.0f;
    return diff;
}

float round1(float v) {
    return std::round(v * 10.0f) / 10.0f;
}

std::array<float, 3> dampen_extreme_tone_spread(float contrast, float highlights, float shadows) {
    float combinedSpread = shadows - highlights;
    if (combinedSpread > 45.0f) {
        float excess = (combinedSpread - 45.0f) * 0.5f;
        shadows -= excess;
        highlights += excess;
    }
    return {contrast, highlights, shadows};
}

float apply_display_contrast(float y, float contrast) {
    if (std::fabs(contrast) < 1.0f) return y;
    float strength = contrast / 100.0f * 0.7f;
    float s = 0.5f * std::sin(static_cast<float>(M_PI) * (y - 0.5f));
    return y + strength * s;
}

float apply_display_highlights(float y, float highlights) {
    if (std::fabs(highlights) < 1.0f) return y;
    float weight = smoothstep(0.35f, 0.75f, y);
    float shift = highlights / 100.0f * 0.30f;
    return y + shift * weight;
}

float apply_display_shadows(float y, float shadows) {
    if (std::fabs(shadows) < 1.0f) return y;
    float weight = 1.0f - smoothstep(0.20f, 0.55f, y);
    float shift = shadows / 100.0f * 0.25f;
    return y + shift * weight;
}

std::vector<float> build_display_curve(int n, float contrast, float highlights, float shadows) {
    std::vector<float> lut(static_cast<size_t>(n), 0.0f);
    for (int i = 0; i < n; ++i) {
        float x = static_cast<float>(i) / (n - 1);
        float y = x;
        y = apply_display_contrast(y, contrast);
        y = apply_display_highlights(y, highlights);
        y = apply_display_shadows(y, shadows);
        lut[static_cast<size_t>(i)] = clamp01(y);
    }
    for (int i = 1; i < n; ++i) {
        if (lut[static_cast<size_t>(i)] < lut[static_cast<size_t>(i - 1)]) {
            lut[static_cast<size_t>(i)] = lut[static_cast<size_t>(i - 1)];
        }
    }
    return lut;
}

float sample_tone_lut(const std::vector<float>& lut, float x) {
    int n = static_cast<int>(lut.size());
    float idx = clamp01(x) * (n - 1);
    int i0 = static_cast<int>(idx);
    int i1 = std::min(i0 + 1, n - 1);
    float f = idx - i0;
    return lut[static_cast<size_t>(i0)] * (1.0f - f) + lut[static_cast<size_t>(i1)] * f;
}

float srgb_gamma(float linear) {
    if (linear <= 0.0031308f) {
        return 12.92f * linear;
    }
    return 1.055f * std::pow(linear, 1.0f / 2.4f) - 0.055f;
}

float apply_highlight_shoulder(float y) {
    if (y > 0.78f) {
        float t = (y - 0.78f) / 0.22f;
        y -= t * t * 0.05f;
    }
    return y;
}

float apply_contrast(float y, float x, float contrast) {
    if (std::fabs(contrast) < 1.0f) return y;
    float exponent = kGammaExp - (contrast / 100.0f) * 0.10f;
    exponent = clamp(exponent, 0.25f, 0.75f);
    float contrastCurve = std::pow(x, exponent);
    float blend = std::min(1.0f, std::fabs(contrast) / 100.0f);
    return y * (1.0f - blend) + contrastCurve * blend;
}

float apply_highlights(float y, float x, float highlights) {
    if (std::fabs(highlights) < 1.0f) return y;
    float weight = smoothstep(0.35f, 0.75f, x);
    float shift = highlights / 100.0f * 0.25f;
    return y + shift * weight;
}

float apply_shadows(float y, float x, float shadows) {
    if (std::fabs(shadows) < 1.0f) return y;
    float weight = 1.0f - smoothstep(0.2f, 0.55f, x);
    float shift = shadows / 100.0f * 0.18f;
    return y + shift * weight;
}

std::string lower_copy(const std::string& in) {
    std::string out = in;
    std::transform(out.begin(), out.end(), out.begin(), [](unsigned char c) {
        return static_cast<char>(std::tolower(c));
    });
    return out;
}

float log2_safe(float x) {
    return static_cast<float>(std::log(x) / std::log(2.0f));
}

float shutter_to_seconds(const std::string& s) {
    if (s.empty()) return 0.008f;
    std::string trimmed = s;
    trimmed.erase(0, trimmed.find_first_not_of(" \t\n\r"));
    trimmed.erase(trimmed.find_last_not_of(" \t\n\r") + 1);
    size_t slash = trimmed.find('/');
    try {
        if (slash != std::string::npos) {
            float num = std::stof(trimmed.substr(0, slash));
            float den = std::stof(trimmed.substr(slash + 1));
            return den != 0.0f ? num / den : 0.008f;
        }
        return std::stof(trimmed);
    } catch (...) {
        return 0.008f;
    }
}

float compute_ev(float aperture, float shutterS, int iso) {
    if (shutterS <= 0.0f) shutterS = 1e-6f;
    return log2_safe((aperture * aperture) / shutterS) - log2_safe(iso / 100.0f);
}

float sensor_noise_advantage(const std::string& sensorType) {
    std::string s = lower_copy(sensorType);
    if (s == "medium_format") return 4.0f;
    if (s == "full_frame") return 3.0f;
    if (s == "apsc") return 2.0f;
    if (s == "micro_four_thirds") return 1.5f;
    if (s == "mobile") return 0.0f;
    return 2.0f;
}

bool is_low_light_like(const std::string& lighting) {
    return lighting == "very_low_light"
            || lighting == "low_light"
            || lighting == "indoor"
            || lighting == "artificial"
            || lighting == "mixed"
            || lighting == "cloudy"
            || lighting == "blue_hour"
            || lighting == "golden_hour";
}

bool is_mostly_static(const std::string& motion) {
    return motion == "static" || motion == "slow";
}

int quantize_iso(int iso) {
    int best = kDefaultValidIsos[0];
    int bestDist = std::abs(iso - best);
    for (int v : kDefaultValidIsos) {
        int d = std::abs(iso - v);
        if (d < bestDist) {
            best = v;
            bestDist = d;
        }
    }
    return best;
}

int quantize_shutter_index(float shutterS) {
    if (shutterS <= 0.0f) shutterS = 1e-6f;
    float logTarget = log2_safe(shutterS);
    int bestIdx = 0;
    float bestDist = std::fabs(logTarget - log2_safe(std::max(1e-12f, shutter_to_seconds(kDefaultValidShutters[0]))));
    for (int i = 1; i < static_cast<int>(std::size(kDefaultValidShutters)); ++i) {
        float candidateS = shutter_to_seconds(kDefaultValidShutters[i]);
        if (candidateS <= 0.0f) continue;
        float dist = std::fabs(logTarget - log2_safe(candidateS));
        if (dist < bestDist) {
            bestIdx = i;
            bestDist = dist;
        }
    }
    return bestIdx;
}

int choose_iso(int refIso, int baseIso, int maxUsableIso, float noise,
               const std::string& lighting, const std::string& sensorType) {
    float effectiveStops = sensor_noise_advantage(sensorType) / 2.0f;
    float target = refIso / std::pow(2.0f, effectiveStops);
    target = std::max(static_cast<float>(baseIso), target);
    if (noise > 0.4f) {
        target = std::max(static_cast<float>(baseIso), target / 2.0f);
    }
    if (lighting == "very_low_light" || lighting == "low_light" || lighting == "blue_hour") {
        target = std::max(target, 400.0f);
    } else if (lighting == "indoor" || lighting == "artificial"
            || lighting == "mixed" || lighting == "cloudy"
            || lighting == "golden_hour") {
        target = std::max(target, 200.0f);
    } else if (lighting == "bright" || lighting == "very_bright") {
        target = std::min(target, 100.0f);
    }
    target = clamp(target, static_cast<float>(baseIso), static_cast<float>(maxUsableIso));
    return static_cast<int>(target);
}

int compute_iso_for_target_ev(float fixedAperture, int baseIso, int maxUsableIso,
                              float refEv, float targetShutterS) {
    try {
        float iso = 100.0f * std::pow(2.0f,
                log2_safe((fixedAperture * fixedAperture) / targetShutterS) - refEv);
        return static_cast<int>(clamp(iso, static_cast<float>(baseIso), static_cast<float>(maxUsableIso)));
    } catch (...) {
        return baseIso;
    }
}

int adjust_iso_for_bright(float fixedAperture, int baseIso, int maxUsableIso,
                          float refEv, float minShutter) {
    try {
        float log2Iso100 = log2_safe((fixedAperture * fixedAperture) / minShutter) - refEv;
        float iso = 100.0f * std::pow(2.0f, log2Iso100);
        return static_cast<int>(clamp(iso, static_cast<float>(baseIso), static_cast<float>(maxUsableIso)));
    } catch (...) {
        return baseIso;
    }
}

int adjust_iso_for_dark(float fixedAperture, int baseIso, int maxUsableIso,
                        float refEv, float maxShutter) {
    try {
        float log2Iso100 = log2_safe((fixedAperture * fixedAperture) / maxShutter) - refEv;
        float iso = 100.0f * std::pow(2.0f, log2Iso100);
        return static_cast<int>(clamp(iso, static_cast<float>(baseIso), static_cast<float>(maxUsableIso)));
    } catch (...) {
        return maxUsableIso;
    }
}

float motion_compensate(float shutterS, const std::string& motion) {
    float limit = 0.0f;
    if (motion == "very_fast") limit = 1.0f / 1000.0f;
    else if (motion == "fast") limit = 1.0f / 500.0f;
    else if (motion == "normal") limit = 1.0f / 250.0f;
    else if (motion == "slow") limit = 1.0f / 60.0f;
    else if (motion == "chaotic") limit = 1.0f / 500.0f;
    if (limit > 0.0f && shutterS > limit) {
        return limit;
    }
    return shutterS;
}

std::pair<int, int> map_white_balance(int kelvin) {
    const int presets[] = {2700, 4000, 5500, 6500};
    const int presetModes[] = {kWbIncandescent, kWbFluorescent, kWbDaylight, kWbCloudy};
    int closestMode = kWbAuto;
    int closestDist = std::numeric_limits<int>::max();
    for (int i = 0; i < 4; ++i) {
        int dist = std::abs(kelvin - presets[i]);
        if (dist < closestDist) {
            closestDist = dist;
            closestMode = presetModes[i];
        }
    }
    if (closestDist > 500) {
        int clampedKelvin = std::max(2300, std::min(10000, kelvin));
        return {kWbCustom, clampedKelvin};
    }
    return {closestMode, -1};
}

int choose_metering_mode(const std::string& lighting) {
    if (lighting == "backlit") return kMeteringSpot;
    if (lighting == "mixed" || lighting == "artificial") return kMeteringCenterWeighted;
    return kMeteringMatrix;
}

}  // namespace

std::vector<int32_t> generate_lut_pixels(float contrast, float highlights,
                                         float shadows, float saturation,
                                         float highlightWarmth, float shadowTint) {
    auto tone = dampen_extreme_tone_spread(contrast, highlights, shadows);
    contrast = tone[0];
    highlights = tone[1];
    shadows = tone[2];

    const int n = MIMIC_LUT_SIZE;
    const int width = n * n;
    const int height = n;
    std::vector<float> toneLut = build_display_curve(n, contrast, highlights, shadows);
    bool applySaturation = std::fabs(saturation) > 1.0f;
    float satFactor = applySaturation ? clamp(1.0f + saturation / 100.0f, 0.5f, 1.5f) : 1.0f;
    float warmthStr = highlightWarmth / 100.0f * 0.08f;
    float tintStr = shadowTint / 100.0f * 0.08f;
    bool applyColorShift = std::fabs(highlightWarmth) > 1.0f || std::fabs(shadowTint) > 1.0f;

    std::vector<int32_t> pixels(static_cast<size_t>(width * height), 0);
    for (int gy = 0; gy < n; ++gy) {
        float gBase = toneLut[static_cast<size_t>(gy)];
        for (int bSlice = 0; bSlice < n; ++bSlice) {
            float bBase = toneLut[static_cast<size_t>(bSlice)];
            int xBase = bSlice * n;
            for (int rx = 0; rx < n; ++rx) {
                float rBase = toneLut[static_cast<size_t>(rx)];
                float rVal = rBase;
                float gVal = gBase;
                float bVal = bBase;
                if (applySaturation) {
                    float lum = 0.2126f * rBase + 0.7152f * gBase + 0.0722f * bBase;
                    rVal = lum + (rBase - lum) * satFactor;
                    gVal = lum + (gBase - lum) * satFactor;
                    bVal = lum + (bBase - lum) * satFactor;
                }
                if (applyColorShift) {
                    float lum = 0.2126f * rVal + 0.7152f * gVal + 0.0722f * bVal;
                    if (std::fabs(highlightWarmth) > 1.0f) {
                        float hWeight = smoothstep(0.4f, 0.8f, lum);
                        rVal += warmthStr * hWeight;
                        bVal -= warmthStr * hWeight;
                    }
                    if (std::fabs(shadowTint) > 1.0f) {
                        float sWeight = 1.0f - smoothstep(0.2f, 0.5f, lum);
                        rVal += tintStr * sWeight;
                        bVal -= tintStr * sWeight;
                    }
                }
                rVal = clamp01(rVal);
                gVal = clamp01(gVal);
                bVal = clamp01(bVal);
                pixels[static_cast<size_t>(gy * width + xBase + rx)] =
                        pack_argb(255, to_byte(rVal), to_byte(gVal), to_byte(bVal));
            }
        }
    }
    return pixels;
}

std::vector<int32_t> identity_lut_pixels() {
    const int n = MIMIC_LUT_SIZE;
    const int width = n * n;
    const int height = n;
    std::vector<int32_t> pixels(static_cast<size_t>(width * height), 0);
    for (int gy = 0; gy < n; ++gy) {
        int gByte = static_cast<int>(std::lround(gy * 255.0f / (n - 1)));
        for (int bSlice = 0; bSlice < n; ++bSlice) {
            int bByte = static_cast<int>(std::lround(bSlice * 255.0f / (n - 1)));
            int xBase = bSlice * n;
            for (int rx = 0; rx < n; ++rx) {
                int rByte = static_cast<int>(std::lround(rx * 255.0f / (n - 1)));
                pixels[static_cast<size_t>(gy * width + xBase + rx)] =
                        pack_argb(255, rByte, gByte, bByte);
            }
        }
    }
    return pixels;
}

std::vector<int32_t> compose_lut_pixels(const int32_t* basePixels, int width, int height,
                                        float contrast, float highlights,
                                        float shadows, float saturation,
                                        float highlightWarmth, float shadowTint) {
    if (basePixels == nullptr || width != MIMIC_LUT_SIZE * MIMIC_LUT_SIZE || height != MIMIC_LUT_SIZE) {
        return {};
    }
    auto tone = dampen_extreme_tone_spread(contrast, highlights, shadows);
    contrast = tone[0];
    highlights = tone[1];
    shadows = tone[2];
    std::vector<float> toneLut = build_display_curve(MIMIC_LUT_SIZE, contrast, highlights, shadows);
    bool applySat = std::fabs(saturation) > 1.0f;
    float satFactor = applySat ? clamp(1.0f + saturation / 100.0f, 0.5f, 1.5f) : 1.0f;
    bool applyShift = std::fabs(highlightWarmth) > 1.0f || std::fabs(shadowTint) > 1.0f;
    float warmthStr = highlightWarmth / 100.0f * 0.08f;
    float tintStr = shadowTint / 100.0f * 0.08f;

    std::vector<int32_t> out(static_cast<size_t>(width * height), 0);
    for (int i = 0; i < width * height; ++i) {
        int32_t px = basePixels[i];
        int a = alpha_of(px);
        float r = sample_tone_lut(toneLut, channel_r(px));
        float g = sample_tone_lut(toneLut, channel_g(px));
        float b = sample_tone_lut(toneLut, channel_b(px));
        if (applySat) {
            float lum = 0.2126f * r + 0.7152f * g + 0.0722f * b;
            r = lum + (r - lum) * satFactor;
            g = lum + (g - lum) * satFactor;
            b = lum + (b - lum) * satFactor;
        }
        if (applyShift) {
            float lum = 0.2126f * r + 0.7152f * g + 0.0722f * b;
            if (std::fabs(highlightWarmth) > 1.0f) {
                float weight = smoothstep(0.4f, 0.8f, lum);
                r += warmthStr * weight;
                b -= warmthStr * weight;
            }
            if (std::fabs(shadowTint) > 1.0f) {
                float weight = 1.0f - smoothstep(0.2f, 0.5f, lum);
                r += tintStr * weight;
                b -= tintStr * weight;
            }
        }
        out[static_cast<size_t>(i)] = pack_argb(a, to_byte(clamp01(r)), to_byte(clamp01(g)), to_byte(clamp01(b)));
    }
    return out;
}

std::vector<int32_t> apply_lut_pixels(const int32_t* imagePixels, int width, int height,
                                      const int32_t* lutPixels, int lutWidth, int lutHeight) {
    if (imagePixels == nullptr || lutPixels == nullptr || lutWidth != MIMIC_LUT_SIZE * MIMIC_LUT_SIZE
            || lutHeight != MIMIC_LUT_SIZE || width <= 0 || height <= 0) {
        return {};
    }
    const int n = MIMIC_LUT_SIZE;
    const float sizeM1 = n - 1.0f;
    std::vector<int32_t> out(static_cast<size_t>(width * height), 0);
    for (int i = 0; i < width * height; ++i) {
        int32_t px = imagePixels[i];
        int a = alpha_of(px);
        float r = channel_r(px);
        float g = channel_g(px);
        float b = channel_b(px);
        float blueIndex = b * sizeM1;
        int bFloor = static_cast<int>(blueIndex);
        int bCeil = std::min(bFloor + 1, n - 1);
        float bFrac = blueIndex - bFloor;
        int rx = std::min(static_cast<int>(std::lround(r * sizeM1)), n - 1);
        int gy = std::min(static_cast<int>(std::lround(g * sizeM1)), n - 1);
        int idx0 = gy * lutWidth + bFloor * n + rx;
        int idx1 = gy * lutWidth + bCeil * n + rx;
        int32_t s0 = lutPixels[idx0];
        int32_t s1 = lutPixels[idx1];
        float r0 = channel_r(s0), g0 = channel_g(s0), b0 = channel_b(s0);
        float r1 = channel_r(s1), g1 = channel_g(s1), b1 = channel_b(s1);
        int outR = to_byte(r0 + (r1 - r0) * bFrac);
        int outG = to_byte(g0 + (g1 - g0) * bFrac);
        int outB = to_byte(b0 + (b1 - b0) * bFrac);
        out[static_cast<size_t>(i)] = pack_argb(a, outR, outG, outB);
    }
    return out;
}

std::array<float, 6> extract_tone_params(const int32_t* pixels, int width, int height) {
    if (pixels == nullptr || width <= 0 || height <= 0) {
        return {0, 0, 0, 0, 0, 0};
    }
    int count = width * height;
    std::vector<float> luma(static_cast<size_t>(count), 0.0f);
    std::vector<float> sat(static_cast<size_t>(count), 0.0f);
    std::vector<float> hue(static_cast<size_t>(count), 0.0f);
    for (int i = 0; i < count; ++i) {
        float r = channel_r(pixels[i]);
        float g = channel_g(pixels[i]);
        float b = channel_b(pixels[i]);
        float L = 0.2126f * r + 0.7152f * g + 0.0722f * b;
        luma[static_cast<size_t>(i)] = L;
        float cmax = std::max(r, std::max(g, b));
        float cmin = std::min(r, std::min(g, b));
        float delta = cmax - cmin;
        float midL = (cmax + cmin) * 0.5f;
        float denom = 1.0f - std::fabs(2.0f * midL - 1.0f);
        sat[static_cast<size_t>(i)] = denom > 1e-6f ? std::min(delta / denom, 1.0f) : 0.0f;
        float h = 0.0f;
        if (delta < 1e-7f) {
            h = 0.0f;
        } else if (cmax == r) {
            h = 60.0f * std::fmod(((g - b) / delta), 6.0f);
        } else if (cmax == g) {
            h = 60.0f * (((b - r) / delta) + 2.0f);
        } else {
            h = 60.0f * (((r - g) / delta) + 4.0f);
        }
        hue[static_cast<size_t>(i)] = wrap_degrees(h);
    }

    float lumaSum = 0.0f;
    for (float v : luma) lumaSum += v;
    float lumaMean = lumaSum / count;
    float lumaVarSum = 0.0f;
    for (float v : luma) {
        float d = v - lumaMean;
        lumaVarSum += d * d;
    }
    float lumaStd = std::sqrt(lumaVarSum / count);
    float contrast = clamp100((lumaStd - kNeutralContrastStd) / kNeutralContrastStd * 100.0f);

    float hiSum = 0.0f;
    int hiCount = 0;
    float loSum = 0.0f;
    int loCount = 0;
    float satSum = 0.0f;
    for (int i = 0; i < count; ++i) {
        float v = luma[static_cast<size_t>(i)];
        if (v >= 0.5f) {
            hiSum += v;
            hiCount++;
        } else {
            loSum += v;
            loCount++;
        }
        satSum += sat[static_cast<size_t>(i)];
    }
    float hiMean = hiCount > 0 ? hiSum / hiCount : 0.5f;
    float loMean = loCount > 0 ? loSum / loCount : 0.5f;
    float satMean = satSum / count;
    float highlights = clamp100((hiMean - kNeutralHighlightsMean) / kNeutralHighlightsMean * 100.0f);
    float shadows = clamp100((loMean - kNeutralShadowsMean) / kNeutralShadowsMean * 100.0f);
    float saturation = clamp100((satMean - kNeutralSaturation) / kNeutralSaturation * 100.0f);

    float highlightWarmth = 0.0f;
    {
        double sinSum = 0.0, cosSum = 0.0;
        int n = 0;
        for (int i = 0; i < count; ++i) {
            if (luma[static_cast<size_t>(i)] >= 0.6f && sat[static_cast<size_t>(i)] > 0.05f) {
                double rad = hue[static_cast<size_t>(i)] * M_PI / 180.0;
                sinSum += std::sin(rad);
                cosSum += std::cos(rad);
                n++;
            }
        }
        if (n > 50) {
            double meanHue = std::atan2(sinSum / n, cosSum / n) * 180.0 / M_PI;
            float hueDiff = signed_hue_diff(static_cast<float>(wrap_degrees(static_cast<float>(meanHue))), kNeutralWarmthDeg);
            highlightWarmth = clamp100(-hueDiff * 1.5f);
        }
    }

    float shadowTint = 0.0f;
    {
        double sinSum = 0.0, cosSum = 0.0;
        int n = 0;
        for (int i = 0; i < count; ++i) {
            if (luma[static_cast<size_t>(i)] < 0.4f && sat[static_cast<size_t>(i)] > 0.05f) {
                double rad = hue[static_cast<size_t>(i)] * M_PI / 180.0;
                sinSum += std::sin(rad);
                cosSum += std::cos(rad);
                n++;
            }
        }
        if (n > 50) {
            double meanHue = std::atan2(sinSum / n, cosSum / n) * 180.0 / M_PI;
            float hueDiff = signed_hue_diff(static_cast<float>(wrap_degrees(static_cast<float>(meanHue))), kNeutralShadowHueDeg);
            shadowTint = clamp100(hueDiff * 1.5f);
        }
    }

    return {round1(contrast), round1(highlights), round1(shadows), round1(saturation),
            round1(highlightWarmth), round1(shadowTint)};
}

std::vector<float> build_tone_curve(float contrast, float highlights,
                                    float shadows, int maxPoints) {
    int numPoints = std::min(kDefaultCurvePoints, maxPoints);
    if (numPoints < 2) numPoints = 2;
    std::vector<float> curve(static_cast<size_t>(numPoints * 2), 0.0f);
    for (int i = 0; i < numPoints; ++i) {
        float x = static_cast<float>(i) / (numPoints - 1);
        float y = srgb_gamma(x);
        y = apply_highlight_shoulder(y);
        y = apply_contrast(y, x, contrast);
        y = apply_highlights(y, x, highlights);
        y = apply_shadows(y, x, shadows);
        y = clamp01(y);
        curve[static_cast<size_t>(i * 2)] = x;
        curve[static_cast<size_t>(i * 2 + 1)] = y;
    }
    curve[0] = 0.0f;
    curve[1] = std::max(0.0f, curve[1]);
    curve[static_cast<size_t>((numPoints - 1) * 2)] = 1.0f;
    curve[static_cast<size_t>((numPoints - 1) * 2 + 1)] = std::min(1.0f, curve[static_cast<size_t>((numPoints - 1) * 2 + 1)]);
    for (int i = 1; i < numPoints; ++i) {
        if (curve[static_cast<size_t>(i * 2 + 1)] < curve[static_cast<size_t>((i - 1) * 2 + 1)]) {
            curve[static_cast<size_t>(i * 2 + 1)] = curve[static_cast<size_t>((i - 1) * 2 + 1)];
        }
    }
    return curve;
}

std::vector<float> identity_tone_curve(int maxPoints) {
    int numPoints = std::min(kDefaultCurvePoints, maxPoints);
    if (numPoints < 2) numPoints = 2;
    std::vector<float> curve(static_cast<size_t>(numPoints * 2), 0.0f);
    for (int i = 0; i < numPoints; ++i) {
        float x = static_cast<float>(i) / (numPoints - 1);
        curve[static_cast<size_t>(i * 2)] = x;
        curve[static_cast<size_t>(i * 2 + 1)] = apply_highlight_shoulder(srgb_gamma(x));
    }
    return curve;
}

std::array<float, 9> build_saturation_matrix(float saturation) {
    float s = clamp(1.0f + saturation / 100.0f, 0.0f, 2.0f);
    constexpr float lr = 0.2126f;
    constexpr float lg = 0.7152f;
    constexpr float lb = 0.0722f;
    float sr = (1.0f - s) * lr;
    float sg = (1.0f - s) * lg;
    float sb = (1.0f - s) * lb;
    return {sr + s, sg, sb,
            sr, sg + s, sb,
            sr, sg, sb + s};
}

std::vector<SearchPair> master_match_topk(const float* query, int dim,
                                          const float* flatEmbeddings, int count,
                                          const int* candidates, int candidateCount,
                                          int topK) {
    std::vector<SearchPair> results;
    if (query == nullptr || flatEmbeddings == nullptr || dim <= 0 || count <= 0 || topK <= 0) {
        return results;
    }
    std::vector<SearchPair> candidatesOut;
    int total = (candidates != nullptr && candidateCount > 0) ? candidateCount : count;
    candidatesOut.reserve(static_cast<size_t>(total));
    auto addCandidate = [&](int idx) {
        if (idx < 0 || idx >= count) return;
        const float* row = flatEmbeddings + static_cast<size_t>(idx) * dim;
        float dot = 0.0f;
        float normA = 0.0f;
        float normB = 0.0f;
        for (int j = 0; j < dim; ++j) {
            float a = query[j];
            float b = row[j];
            dot += a * b;
            normA += a * a;
            normB += b * b;
        }
        float denom = std::sqrt(normA) * std::sqrt(normB);
        float sim = denom > 0.0f ? dot / denom : 0.0f;
        candidatesOut.push_back({idx, sim});
    };
    if (candidates != nullptr && candidateCount > 0) {
        for (int i = 0; i < candidateCount; ++i) addCandidate(candidates[i]);
    } else {
        for (int i = 0; i < count; ++i) addCandidate(i);
    }
    std::sort(candidatesOut.begin(), candidatesOut.end(), [](const SearchPair& a, const SearchPair& b) {
        return a.similarity > b.similarity;
    });
    if (static_cast<int>(candidatesOut.size()) > topK) {
        candidatesOut.resize(static_cast<size_t>(topK));
    }
    return candidatesOut;
}

ExposureMappingResult map_exposure(float fixedAperture, int baseIso, int maxUsableIso,
                                   float minShutterSeconds, float maxShutterSeconds,
                                   float refAperture, const std::string& refShutterSpeed,
                                   int refIso, int refWbKelvin,
                                   const std::string& sensorType,
                                   float currentNoise,
                                   const std::string& currentLighting,
                                   const std::string& currentMotion) {
    std::string lighting = lower_copy(currentLighting);
    std::string motion = lower_copy(currentMotion);

    float refShutterS = shutter_to_seconds(refShutterSpeed);
    float refEv = compute_ev(refAperture, refShutterS, refIso);

    int targetIso = choose_iso(refIso, baseIso, maxUsableIso, currentNoise, lighting, sensorType);
    float evPlusIso = refEv + log2_safe(targetIso / 100.0f);
    float targetShutterS = (fixedAperture * fixedAperture) / std::pow(2.0f, evPlusIso);
    targetShutterS = clamp(targetShutterS, minShutterSeconds, maxShutterSeconds);

    if (targetShutterS <= minShutterSeconds) {
        targetIso = adjust_iso_for_bright(fixedAperture, baseIso, maxUsableIso, refEv, targetShutterS);
    } else if (targetShutterS >= maxShutterSeconds) {
        targetIso = adjust_iso_for_dark(fixedAperture, baseIso, maxUsableIso, refEv, targetShutterS);
    }

    float preMotionShutter = targetShutterS;
    targetShutterS = motion_compensate(targetShutterS, motion);
    if (targetShutterS < preMotionShutter) {
        try {
            float neededIso = 100.0f * std::pow(2.0f,
                    log2_safe((fixedAperture * fixedAperture) / targetShutterS) - refEv);
            targetIso = static_cast<int>(clamp(neededIso, static_cast<float>(baseIso), static_cast<float>(maxUsableIso)));
        } catch (...) {
        }
    }

    if (is_low_light_like(lighting) && is_mostly_static(motion)) {
        const float handheldPreferredMin = 1.0f / 60.0f;
        if (targetShutterS < handheldPreferredMin) {
            targetShutterS = handheldPreferredMin;
            targetIso = compute_iso_for_target_ev(fixedAperture, baseIso, maxUsableIso, refEv, targetShutterS);
        }
    }

    int qIso = quantize_iso(targetIso);
    int shutterIndex = quantize_shutter_index(targetShutterS);
    float actualEv = compute_ev(fixedAperture, shutter_to_seconds(kDefaultValidShutters[shutterIndex]), qIso);
    float evOffset = refEv - actualEv;
    evOffset = clamp(std::round(evOffset * 2.0f) / 2.0f, -3.0f, 3.0f);
    if (is_low_light_like(lighting) && evOffset < 0.0f) {
        evOffset = 0.0f;
    }

    int wbModeCode = kWbAuto;
    int wbKelvin = -1;
    if (refWbKelvin > 0) {
        auto wb = map_white_balance(refWbKelvin);
        wbModeCode = wb.first;
        wbKelvin = wb.second;
    }

    return {qIso, shutterIndex, evOffset, wbModeCode, wbKelvin, choose_metering_mode(lighting)};
}

}  // namespace camera_native
