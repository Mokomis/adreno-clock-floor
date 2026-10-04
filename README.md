# GPU Clock Floor for OPPO Pad Mini

GPU Clock Floor is a small root utility for the **OPPO Pad Mini OPD2515** (Snapdragon 8 Gen 5, Adreno 829) on ColorOS 16. It holds the GPU clock at a chosen minimum and turns inter-frame power collapse off, so short bursts of GPU work, such as decoding a streamed video frame, run at a high clock every time.

> [!WARNING]
> This app writes to the Adreno kernel driver's tunables as root. A clock floor makes the tablet run hotter and drain the battery faster in every app, not only while streaming. Root access is mandatory. Use this software at your own risk.

## What it does

The app writes two values under `/sys/class/kgsl/kgsl-3d0`:

```text
gmu/dcvs_tunables/min_freq_mhz   # stock: -1 (no floor)   set: 1025, 1150 or 1225
ifpc                             # stock: 1 (on)          set: 0 (off)
```

**Restore stock** writes `-1` and `1` back and switches both options below off. Changes take effect at once. The driver forgets both values on reboot; by default the app does not put them back.

Either change alone made no measurable difference in testing. They are switched together.

## Options

Both are off by default.

- **Only while streaming.** A foreground service watches which app is in front. It applies the chosen floor while Punktfunk (`io.unom.punktfunk` or `io.unom.punktfunk.mokomis`) is in front and restores stock when another app is. This avoids the heat and battery cost outside a stream. It checks every two seconds and shows a persistent notification.
- **Apply at startup.** After a reboot, the app applies the chosen floor again. With **Only while streaming** on, it restarts the watcher instead, so the floor still applies only during a stream.

