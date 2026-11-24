# Doze pickup motion regression

From the `frameworks/base` checkout, run with a host JDK. No Android build,
device, or external dependencies are needed:

```sh
classes=$(mktemp -d)
javac -d "$classes" \
  packages/SystemUI/src/com/android/systemui/doze/DozePickupMotion.java \
  packages/SystemUI/tests/host/doze/DozePickupMotionHostTest.java &&
java -cp "$classes" com.android.systemui.doze.DozePickupMotionHostTest
```

`DozePickupMotionHostTest` feeds synthetic, timestamped samples to the
production classifier. The Android wiring is covered separately by
`atest SystemUITests:com.android.systemui.doze.DozeTriggersTest` from a configured
Android build environment.

The classifier only sees samples delivered in the 500 ms window opened after a
Doze pickup trigger is admitted by the existing proximity/state checks. It has
no pre-trigger accelerometer data. A first sample already taken at rest cannot
prove that a lift took place; an isolated step can also resemble a lift if the
next step falls outside the window. Stationary pickup pulses are the historical
failure motivating the filter, but retained wake reasons and sensor-registration
records do not contain timestamped desk-lift or walking acceleration traces.
The model thresholds are therefore regression fixtures, not handset calibration.

Only configurations opting into `doze_pickup_require_tilt` run this check. The
platform default is `false`; Sagit's overlay enables the check without enabling
its separate user pickup setting by default. Face authentication has its own
pickup listener and does not use this check.
