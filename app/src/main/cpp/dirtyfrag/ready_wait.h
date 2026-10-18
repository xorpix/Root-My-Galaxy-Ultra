#pragma once
#include <stdint.h>

#define DF_READY_LIMIT_MS 5000
#define DF_READY_PROBE_MS 150
#define DF_READY_INTERVAL_MS 250

struct df_ready_ops {
    int64_t (*now_ms)(void *context);
    void (*sleep_ms)(void *context, int64_t millis);
    int (*probe)(void *context, int64_t budget_ms);
};
struct df_ready_result { int64_t elapsed_ms; int ready; };

/** Reparenting gates the trigger; only a completed root command may end this five-second window. */
static inline struct df_ready_result df_wait_ready(const struct df_ready_ops *ops, void *context) {
    int64_t start = ops->now_ms(context), elapsed = 0;
    if (start < 0) {
        ops->sleep_ms(context, DF_READY_LIMIT_MS);
        return (struct df_ready_result){DF_READY_LIMIT_MS, 0};
    }
    while (1) {
        int64_t now = ops->now_ms(context);
        if (now < start + elapsed) {
            ops->sleep_ms(context, DF_READY_LIMIT_MS - elapsed);
            return (struct df_ready_result){DF_READY_LIMIT_MS, 0};
        }
        elapsed = now - start;
        if (elapsed >= DF_READY_LIMIT_MS) break;
        int64_t budget = DF_READY_LIMIT_MS - elapsed;
        if (budget > DF_READY_PROBE_MS) budget = DF_READY_PROBE_MS;
        if (ops->probe(context, budget)) {
            now = ops->now_ms(context);
            return (struct df_ready_result){now >= start ? now - start : elapsed, 1};
        }
        now = ops->now_ms(context);
        if (now < start + elapsed) continue;
        elapsed = now - start;
        int64_t pause = DF_READY_LIMIT_MS - elapsed;
        if (pause <= 0) break;
        if (pause > DF_READY_INTERVAL_MS) pause = DF_READY_INTERVAL_MS;
        ops->sleep_ms(context, pause);
    }
    return (struct df_ready_result){elapsed, 0};
}
