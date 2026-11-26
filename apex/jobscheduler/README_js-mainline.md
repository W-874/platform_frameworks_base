# Making Job Scheduler into a Mainline Module

## Current structure

- JS service side classes are put in `service-jobscheduler.jar`.
It's *not* included in services.jar, and instead it's put in the system server classpath,
which currently looks like the following:
`SYSTEMSERVERCLASSPATH=/system/framework/services.jar:/system/framework/ethernet-service.jar:/system/framework/com.android.location.provider.jar:/system/framework/service-jobscheduler.jar`

  `SYSTEMSERVERCLASSPATH` is generated from `PRODUCT_SYSTEM_SERVER_JARS`.

- JS framework side classes are put in `framework-jobscheduler.jar`,
and the rest of the framework code is put in `framework-minus-apex.jar`,
as of http://ag/9145619.

  However these jar files are *not* put on the device. We still generate
  `framework.jar` merging the two jar files, and this jar file is what's
  put on the device and loaded by Zygote.

The current structure is *not* the final design.

## Downstream OTA migration

On sagit, the boot phase after persisted jobs load cancels user 0's legacy
Motorola Dolby check-in job (`555805291`, default namespace) when PackageManager
no longer has a package state for `com.motorola.dolby.dolbyui`. Normal JobStore
writeback removes the orphan from disk so subsequent boots do not load it.
This is not a general missing-service sweep: a package can be known to
PackageManager while its service is temporarily unavailable.
