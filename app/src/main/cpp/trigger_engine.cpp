// Native L0 trigger engine implementation.
//
// Mirrors the Java TriggerScorer.evalTerm / applyGates, TriggerArbiter.apply
// and TriggerHysteresis.stabilize exactly.
#include "trigger_engine.h"

#include <algorithm>
#include <cmath>
#include <limits>

namespace camera_native {

namespace {

inline float clip01(float v) {
    if (v < 0.0f) return 0.0f;
    if (v > 1.0f) return 1.0f;
    return v;
}

inline float sigmoidf(float x) {
    if (x >= 0.0f) {
        return static_cast<float>(1.0 / (1.0 + std::exp(-static_cast<double>(x))));
    }
    double z = std::exp(static_cast<double>(x));
    return static_cast<float>(z / (1.0 + z));
}

inline float getSig(const Signals& sig, const std::string& key, float def) {
    auto it = sig.find(key);
    return it == sig.end() ? def : it->second;
}

// Matches Java TriggerScorer.evalTerm.
float evalTerm(const Term& t, const Signals& sig) {
    switch (t.type) {
        case TERM_GATE: {
            float v = getSig(sig, t.key, t.dflt);
            return v >= t.thr ? 1.0f : 0.0f;
        }
        case TERM_PROB:
            return clip01(getSig(sig, t.key, 0.0f));
        case TERM_INV:
            return clip01(1.0f - getSig(sig, t.key, 0.0f));
        case TERM_EQ: {
            int actual = static_cast<int>(getSig(sig, t.key + "_id", -1.0f));
            float p = getSig(sig, t.key + "_prob", 0.0f);
            return actual == t.classId ? p : 0.0f;
        }
        case TERM_RANGE: {
            float v = getSig(sig, t.key, 0.0f);
            return (v >= t.low && v <= t.high) ? 1.0f : 0.0f;
        }
        case TERM_GT: {
            float v = getSig(sig, t.key, 0.0f);
            if (v >= t.thr) return 1.0f;
            return std::max(0.0f, v) / std::max(t.thr, 1e-6f);
        }
        case TERM_LT: {
            float v = getSig(sig, t.key, 0.0f);
            return v <= t.thr ? 1.0f : 0.0f;
        }
        case TERM_LINEAR: {
            float v = getSig(sig, t.key, 0.0f);
            if (t.high == t.low) return 0.0f;
            return clip01((v - t.low) / (t.high - t.low));
        }
        default:
            return 0.0f;
    }
}

// Matches Java TriggerScorer.applyGates.
float applyGates(float scoreVal, const Rule& r, const Signals& sig) {
    for (const Term& t : r.terms) {
        if (t.type != TERM_GATE) continue;
        float v = getSig(sig, t.key, t.dflt);
        if (v < t.thr) {
            scoreVal *= t.weight;
        }
    }
    return scoreVal;
}

int indexOf(const std::string& name) {
    const auto& names = trigger_names();
    for (size_t i = 0; i < names.size(); ++i) {
        if (names[i] == name) return static_cast<int>(i);
    }
    return -1;
}

}  // namespace

std::vector<float> score(const Signals& signals) {
    const auto& rules = compiled_rules();
    const auto& names = trigger_names();
    std::vector<float> out(names.size(), 0.0f);
    // rules are stored in canonical order by the codegen.
    for (size_t i = 0; i < rules.size() && i < out.size(); ++i) {
        const Rule& r = rules[i];
        float z = r.bias;
        for (const Term& t : r.terms) {
            if (t.type == TERM_GATE) continue;
            z += t.weight * evalTerm(t, signals);
        }
        out[i] = applyGates(sigmoidf(z), r, signals);
    }
    return out;
}

void arbitrate(std::vector<float>& scores, const std::vector<float>& thresholds) {
    const float eps = 1e-3f;
    const float defThr = 0.5f;
    for (const ArbGroup& grp : compiled_arbitration()) {
        std::vector<int> idx;
        std::vector<float> margins;
        std::vector<float> thrs;
        for (const std::string& m : grp.members) {
            int i = indexOf(m);
            if (i < 0 || i >= static_cast<int>(scores.size())) continue;
            float thr = (i < static_cast<int>(thresholds.size())) ? thresholds[i] : defThr;
            float margin = scores[i] - thr;
            if (margin >= 0.0f) {
                idx.push_back(i);
                margins.push_back(margin);
                thrs.push_back(thr);
            }
        }
        if (idx.size() <= 1) continue;
        int winIdx = 0;
        for (size_t i = 1; i < margins.size(); ++i) {
            if (margins[i] > margins[winIdx]) winIdx = static_cast<int>(i);
        }
        float winMargin = margins[winIdx];
        float runnerMargin = -std::numeric_limits<float>::infinity();
        for (size_t i = 0; i < margins.size(); ++i) {
            if (static_cast<int>(i) == winIdx) continue;
            if (margins[i] > runnerMargin) runnerMargin = margins[i];
        }
        if (winMargin - runnerMargin < grp.margin) continue;
        for (size_t i = 0; i < idx.size(); ++i) {
            if (static_cast<int>(i) == winIdx) continue;
            int s = idx[i];
            float capped = std::min(scores[s], thrs[i] - eps);
            scores[s] = capped;
        }
    }
}

Hysteresis::Hysteresis(int enterFrames, int exitFrames, float releaseRatio)
    : enterFrames_(std::max(1, enterFrames)),
      exitFrames_(std::max(1, exitFrames)),
      releaseRatio_(releaseRatio),
      active_(trigger_names().size(), 0),
      enterCount_(trigger_names().size(), 0),
      exitCount_(trigger_names().size(), 0) {}

void Hysteresis::stabilize(std::vector<float>& scores,
                           const std::vector<float>& thresholds) {
    const float eps = 1e-3f;
    for (size_t i = 0; i < scores.size(); ++i) {
        float scoreVal = scores[i];
        float thr = (i < thresholds.size()) ? thresholds[i] : 0.5f;
        bool wasActive = active_[i] != 0;

        bool confirmed;
        if (!wasActive) {
            if (scoreVal >= thr) {
                int c = ++enterCount_[i];
                confirmed = c >= enterFrames_;
            } else {
                enterCount_[i] = 0;
                confirmed = false;
            }
            exitCount_[i] = 0;
        } else {
            if (scoreVal < thr * releaseRatio_) {
                int c = ++exitCount_[i];
                confirmed = c < exitFrames_;
            } else {
                exitCount_[i] = 0;
                confirmed = true;
            }
            enterCount_[i] = 0;
        }

        active_[i] = confirmed ? 1 : 0;
        if (!confirmed && scoreVal >= thr) {
            scores[i] = std::min(scoreVal, thr - eps);
        } else if (confirmed && scoreVal < thr) {
            scores[i] = std::max(scoreVal, thr);
        }
    }
}

}  // namespace camera_native