> [!CAUTION]
> **1225 MHz: proceed with caution.** It is the GPU's top clock. Holding it there continuously could lead to overheating. That has not been shown: in a four-minute test the GPU reached 74–78 °C against a chip threshold of 105 °C and nothing throttled. The system thermal status did rise to severe, but that came from the tablet's surface estimate, not from the GPU. On this tablet light, moderate and severe are only 1 °C apart: the surface estimate at 48, 49 and 50 °C. "Severe" here means a surface of about 50 °C; see [What "severe" means here](#what-severe-means-here). Longer sessions, a hot room, a case, or charging while playing were not tested.
>
> 1225 MHz is not an overclock. It is the highest level in the tablet's own Adreno driver table (`max_clock_mhz` reads 1225) and the published maximum for the Adreno 829, and the stock tablet reaches it by itself in short bursts. What this app changes is how long the GPU stays there.

Choosing 1225 MHz always asks for confirmation first, and the app shows a standing warning while 1225 MHz is the chosen floor, because either option will then apply it without asking again.

### Keeping the watcher alive on ColorOS

ColorOS stops background apps aggressively. Before any exemption it killed the watcher within a minute of the app leaving the screen, which leaves the GPU at whatever was last written.

With the following in place the watcher ran for the whole test: three minutes on the home screen with the driver at stock, then the chosen floor applied when Punktfunk came to the front.

- **Background activity allowed** for GPU Clock Floor in the tablet's app settings. This is a manual step.
- **Exempt from battery optimization, and allowed to run in the background.** Turning **Only while streaming** on applies both through root (`dumpsys deviceidle whitelist +…`, `appops set … RUN_ANY_IN_BACKGROUND allow`).

Which of these is strictly required was not isolated. Swiping the app away in the recent-apps list still stops the watcher; lock its card there if your ColorOS version offers that. Opening the app starts the watcher again whenever its switch is on.

**Apply at startup** was not tested with a reboot. ColorOS has a separate auto-launch list, and the app may need to be allowed there for it to start after boot.

## Why this node

On ColorOS, OPPO's resource service (`vendor.oplus.hardware.urcc-service`) rewrites the driver's ordinary floor (`min_pwrlevel` / `min_clock_mhz`) on every app switch, so a floor set there lasts under a second. It was not seen to write the firmware tunable `gmu/dcvs_tunables/min_freq_mhz`, which survived app restarts and reconnects through the test session.

## Measured effect

OPPO Pad Mini, stock Qualcomm Vulkan driver, PyroWave video decode at 2520×1680, 144 Hz. Short observations from one evening, not a benchmark.

| Floor | Decode time | GPU temperature |
|---|---|---|
| Stock | 5.4–5.9 ms | 49–57 °C |
| 1025 MHz | 4.3–4.7 ms | 62–68 °C |
| 1150 MHz | 4.1 ms | 70–74 °C |
| 1225 MHz | 3.8–3.9 ms | 74–78 °C |

At 1225 MHz the system thermal status rose from moderate to severe within three minutes. No throttling was seen in the four minutes measured; longer sessions were not tested. At 1150 MHz the status stayed at moderate through four minutes, with the GPU temperature still rising slowly at the end. 925 MHz gave no gain and is not offered.

### What "severe" means here

Android's thermal status is the worst level reported by any sensor, and on this tablet two very different sensors matter:

| | Skin (estimated surface) | GPU chip |
|---|---|---|
| What it measures | How hot the outside of the tablet is | The temperature inside the GPU |
| Light / moderate / severe | 48 / 49 / 50 °C | none / none / 105 °C |
| Critical / shutdown | 60 / 90 °C | none / 125 °C |
| Reading at 1225 MHz | about 50 °C | 74–78 °C |

These are the thresholds the tablet's thermal service reports (`dumpsys thermalservice`). "Skin" is an estimate of the surface temperature calculated from internal sensors, and it is the only sensor with light and moderate levels. So every status of 1, 2 or 3 in these tests came from the surface estimate.

"Severe" at 1225 MHz therefore meant the tablet was getting hot to hold, at about 50 °C, while the GPU was still roughly 27 °C below its own limit. The surface limit exists for comfort and safety in the hand. With the tablet on a stand and not held, a warm surface matters less to you, and the chip still has room.

Two cautions. The system does not know whether the tablet is being held, so ColorOS may still react to the surface estimate by limiting frame rate, brightness or performance; what it does at each level was not traced. And the three low levels sit only 2 °C apart, so the status climbs quickly once the tablet is warm, with the next step, critical, 10 °C further on.

## Test status

Tested on one OPPO Pad Mini OPD2515, ColorOS 16, KernelSU, on October 4, 2026:

- Manual floors and **Restore stock**: working. A manual floor stays set after the app is closed.
- **Only while streaming**: working with the keep-alive steps above. Stock on the home screen, the chosen floor with Punktfunk in front.
- **Apply at startup**: not tested.
- The 1225 MHz confirmation and standing warning: not exercised on the device.

## Safety behavior

Before a change, the app:

- Requests a root shell through `su`.
- Confirms the three driver nodes exist, and reports the device as unsupported otherwise.
- Refuses a floor that the GPU's own frequency table (`freq_table_mhz`) does not list.
- Reads both values back after writing and reports an error if the driver did not keep them.
- Asks for confirmation before setting the top clock, and keeps a warning on screen while it is the chosen floor.

## Runtime requirements

- **OPPO Pad Mini OPD2515**, or another Adreno device whose driver exposes the same nodes and lists the chosen frequency. Other devices are untested.
- **Android 9 or newer**; tested on ColorOS 16 / Android 16.
- **An unlocked and rooted tablet** with a working `su`. Tested with KernelSU: after installation, open KernelSU Manager, select **Superuser**, open **GPU Clock Floor**, and enable **Superuser** access.
- **Magisk:** not tested.

No network connection or external service is required.

## Permissions

The manual buttons need only root. The options add:

- `RECEIVE_BOOT_COMPLETED`, for **Apply at startup**.
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` and `POST_NOTIFICATIONS`, for the watcher and its notification.
- `PACKAGE_USAGE_STATS`, to see which app is in front. Turning **Only while streaming** on grants this and the notification permission to the app itself through root (`appops set … GET_USAGE_STATS allow`, `pm grant … POST_NOTIFICATIONS`), along with the battery and background exemptions described above. The app reads only the name of the app in front and sends nothing anywhere.

## Build

```sh
./gradlew :app:assembleDebug
```

Requires JDK 17 or newer and Android SDK platform 36.

## License

GPL-3.0-only. See [LICENSE](LICENSE).
