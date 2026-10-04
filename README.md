# GPU Clock Floor for OPPO Pad Mini

GPU Clock Floor is a small root utility for the **OPPO Pad Mini OPD2515** (Snapdragon 8 Gen 5, Adreno 829) on ColorOS 16. It holds the GPU clock at a chosen minimum and turns inter-frame power collapse off, so short bursts of GPU work, such as decoding a streamed video frame, run at a high clock every time.

> [!WARNING]
> This app writes to the Adreno kernel driver's tunables as root. A clock floor makes the tablet run hotter and drain the battery faster in every app, not only while streaming. Root access is mandatory. Use this software at your own risk.

## Why it exists

[Punktfunk](https://git.unom.io/unom/punktfunk) is a low-latency game-streaming app: a host PC encodes its screen and a client, here the tablet, decodes and shows it. With Punktfunk's PyroWave codec, the tablet decodes every video frame on the **GPU**, as a Vulkan compute job, instead of on the chip's dedicated video decoder.

That makes decode time depend on the GPU clock. At 144 frames per second each frame is a few milliseconds of GPU work followed by a short idle gap. The stock Adreno driver treats those gaps as a chance to save power: it lowers the clock and powers the GPU down between frames, so each frame starts slow. Holding the clock up shortens decode, from about 5.4–5.9 ms to 4.3–4.6 ms at the 1025 MHz floor in testing.

This only helps when the GPU does the decoding. Codecs that use the hardware video decoder, such as AV1 or HEVC, leave the GPU idle during a stream and gain nothing from a floor.

### Local games

The other use is games that run on the tablet itself and render on its GPU, such as Steam games through a compatibility layer. A floor stops the driver from dropping the clock during lighter moments and ramping back up when the scene gets heavy, which is one cause of uneven frame times. Holding the clock up aims at a steadier, more consistent frame rate.

This use was **not measured**. A floor sets the GPU's minimum clock; it does not raise its maximum, so it cannot lift a frame rate the GPU already struggles to reach at full speed. Expect the gain, if any, in consistency, and the same extra heat and battery drain.

**Only while streaming** watches for Punktfunk only. For a local game, set a floor with the buttons and use **Restore stock** when you finish.

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

- **Only while streaming.** A foreground service watches which app is in front. It applies the chosen floor while Punktfunk (`io.unom.punktfunk` or `io.unom.punktfunk.mokomis`) is in front and restores stock when another app is. This avoids the heat and battery cost outside a stream. It checks every two seconds and shows a persistent notification. **It is not reliable on ColorOS**, which stops the watcher in the background; see below.
- **Apply at startup.** After a reboot, the app applies the chosen floor again. With **Only while streaming** on, it restarts the watcher instead, so the floor still applies only during a stream.

> [!CAUTION]
> **1225 MHz: proceed with caution.** It is the GPU's top clock. Holding it there continuously could lead to overheating. With the ordinary 4:2:0 decode load that has not been shown: in a four-minute test the GPU reached 74–78 °C against a chip threshold of 105 °C and nothing throttled. Under a heavier load it has: decoding 4:4:4 video at this floor, the tablet cut the GPU clock to 646 MHz after about 13 minutes. The system thermal status reads severe well before that, and it tracks the tablet's surface, not the GPU: the service lists light, moderate and severe at a surface estimate of 48, 49 and 50 °C. See [What "severe" means here](#what-severe-means-here). Long sessions, a hot room, a case, or charging while playing were not tested.
>
> 1225 MHz is not an overclock. It is the highest level in the tablet's own Adreno driver table (`max_clock_mhz` reads 1225) and the published maximum for the Adreno 829, and the stock tablet reaches it by itself in short bursts. What this app changes is how long the GPU stays there.

Choosing 1225 MHz always asks for confirmation first, and the app shows a standing warning while 1225 MHz is the chosen floor, because either option will then apply it without asking again.

### The watcher does not survive on ColorOS

ColorOS stops background apps aggressively, and **Only while streaming** depends on a background watcher. When the watcher is stopped, the GPU stays at whatever was last written, which is stock if you were outside Punktfunk at the time.

- With no exemptions, ColorOS killed the watcher within a minute of the app leaving the screen.
- With background activity allowed in the app's settings, and the battery-optimization and background exemptions that turning the option on applies through root (`dumpsys deviceidle whitelist +…`, `appops set … RUN_ANY_IN_BACKGROUND allow`), it ran through a first test: three minutes on the home screen at stock, then the chosen floor when Punktfunk came to the front.
- It did not last. On the same tablet, with those exemptions still in place, ColorOS killed it twice more over the following hours while it was running as a foreground service. A later stream started at stock with the switch still showing on.

Swiping the app away in the recent-apps list also stops it. Opening the app starts the watcher again whenever its switch is on.

**Use a manual floor.** Set it with the buttons before you play and tap **Restore stock** afterwards. A manual floor stays set when the app is closed or killed.

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

These are 4:2:0 streams at 144 Hz. At 120 Hz the same stream decodes in 3.9–4.1 ms at the 1225 MHz floor with the GPU about 42% busy.

At 1225 MHz the system thermal status rose from moderate to severe within three minutes on an already warm tablet. No throttling was seen in the four minutes measured. At 1150 MHz the status stayed at moderate through four minutes, with the GPU temperature still rising slowly at the end. 925 MHz gave no gain and is not offered. None of these 4:2:0 runs was long enough to show where the temperatures level off.

### A heavier load does get throttled

Decoding 4:4:4 at 2520×1680 and 120 Hz is about twice the work. At the 1225 MHz floor it decoded in 6.6 ms and held 120 fps; at stock it took 10 ms and fell to 86 fps, so here the floor is required. The GPU was about 74% busy.

From a cool start the GPU climbed to 88 °C and the surface estimate to 49.5 °C. After about 13.5 minutes, four of them at severe, the tablet cut the GPU clock from 1225 to 646 MHz. The floor does not prevent this: the system's thermal limit overrides it, which is how it should work.

Lowering the floor to 1025 MHz afterwards did not cool the tablet. Eight minutes later the GPU was at 83 °C and the surface estimate unchanged. The heat follows the amount of decoding, not the floor.

### What "severe" means here

Android's thermal status is one number from 0 to 6. On this tablet two very different sensors could set it:

| | Skin (estimated surface) | GPU chip |
|---|---|---|
| What it measures | How hot the outside of the tablet is | The temperature inside the GPU |
| Light / moderate / severe | 48 / 49 / 50 °C | none / none / 105 °C |
| Critical / shutdown | 60 / 90 °C | none / 125 °C |

These are the thresholds the tablet's thermal service lists (`dumpsys thermalservice`). "Skin" is an estimate of the surface temperature calculated from internal sensors, and it is the only sensor with light and moderate levels. In every run the status moved with the surface estimate while the GPU stayed far below 105 °C. So "severe" here means the tablet is getting hot to hold, not that the GPU is near its limit.

The listed numbers are not the whole rule. In the longest run the status reached moderate at about 45 °C and severe at 48.2 °C, earlier than the table says. ColorOS has its own thermal manager (`horae`), which works from the shell sensors on the front, back and frame, and its policy files are encrypted on the device. What it does at each level could not be read.

The clock cut described above came with the GPU at 88 °C, 17 °C under the chip's own trip point, so it was not the chip protecting itself. It came from that thermal manager or from the battery current limiter, which can also throttle the GPU; which of the two was not established. Either way the surface limit also protects the battery and the screen behind it. This app leaves those limits alone, and you should too.

## Test status

Tested on one OPPO Pad Mini OPD2515, ColorOS 16, KernelSU, on October 4, 2026:

- Manual floors and **Restore stock**: working. A manual floor stays set after the app is closed.
- **Only while streaming**: not reliable. It worked in a first test and ColorOS later killed the watcher twice despite the exemptions.
- **Apply at startup**: not tested.
- The 1225 MHz confirmation: shown and accepted on the device. The standing warning was not checked.

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
