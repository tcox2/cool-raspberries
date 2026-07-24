# Research notes and evidence ledger

## Scope

These notes were derived by static inspection of commit
`ffcedb4f8865e0bb8a25cdabcfa9176f31f1610d`, dated 2025-10-25, in
`TheMiNuS/UnleashedAirConditionner`.

Primary evidence:

- `src/main.cpp`: UART initialization and keepalive interval.
- `lib/UacDevice/UacDevice.h`: declared frame sizes and field offsets.
- `lib/UacDevice/UacDevice.cpp`: transmitted bytes, decoding operations, and
  checksum algorithm.
- `Protocol/protocol_Airton.md`: the upstream author's interpretation, used as
  a lead but not accepted where it conflicts with code.

## Confidence labels

- **High:** directly constructed, decoded, or configured in executable code.
- **Medium:** strongly indicated by names or consistent code paths.
- **Low:** present only in declarations or prose, or otherwise untested.
- **Unknown:** no evidence sufficient for an interpretation.

## Corrections to earlier notes

1. Offsets 8–9 are not universally `0A 0A`; constructed A1 uses `00 00`.
2. A1 fan speed occupies bits 6:4, while bit 7 is turbo.
3. The published A3 sample contains 32 octets despite the declared length byte
   and implementation both requiring 34.
4. The claim that `01 05` means 16.5 °C conflicts with the implemented formula,
   which yields 1.5 °C.
5. Timer ordering is internally inconsistent between the setter and decoder.
6. A3 is the only received message for which CRC validation is visible.
7. A2 and A5 purposes are not supported by executable handling in the device
   class.

## Parser risks discovered

These are implementation observations, not protocol properties:

- The reference parser trusts the length byte before demonstrating a bounded
  destination selection.
- It does not check addresses, message type/length agreement, or offsets 8–9
  before accepting the header.
- CRC validation is performed after A3 decoding and publication is called
  regardless of validity.
- A4, A6, and AC are accepted without checksum verification.
- Resynchronization after an isolated `7A` or malformed header is rudimentary.
- Signed `char` use in the CRC routine may behave unexpectedly for octets above
  `7F` on platforms where `char` is signed.

Independent implementations should use unsigned octets, validate first, and
apply strict length bounds.

## Experiments needed

| Priority | Experiment | Question answered |
|---:|---|---|
| 1 | Passive logic-analyser capture of idle and boot | voltage, polarity, endpoints, boot sequence |
| 2 | Change one setting at a time | bit semantics and enum values |
| 3 | Compare room temperature over a range | A3 offsets 10–11 encoding |
| 4 | Set timers above and below 255 minutes | byte order and units |
| 5 | Disable/enable physical remote | meaning of A4 values |
| 6 | Block keepalive frames | AB/AC relationship and timeout behaviour |
| 7 | Capture enrolment/reset | A2 and A5 roles |
| 8 | Collect multiple models/PCBs | compatibility matrix |

