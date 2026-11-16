# Thermal camera: hardware reference

Device: USB `0bda:5840` (Realtek bridge), InfiRay **Tiny1-C** module, 256x192 @ 25 fps, UVC device "USB Camera".

Legend: **[verified]** = measured on our unit. **[P2 Pro]** = documented for `0bda:5830` (same module), not yet tested on 5840.

## Video stream [verified]

| Mode | Content |
|---|---|
| YUYV 256x192 @25 | Processed image only |
| YUYV 256x384 @25 | Top half: image (looked like noise in our capture). Bottom half: uint16 LE per pixel temperature |

- Temperature: `°C = raw / 64 - 273.15`. The bottom-half image is clean (ceiling, panels and lights clearly visible).
- Cold start: about 3 s of frames are constant `0x8000` (no data). [P2 Pro] Then about 6 s of real but uncorrected, noisy data. After that the image is flat-fielded and normal.
- No unlock or authentication is needed to stream.

## Command channel [P2 Pro]

This is not a UVC extension unit. Commands are vendor control transfers to the Realtek bridge.

- Write: `ctrlTransfer(0x41, 0x45, wValue=0x78, wIndex=addr, data)`
- Read: `ctrlTransfer(0xC1, 0x44, wValue=0x78, wIndex=addr, len)`
- Ready poll: read 1 byte at `0x200` after every command.
  - `b & 0x03 == 0` means idle.
  - `b & 0xFC != 0` means error.
- Standard command:
  - Send an 8-byte header to `0x1d00`: cmd `<H`, param `<I`, len `>H`.
  - Read the response from `0x1d08`.
- Command with payload:
  - Send the header to `0x9d00`.
  - Send the data to `0x9d08+off` in 64-byte chunks.
  - Send the last 8 bytes to `0x1d08+off`.
- Long command:
  - At `0x9d00`: cmd `<H`, p1 `>H`, p2 `>I`.
  - At `0x1d08`: p3 `>I`, p4 (or readLen) `>I`.
  - Read the result from `0x1d10`.
- SET opcode = GET opcode `| 0x4000`.
- The stream must be open first, otherwise the call hangs. On a cold camera the channel answers about 2.9 s after the stream starts.
- Android: works with `UsbDeviceConnection.controlTransfer`, no root needed.
- Windows: needs a libusb filter driver (Zadig), which breaks the UVC webcam driver. Test commands on Android.

## Commands [P2 Pro]

| Function | Opcode / bytes | Notes |
|---|---|---|
| TPD get / set (long cmd: p1=id, p2=value, get returns 2 bytes BE) | `0x8514` / `0xC514` | Ids listed below. |
| High gain example | `14 C5 00 05 00 00 00 01` -> `0x9d00`, then 8 zero bytes -> `0x1d08` | |
| Manual FFC (`ooc_b_update`) | `0d c1 01 00 00 00 00 00` | Disputed: one source says no manual-FFC opcode exists. |
| Shutter actuator (`shutter_manual_switch`) | `0x420c` | 0 = open, 1 = close. Packing not confirmed. |
| Auto-shutter get / set | `0x8214` / `0xC214` | Ids listed below. Not persisted across power loss. Packing presumed long cmd. |
| Palette get / set (param 0, 1-byte payload) | `0x8409` / `0xC409` | 1 white-hot, 3 iron, 4-6 and 9-10 rainbow, 7 red-hot, 8 hot-red, 11 black-hot. Affects the top-half image only. |
| Device info | `0x8405` param 0-8 | Chip id, fw date, qualification, IR info, project, fw version, PN, SN, sensor id. Lengths: 8, 8, 8, 26, 4, 50, 48, 16, 4. |
| Sensor temperature (`cur_vtemp`) | `0x8B0D` | 2 bytes BE, scale unknown. Use for self-heating monitoring. |
| Shutter temperature (`shutter_vtemp`) | `0x840C` | 2 bytes BE, scale unknown. |
| Y16 preview start / stop | `0a 01 00 00 00 <mode> 00 00` (`0x010a`) / `0x020a` | **Risky**: can leave the camera in a broken raw mode until it is unplugged. |
| Preview start / stop | `0xc10f` / `0x020f` | |

TPD ids:

| Id | Parameter | Notes |
|---|---|---|
| 0 | Distance | Scale disputed: 1/163.835 m or 1/128 m. |
| 1 | Reflected temperature | Kelvin. |
| 2 | Atmospheric temperature | Kelvin. |
| 3 | Emissivity | Scale disputed: /127 or /129. Calibrate by read-back. |
| 4 | Transmittance | |
| 5 | Gain | 0 = low, 1 = high. Switching gain re-runs calibration (allow up to 30 s). |

Auto-shutter ids:

| Id | Parameter | Default |
|---|---|---|
| 0 | Switch | 1 |
| 1 | Minimum interval | 5 s (lowering it is refused) |
| 2 | Maximum interval | 60 s |
| 9, 10 | Preview-start delays | 5 s / 4 s |
| 11, 12 | Gain-change delays | 5 s / 4 s |

Setting ids 9 and 10 to 1 at each stream open cut the time to a usable image from about 9.4 s to about 5.4 s.

**Never send:**
- `0x8201` (spi_transfer): writes flash.
- `0x0805` (sys_reset_to_rom): resets the camera to ROM.

Known in the SDK, opcodes not yet found:
- `get/set_prop_image_params` (TNR, brightness, ...)
- `tpd_ktbt_recal_1point` / `_2point` (user temperature calibration)
- `restore_default_cfg`
- `spi_config_save`
- Point, rectangle and min/max temperature reads
- DDE, contrast, AGC, mirror/flip, bad-pixel calibration, humidity

