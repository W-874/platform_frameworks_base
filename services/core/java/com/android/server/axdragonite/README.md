# Sagit Dragonite ownership and diagnostics

This port follows Axion's `lineage-23.2` [SDK](https://github.com/AxionAOSP/android_axion_sdk/tree/7f12002f4b24fb6f6cfd02b6b73a11f5e118b897), [framework](https://github.com/AxionAOSP/android_frameworks_base/tree/2e6f9ee715d68fe1ba428c9f42417cfa2191458f), and [native framework](https://github.com/AxionAOSP/android_frameworks_native/tree/e3a16ad1587e686fa9adb1d1299eb95d4721300b) references.

## Kernel manager

The device installs `ax_kernel_manager.xml` under `/system_ext/etc`. Its CPU
entries locate placement policies; they are not writable node declarations.
Sagit does not search multiple partitions for arbitrary writable paths. The
existing Global CPU, GPU and governor profile settings submit fixed named
requests through `IPowerExt`; only MSM8998 libperfmgr modifies shared nodes.
There is no new client-selectable raw sysfs write API, and the settings do
not override thermal or sustained-mode limits.

`adb shell dumpsys activity axdragonite` requires `android.permission.DUMP`.
It includes requested/supported/active HAL modes and a read-only snapshot of
kernel CPU policy membership, governor and scaling frequencies, CPU ticks,
and memory PSI. CPU metrics discover the live cpufreq policies (Axion's
CPU fallback), so placement-target XML never hides non-placement clusters or
creates a second hardware inventory. `activeTicks` and `totalTicks` are
cumulative, not instantaneous percentages. Cluster sums cover CPUs present
in that `/proc/stat` sample; use matching online CPU membership when comparing
samples. Unavailable attributes are identified separately from zero. Memory
PSI reuses Android's existing parser and is never fed back into policy. There
is no periodic sampler and no unprivileged Binder telemetry or controls API.

Raw KGSL statistics from upstream `getAxKernelMetrics` are not exposed here:
Sagit keeps the GPU in the existing HAL/graphics privilege boundary and does
not grant SystemServer generic KGSL sysfs access. HAL GPU votes remain in
the diagnostic output; they are requests, not measured clocks.

## Foreground games and scene isolation

The focused top-resumed activity identifies the only eligible process; a UID
in `PROCESS_STATE_TOP` is not enough on its own. GameManagerService checks
the package's UID, game category and Performance mode for the current user.
The scene worker rechecks the app-thread Binder and process start time, and
observes WM focus, attach/death, sleep, UID state, mode and user changes.
This replaces the Axion `SCENE_GAME_1` toggle with a process-scoped session,
without a package allowlist or writable named-affinity proc nodes.

While eligible, named render threads use the big cluster and named workers
the little cluster, intersected with their pre-vote affinity. The worker
rediscovers names and recycled threads once a second. A UI placement lease
wins over the game; the session can resume placement without another game
report. Only an authenticated loading edge from the admitted game process
can vote the separate 640 ms Dragonite loading hint. The session itself
never requests CPU/GPU floors; Android's ordinary GameManager modes and
UWU's UI remain in place.

The same arbiter sends held, bounded top-app votes for shade and unlock
(`0-3`, restoring `0-7`) and background votes for launch and system fling
(`0`, restoring `0-1`). Top-app restriction yields to pending game placement
or UI placement in top-app (including unknown membership). Background is
already little-only. Camera remains an independent hint on its own node.
The two-cluster policy does not copy Axion's 6+2 branch, move processes
between groups, modify ART properties on fling, freeze background apps, or
detect benchmarks to override user policy. Init supplies the boot defaults;
only the existing libperfmgr HAL writes these masks at runtime.

## Process, platform, memory and ART

- `ax_process_utils` implements affinity, thread priority, and cpuset/profile
  operations. Placement uses Android's existing privileged scheduler and
  process utilities with the Dragonite lifecycle; no duplicate static
  library or direct process-wide cpuset replacement is linked. The pinned
  Axion native tree does not link this helper. A private SurfaceFlinger
  scheduling transaction is not a supported integration.
- `ax_platform_hooks` is the client API for Axion SystemUI's Binder service
  and Routines, not a Dragonite platform lifecycle API. Sagit has no matching
  Axion Routines consumer and retains the existing SystemUI tiles/controllers
  and permission checks. Do not introduce an exported package-prefix-based
  privilege bridge for Wi-Fi, Bluetooth, privacy, media or screen actions.
- `ax_memory_server` adds policy at AMS process launch/death and in
  `OomAdjuster`, including prefork, foreground-service/cached-app adjustment,
  camera/face-auth release and an explicit memory-release Binder call. These
  compete with AOSP AMS, LMKD, the freezer/compactor (`CachedAppOptimizer`)
  and mmd. They are not ported; no parallel pressure-trigger loop or app-kill
  policy is enabled. The read-only pressure diagnostic is independent of them.
- `ax_dexopt_server`'s in-call guard is registered by `DexOptHelper` as an
  ART batch start callback (`ax_dexopt_guard_enabled`, default on for the
  configured product). Its compensation policy is part of the existing
  `bg-dexopt` job in the ART project, not a new JobScheduler job or worker in
  SystemServer (`ax_compensate_dexopt_enabled`, default on). Axion's user
  optimization UI/status bridge, startup forced dexopt and remote config
  are not part of Sagit's Package Manager or Settings contract; existing
  Android `pm art`/dexopt operations retain their own privileges and behavior.

This is source integration. Running under the device's enforcing policy,
checking live CPU/PSI values, and observing in-call cancellation and ART
cancellation timing still require a device; no device verification is
implied by a focused Java compile.
