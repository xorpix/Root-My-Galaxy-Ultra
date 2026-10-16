#include <assert.h>
#include <stdio.h>
#include "ready_wait.h"

struct clock_state { int64_t now, ready_at, cost; int calls, fail_clock; };
static int64_t now(void *opaque) {
    struct clock_state *s = opaque;
    return s->fail_clock ? -1 : s->now;
}
static void sleep_ms(void *opaque, int64_t millis) {
    assert(millis >= 0);
    ((struct clock_state *)opaque)->now += millis;
}
static int probe(void *opaque, int64_t budget) {
    struct clock_state *s = opaque;
    assert(s->now >= DF_READY_MIN_MS && budget > 0 && budget <= DF_READY_PROBE_MS);
    s->calls++;
    s->now += s->cost < budget ? s->cost : budget;
    return s->ready_at >= 0 && s->now >= s->ready_at;
}
int main(void) {
    const struct df_ready_ops ops = {now, sleep_ms, probe};
    struct clock_state immediate = {.ready_at = 0};
    struct df_ready_result r = df_wait_ready(&ops, &immediate);
    assert(r.ready && r.elapsed_ms == 1000 && immediate.calls == 1);
    struct clock_state delayed = {.ready_at = 2100, .cost = 20};
    r = df_wait_ready(&ops, &delayed);
    assert(r.ready && r.elapsed_ms >= 2100 && r.elapsed_ms < 2400);
    struct clock_state denied = {.ready_at = -1};
    r = df_wait_ready(&ops, &denied);
    assert(!r.ready && r.elapsed_ms == 5000 && denied.calls > 1);
    struct clock_state hung = {.ready_at = -1, .cost = 150};
    r = df_wait_ready(&ops, &hung);
    assert(!r.ready && r.elapsed_ms == 5000);
    struct clock_state slow = {.ready_at = 6000, .cost = 150};
    r = df_wait_ready(&ops, &slow);
    assert(!r.ready && r.elapsed_ms == 5000);
    struct clock_state missing_clock = {.ready_at = 0, .fail_clock = 1};
    r = df_wait_ready(&ops, &missing_clock);
    assert(!r.ready && missing_clock.now == 5000 && missing_clock.calls == 0);
    puts("Adaptive native wait: PASS (6 clock/probe cases)");
}
