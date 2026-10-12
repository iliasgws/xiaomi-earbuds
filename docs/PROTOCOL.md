# Global Redmi Buds 6 Pro protocol reference

## Evidence and scope

Observed target: global Redmi Buds 6 Pro, `miwear.headphone.o76gl`, sales model
M2430E1, VID/PID `2717:509E`, firmware 1.3.9.9. The installed Xiaomi Earbuds app
was version 1.38.0i. This profile does not equate China/gaming IDs with the global
model. The Android client requires the global identity before setting writes.

App APK SHA-256:
`cbac95ac83de56ae93c6908856a99133862e3a101a55fa39edc91b7dd8520455`.
Matching ARM64 authentication library SHA-256:
`69cd6ed90d130a121fc0a83a0bb6ddb7ced1626b9ba176a82a01f4ccb34ef677`.

## Connection and frames

The successful observed connection used the MIUI RFCOMM UUID:
`0000fd2d-0000-1000-8000-00805f9b34fb`. The app's global-model cache selects
SPP-first. The Android client uses the ordinary paired-device RFCOMM API and
framed mutual authentication, not the Xiaomi private MMA service.

```text
Request:  FE DC BA flags opcode bodyLength:u16-BE sequence payload... EF
Response: FE DC BA flags opcode bodyLength:u16-BE status sequence payload... EF
```

Body length includes sequence/status. Flags bit 7 is request, bit 6 requests a
response, low three bits select target. Observed host command flags are **C4**;
ordinary replies use **04**. Device broadcasts can use **87**. `EF` is a constant
terminator, not a CRC. Opcode 1 has an extra/special subopcode field.

Replies are matched by **opcode and sequence**. Unsolicited status (`0E`) and
config (`F4`) commands are handled while waiting, rather than mistaken for a
reply. The original app ACKed `0E` status even with the response-required bit clear.

## Authentication

Opcodes `50/51` perform mutual authentication. Payload version is 1. A challenge
factor is 16 bytes; its reply is version 1 plus a 16-byte calculation. Pair result
0 is success. Peer result acknowledgment includes the version byte only.

For challenge C and six-byte seed `11 22 33 33 22 11`:

1. Key K is C, with byte 15 XOR `06`.
2. Plaintext is the repeated seed, truncated to 16 bytes.
3. Run the recovered eight-round SAFER+-style transform with original-input
   feed-forward before the third round, then final key mixing.

The shipped wrapper calculates a linkkey-dependent intermediate, then E21
overwrites its entire output. Original-instruction replay confirmed no final
output changes under 1,548 linkkey mutations. The implementation computes the
verified final value directly, without Android JNI or vendor binaries.

Both real directions matched:

| Challenge | Observed and calculated response |
| --- | --- |
| `f81be8e78d765a2e63339fc99a66320d` | `f8955578093e0cdf56e7c74436605f08` |
| `490b58fa1d22344b3413011ee49d4ff5` | `c05c35726a1303df4fa415aa5c2dbd8f` |

## Configuration namespace

`F2` sets, `F3` gets, `F4` notifies. Ordinary records are
`length:u8 | configId:u16-BE | value`, with length=value length+2. GET payloads
are just consecutive u16 IDs. Config 52 has special extended-length framing.
Config IDs are separate from opcodes and feature IDs.

| Control | Encoding | Live evidence |
| --- | --- | --- |
| Off / ANC / transparency | opcode 08, `02 04 mode`, mode 0/1/2 | All three acknowledged and reported |
| Noise level | config 000B, `[mode,level]` | ANC 10,18,19; transparency 0,1,2 acknowledged/reported |
| EQ preset | config 0007, one byte | 0 balanced, 5 bass, 10 custom acknowledged/read back |
| Gesture assignment | config 0002, `[gesture,leftAction,rightAction]` | Left double tap changed 3→4→3, right preserved |
| Custom profile | config 0037 report | Ten frequencies and +/-6 bounds read from device |
| Adaptive ANC / personalised ANC / adaptive sound | configs 0025 / 003B / 0029, one-byte switch | Values read; toggle write formats from APK code, not exercised in this stage |

Global cached ranges: ANC 0–19 and transparency 0/1/2. Preset IDs also include
1 voice and 6 treble; they were present in cache/UI but not individually written
in the recorded stage.

### Example captured bytes

```text
ANC mode:         FE DC BA C4 08 00 04 0A 02 04 01 EF
ANC level 10:     FE DC BA C4 F2 00 06 0E 04 00 0B 01 0A EF
Left double/up:   FE DC BA C4 F2 00 07 33 05 00 02 01 04 FF EF
Left double/next: FE DC BA C4 F2 00 07 34 05 00 02 01 03 FF EF
```

`FF` is the untouched-ear write sentinel. Original readback was:
`04 01 01 01 03 03 02 02 02 03 06 06 05 0B 0B` (single, double, triple,
hold, swipe triplets).

## Battery, firmware and custom EQ

Target info is opcode 02 with mask `FF FF FF FF`. Attributes use one-byte length
and one-byte type, a different record layout from 16-bit config IDs.

- Type 3 is VID/PID: `27 17 50 9E`.
- Type 1 is packed firmware/version values: `13 99 13 99` → 1.3.9.9.
- Type 7 contains left/right/case battery bytes. Low seven bits are percent;
  high bit is charging; `FF` is unavailable and must not renumber slots.
- Observed battery bytes `64 E4 32` → left 100%, right 100% charging, case 50%.

Custom profile's band frequencies: **62,125,250,500,1000,2000,4000,8000,12000,
16000 Hz**. Observed maximum/minimum bound fields are 6, with UI +/-6 dB.
Frequencies are u16-BE; negative gains use sign-magnitude (`-3` → `83`, not `FD`).

Recovered save value: `01 0A 01 01 01 00 N` followed by N three-byte bands.
Preview value: `05 01 01 N` followed by bands. Save serialization is implemented
with range checks/readback; gain writes were not part of the official-app live
stage, which preserved the user's existing zero-gain custom profile.

## Verification boundary

Auth tests include 516 original ARM64 vectors, not expected outputs generated
by the Kotlin implementation. Session tests independently replay a captured
handshake and include failure/timeout/device-guard cases. Some official-app
diagnostic dumps truncated at 50 bytes; no missing wire bytes were invented.
The full target-info fixture uses the app's complete parsed payload trace.

The user's tested settings were restored: transparency Regular, remembered ANC
level 19, bass EQ, original gestures and untouched custom gains. Runtime reads
confirmed restoration. The new APK's physical-phone smoke test is separately
tracked; official-app success is not represented as new-client hardware success.
