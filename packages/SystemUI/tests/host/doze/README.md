# Raise-to-wake v2: regressions and sagit acceptance

From the `frameworks/base` checkout, run with a host JDK. No Android build,
device, or external dependencies are needed:

```bash
classes=$(mktemp -d)
src=packages/SystemUI/src/com/android/systemui/doze
tests=packages/SystemUI/tests/host/doze
javac -d "$classes" "$src"/DozePickup{Motion,MotionFeatures,StateMachine}.java \
  "$tests"/DozePickup{Motion,StateMachine}HostTest.java &&
  java -cp "$classes" com.android.systemui.doze.DozePickupMotionHostTest &&
  java -cp "$classes" com.android.systemui.doze.DozePickupStateMachineHostTest
```

The traces are synthetic. They exercise static poses, impacts, periodic gait, four
positive paths, FIFO reordering, shifted hint times and gesture epoch reuse. They
do not measure a handset's accuracy, suspend retention, power, or display latency.
`DozeTriggersTest` separately checks full wake vs ambient pulse and late opt-out.

Only devices opting into `doze_pickup_motion_history` use this path. Sagit still
requires the user's pickup setting. A QTI one-shot is a request to inspect IMU
evidence: its scalar is not read and its timestamp is not a motion landmark. A
non-wake accelerometer requests a batched stream while eligible. History and live
streams are merged by sample time. Recognition stops at most 250 ms after receipt
(late callbacks must not dispatch). A positive path still needs observed FAR and
the usual Doze/user policy. Missing prehistory can arm an additional significant-
motion hint; neither that hint nor a proximity transition is sufficient to wake.
QTI is not throttled without an independently validated replacement. In particular,
screen-off success does not imply the QTI hardware stopped waking the AP.

## On-device validation after installing the matching build

1. Check `adb shell pm path com.android.systemui`; a `/data/app` update may shadow
   the image. Check `dumpsys activity service com.android.systemui/.doze.DozeService`
   for `Pickup motion`, the nonwake proximity listener, FIFO capacity, configured
   batch period, flush completions and `withPretriggerHistory`. Compare quiet
   awake and *actual suspend* sessions. FIFO capacity alone is not retained data.
2. Label desk tilt, level lift, removal from upright stand, pocket withdrawal and
   walking-to-view trials. Alternate initial angles, hands and speeds; record
   misses, duplicate wakes and absence of hardware candidates separately. Test
   ambient pulse and full lockscreen wake. Target >=95% on each positive scenario;
   report denominators and confidence intervals, not just an aggregate percentage.
3. Record confirmations and receipt-to-confirmation latency (last/max in dump;
   collect per-trial values for percentiles). Time to first lit frame is a separate
   measure. A timeout without confirmation is a miss, not a low-latency success.
4. Run prolonged face-up, stand, face-down, desk vibration, pocket walking and
   ordinary handheld walking sessions; record duration and false wakes (target 0).
   Compare matched unplugged idle runs with pickup on/off, without continuously
   polling over USB: deep-sleep residency, wakeup sources, candidate and evaluation
   counts, sensor registrations and wakelock time. No persistent SystemUI pickup
   wakelock should exist. Record separately any QTI wake storm and battery cost.
