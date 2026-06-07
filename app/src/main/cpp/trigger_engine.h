// Native L0 trigger rule engine (gate / arbitration / hysteresis).
//
// Mirrors recommendation/triggers/trigger_scorer.py and the Java
// TriggerScorer / TriggerArbiter / TriggerHysteresis classes byte-for-byte.
// Rules are compiled into the binary via rules_generated.cpp (no JSON shipped),
// so the scoring logic and thresholds cannot be inspected or tampered with by
// editing an asset.
#ifndef CAMERA_NATIVE_TRIGGER_ENGINE_H
#define CAMERA_NATIVE_TRIGGER_ENGINE_H

#include <string>
#include <unordered_map>
#include <vector>

namespace camera_native {

enum TermType {
    TERM_PROB = 0,
    TERM_INV,
    TERM_EQ,
    TERM_RANGE,
    TERM_GT,
    TERM_LT,
    TERM_LINEAR,
    TERM_GATE,
};

struct Term {
    TermType type;
    float weight;
    std::string key;
    int classId;
    float thr;
    float low;
    float high;
    float dflt;
};

struct Rule {
    std::string name;
    float bias;
    std::vector<Term> terms;
};

struct ArbGroup {
    std::string name;
    std::vector<std::string> members;
    float margin;
};

struct HysteresisCfg {
    int enterFrames;
    int exitFrames;
    float releaseRatio;
};

using Signals = std::unordered_map<std::string, float>;

// --- Compiled rule accessors (defined in rules_generated.cpp) --------------
const std::vector<Rule>& compiled_rules();
const std::vector<std::string>& trigger_names();   // canonical 11, fixed order
const std::vector<ArbGroup>& compiled_arbitration();
const HysteresisCfg& compiled_hysteresis();

// --- Stateless scoring + arbitration ---------------------------------------
// Returns scores in canonical trigger_names() order.
std::vector<float> score(const Signals& signals);

// In-place mutual-exclusion suppression. scores/thresholds are indexed by
// canonical trigger order.
void arbitrate(std::vector<float>& scores, const std::vector<float>& thresholds);

// --- Stateful temporal hysteresis ------------------------------------------
class Hysteresis {
public:
    Hysteresis(int enterFrames, int exitFrames, float releaseRatio);
    // In-place stabilisation; scores/thresholds indexed by canonical order.
    void stabilize(std::vector<float>& scores, const std::vector<float>& thresholds);

private:
    int enterFrames_;
    int exitFrames_;
    float releaseRatio_;
    std::vector<char> active_;
    std::vector<int> enterCount_;
    std::vector<int> exitCount_;
};

}  // namespace camera_native

#endif  // CAMERA_NATIVE_TRIGGER_ENGINE_H
