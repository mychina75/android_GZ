// Native CV proxies — see cv_stats.h. Logic mirrors the Java
// BackgroundBokehStats and LensBlockedCornerStats classes.
#include "cv_stats.h"

#include <algorithm>
#include <cmath>
#include <vector>

namespace camera_native {

namespace {

inline float clamp01(float v) {
    if (v < 0.0f) return 0.0f;
    if (v > 1.0f) return 1.0f;
    return v;
}

inline int rOf(int32_t c) { return (c >> 16) & 0xFF; }
inline int gOf(int32_t c) { return (c >> 8) & 0xFF; }
inline int bOf(int32_t c) { return c & 0xFF; }

}  // namespace

// --- Bokeh: variance-of-Laplacian split by subject bbox --------------------
BokehResult bokeh_stats(const int32_t* argb, int width, int height,
                        float bx, float by, float bw, float bh) {
    const int GRID = 48;
    const float EPS = 1e-3f;
    if (argb == nullptr || width < GRID || height < GRID) {
        return {1.0f, 0.0f};
    }
    std::vector<float> luma(GRID * GRID, 0.0f);
    std::vector<char> isSubject(GRID * GRID, 0);

    float sx0 = clamp01(bx), sy0 = clamp01(by);
    float sx1 = clamp01(bx + bw), sy1 = clamp01(by + bh);
    for (int gy = 0; gy < GRID; ++gy) {
        int py = static_cast<int>(((gy + 0.5f) / GRID) * height);
        if (py >= height) py = height - 1;
        for (int gx = 0; gx < GRID; ++gx) {
            int px = static_cast<int>(((gx + 0.5f) / GRID) * width);
            if (px >= width) px = width - 1;
            int32_t c = argb[py * width + px];
            float y = (0.299f * rOf(c) + 0.587f * gOf(c) + 0.114f * bOf(c)) / 255.0f;
            int idx = gy * GRID + gx;
            luma[idx] = y;
            float fx = (gx + 0.5f) / GRID;
            float fy = (gy + 0.5f) / GRID;
            isSubject[idx] = (fx >= sx0 && fx <= sx1 && fy >= sy0 && fy <= sy1)
                             && (sx1 > sx0) && (sy1 > sy0) ? 1 : 0;
        }
    }

    double subjSum = 0, subjSumSq = 0;
    int subjN = 0;
    double bgSum = 0, bgSumSq = 0;
    int bgN = 0;
    for (int gy = 1; gy < GRID - 1; ++gy) {
        for (int gx = 1; gx < GRID - 1; ++gx) {
            int idx = gy * GRID + gx;
            float lap = 4.0f * luma[idx]
                        - luma[idx - 1] - luma[idx + 1]
                        - luma[idx - GRID] - luma[idx + GRID];
            if (isSubject[idx]) {
                subjSum += lap; subjSumSq += static_cast<double>(lap) * lap; subjN++;
            } else {
                bgSum += lap; bgSumSq += static_cast<double>(lap) * lap; bgN++;
            }
        }
    }
    auto variance = [](double sum, double sumSq, int n) -> float {
        if (n <= 0) return 0.0f;
        double mean = sum / n;
        double var = sumSq / n - mean * mean;
        return static_cast<float>(std::max(0.0, var));
    };
    float subjVar = variance(subjSum, subjSumSq, subjN);
    float bgVar = variance(bgSum, bgSumSq, bgN);
    if (subjN == 0 || bgN == 0) {
        return {1.0f, 0.0f};
    }
    float ratio = bgVar / std::max(subjVar, EPS);
    float strength = clamp01(1.0f - ratio);
    return {ratio, strength};
}

// --- Lens corner obstruction heuristic -------------------------------------
namespace {

constexpr int PATCH = 32;
constexpr float DARK_LUMA = 0.22f;
constexpr float LOW_GRAD = 12.0f;
constexpr int MIN_BLOCKED_PATCHES = 2;
constexpr int EDGE_PATCH_COUNT = 8;

struct PatchMetrics {
    float meanLuma;
    float stdLuma;
    float gradMag;
    float darkRatio;
    float warmRatio;
};

PatchMetrics computePatchMetrics(const int32_t* argb, int width,
                                 int left, int top) {
    static thread_local float lumaBuf[PATCH * PATCH];
    float sum = 0.0f, sumSq = 0.0f;
    int darkCount = 0, warmCount = 0, sampleCount = 0;
    const int step = 2;
    for (int y = 0; y < PATCH; y += step) {
        for (int x = 0; x < PATCH; x += step) {
            int i = y * PATCH + x;
            int32_t px = argb[(top + y) * width + (left + x)];
            int r8 = rOf(px), g8 = gOf(px), b8 = bOf(px);
            float lumaVal = (0.299f * r8 + 0.587f * g8 + 0.114f * b8) / 255.0f;
            lumaBuf[i] = lumaVal;
            sum += lumaVal;
            sumSq += lumaVal * lumaVal;
            sampleCount++;
            if (lumaVal < DARK_LUMA) darkCount++;
            if (r8 > 55 && r8 > g8 * 1.06f && r8 > b8 * 1.12f && g8 > b8 * 0.75f) {
                warmCount++;
            }
        }
    }
    float mean = sum / sampleCount;
    float var = std::max(0.0f, sumSq / sampleCount - mean * mean);
    float std_ = std::sqrt(var);
    float darkRatio = static_cast<float>(darkCount) / sampleCount;
    float warmRatio = static_cast<float>(warmCount) / sampleCount;

    float gradSum = 0.0f;
    int gradCount = 0;
    for (int y = step; y < PATCH - step; y += step) {
        for (int x = step; x < PATCH - step; x += step) {
            int c = y * PATCH + x;
            float gx = lumaBuf[c + step] - lumaBuf[c - step];
            float gy = lumaBuf[c + step * PATCH] - lumaBuf[c - step * PATCH];
            gradSum += std::fabs(gx) + std::fabs(gy);
            gradCount++;
        }
    }
    float gradMag = (gradSum / std::max(gradCount, 1)) * 255.0f;
    return {mean, std_, gradMag, darkRatio, warmRatio};
}

float scoreFromCount(int blocked, int total, int edgeBlocked) {
    if (blocked < MIN_BLOCKED_PATCHES || edgeBlocked == 0) {
        return 0.0f;
    }
    float ratio = blocked / std::max(1.0f, static_cast<float>(total));
    float edgeBonus = 0.05f * edgeBlocked;
    float p = 0.20f + 0.10f * blocked + 0.65f * ratio + edgeBonus;
    if (p > 0.98f) p = 0.98f;
    return p;
}

}  // namespace

LensResult lens_corner(const int32_t* argb, int width, int height) {
    if (argb == nullptr || width < PATCH * 4 || height < PATCH * 4) {
        return {0.0f, 0};
    }
    int W = width, H = height;
    std::vector<int> lefts, tops;
    auto addRect = [&](int l, int t) { lefts.push_back(l); tops.push_back(t); };
    // 8 edge/corner patches (fixed order for spatial connectivity check).
    addRect(0, 0);
    addRect(W - PATCH, 0);
    addRect(W - PATCH, H - PATCH);
    addRect(0, H - PATCH);
    addRect((W - PATCH) / 2, 0);
    addRect(W - PATCH, (H - PATCH) / 2);
    addRect((W - PATCH) / 2, H - PATCH);
    addRect(0, (H - PATCH) / 2);
    // Sparse interior grid.
    const int gridCols = 5, gridRows = 7;
    for (int row = 1; row < gridRows - 1; ++row) {
        for (int col = 1; col < gridCols - 1; ++col) {
            int x = static_cast<int>(std::lround(col * (W - PATCH) / static_cast<float>(gridCols - 1)));
            int y = static_cast<int>(std::lround(row * (H - PATCH) / static_cast<float>(gridRows - 1)));
            addRect(x, y);
        }
    }

    int n = static_cast<int>(lefts.size());
    std::vector<PatchMetrics> stats(n);
    float globalLumaSum = 0.0f;
    for (int i = 0; i < n; ++i) {
        stats[i] = computePatchMetrics(argb, W, lefts[i], tops[i]);
        globalLumaSum += stats[i].meanLuma;
    }
    float globalMeanLuma = globalLumaSum / n;

    int blockedCount = 0, edgeBlockedCount = 0;
    for (int i = 0; i < n; ++i) {
        const PatchMetrics& s = stats[i];
        float relativeDarkThresh = std::min(DARK_LUMA, globalMeanLuma * 0.7f);
        bool darkSmooth = (s.meanLuma < relativeDarkThresh && s.gradMag < LOW_GRAD)
                          || (s.darkRatio > 0.75f && s.gradMag < (LOW_GRAD + 4.0f)
                              && s.meanLuma < globalMeanLuma * 0.8f);
        bool warmSmooth = s.warmRatio > 0.55f && s.gradMag < 18.0f && s.stdLuma < 0.18f;
        bool isBlocked = darkSmooth || warmSmooth;
        if (isBlocked) {
            blockedCount++;
            if (i < EDGE_PATCH_COUNT) edgeBlockedCount++;
        }
    }
    // Spatial connectivity veto: interior-only blocks with intact edges = false positive.
    if (blockedCount >= MIN_BLOCKED_PATCHES && edgeBlockedCount == 0) {
        blockedCount = 0;
    }
    LensResult res;
    res.probability = scoreFromCount(blockedCount, n, edgeBlockedCount);
    res.numBlockedPatches = blockedCount;
    return res;
}

}  // namespace camera_native