Likely source for these: `falcon_cmd.h` and `vdcmd.h` in danjulio/iCam, or `libircmd.so` in vendor APKs.

## Official InfiRay SDK (AC010_256) [found for 0bda:5840]

Source: https://github.com/matbeedotcom/Tiny-1C-Python-Bindings. It defaults to VID 0x0BDA / PID 0x5840, which confirms our unit. Header: `include/thermal_cam_cmd.h` (libiruvc).

Opcodes are inside the libs, not in the header. Extract them by disassembly, or cross-check with the [P2 Pro] table above.

### Image parameters (`set/get_prop_image_params`)

| Parameter | Range / values |
|---|---|
| TNR (temporal noise reduction) | 0-3 |
| SNR (spatial noise reduction) | 0-3 |
| DDE (detail enhancement) | level |
| Brightness | level |
| Contrast | level |
| AGC mode | 0-5 |
| AGC max gain | 0-255 |
| AGC BOS (brightness offset) | 0-255 |
| AGC on/off | 0 / 1 |
| Mirror / flip | 0 none, 1 mirror, 2 flip, 3 both |
| Flyer mode | on / off |

### TPD parameters (`set/get_prop_tpd_params`)

| Parameter | Unit | Range |
|---|---|---|
| Distance | | |
| TU (reflected temperature) | K | 230-500 high gain, 230-900 low gain |
| TA (atmospheric temperature) | K | 230-500 high gain, 230-900 low gain |
| EMS (emissivity) | 1/128 | 1-128 |
| TAU (transmittance) | 1/128 | 1-128 |
| GAIN_SEL | | |
| DIST_COR_FACT | | Unsupported |
| ENV_CORRECT_SEL | | |
| DIST_CORRECT_SEL | | |
| TEMP_MAP_SEL | | |

This resolves the emissivity scale conflict above: the scale is **1/128**.

### Auto-shutter parameters

| Id | Parameter |
|---|---|
| 0 | Switch |
| 1 | Minimum interval |
| 2 | Maximum interval |
| 3 | Temperature threshold OOC |
| 4 | Temperature threshold B |
| 5 | Protect switch |
| 6 | Any interval |
| 7 | Protect threshold, high gain |
| 8 | Protect threshold, low gain |
| 9, 10 | Preview-start delays (1st, 2nd) |
| 11, 12 | Gain-change delays (1st, 2nd) |

FFC is triggered automatically by sensor temperature drift (ids 3 and 4).

### Other controls

- **Overexposure protection** (sun / laser burn guard): switch, pixel threshold, count threshold, close delay.
- **Y16 stream sources:** TEMPERATURE, IR, KBC, HBC_DPC, VBC, TNR, SNR, AGC, DDE, GAMMA, MIRROR. These tap the ISP pipeline at each stage. IR is raw pre-correction sensor data.
- **Sensor and shutter temperature:** `cur_vtemp_get`, `shutter_vtemp_get`. The lens temperature (`lens_vtemp`) is unsupported on TC modules.
- **Calibration:** `ooc_b_update` (B_UPDATE, OOC_UPDATE, OOC_B_UPDATE, which is manual FFC). `tpd_ktbt_recal_1point` / `_2point` for user temperature recalibration. `dpc_add/remove_point` and `dpc_auto_calibration` for dead pixels.
- **On-camera measurement:** `tpd_get_max_min_temp_info`, point, line and rect temperatures, rect thresholds.
- **Zoom:** center or position, steps 1-4.
- **Other:** `rmcover_*` (lens cover correction), `switch_fps`, `restore_default_cfg`, `spi_config_save` (persist settings).

### Firmware

`update_fw`, `sys_reset_to_update_fw`, `spi_erase_fw` and `spi_write` exist. **Brick risk.** There are no public firmware images or community firmware hacks. A model-specific "V2" firmware reportedly broke third-party apps on T2S+.

## Specs

- NETD < 40 mK at 25 °C. Accuracy ±2 °C or 2 %.
- High gain: -15 to 150 °C (saturates in practice around 175-183 °C).
- Low gain: 50 to 550 °C (measured to at least 437 °C).
- 25 fps maximum: no faster mode exists.

## Open questions

Whether the camera-side emissivity and distance settings change the raw temperature stream is disputed between sources. Test on our unit. Otherwise compute the corrections on the host.

## Software techniques that unlock the hardware

- **Software flat-field:** average 32 frames of a uniform wall. Beats the shutter: it also corrects window specks and vignetting.
- **Radiometric recording:** FFV1 `gray16le` 256x192 in MKV, about 1.2 GB per hour.
- **Temporal denoise and super-resolution:** run on the phone. Not done on the camera.

## Sources

- https://github.com/LeoDJ/P2Pro-Viewer (`P2Pro/P2Pro_cmd.py`)
- https://github.com/lbatalha/p2-viewer (`src/p2_viewer/vendor.py`)
- https://github.com/stanelie/p2-viewer (`docs/p2pro_protocol.md`, `docs/thermal_cameras.md`)
- https://github.com/mwinter-osr/IR_Cam_QianLi
- https://github.com/thezenox/thermaLab (`lib/t2s/tiny1c_commands.dart`)
- https://github.com/danjulio/iCam (`firmware/components/tiny1c/`)
- https://hackaday.io/project/199352-more-accurate-thermal-imaging-cameras
- https://www.eevblog.com/forum/thermal-imaging/infiray-and-their-p2-pro-discussion/325/
