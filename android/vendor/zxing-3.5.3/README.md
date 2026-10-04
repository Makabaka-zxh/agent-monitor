# ZXing Core 3.5.3

Pure Java QR decoding runs on the phone. No camera frame or selected QR image is uploaded. This is the `com.google.zxing:core:3.5.3` Maven Central artifact, kept locally so builds do not download changing dependencies.

- Upstream: https://github.com/zxing/zxing/tree/zxing-3.5.3
- Binary: https://repo.maven.apache.org/maven2/com/google/zxing/core/3.5.3/core-3.5.3.jar
- Published SHA1: https://repo.maven.apache.org/maven2/com/google/zxing/core/3.5.3/core-3.5.3.jar.sha1
- SHA1, verified against the published checksum: `ca1349214a356cd7958651b2d5a0e1f3811a9c4b`
- SHA256, enforced by `build.ps1`: `8d8064c1636fdaef7189dd9055c7d59950a8940a12f2293956446ec3c109fd82`
- Size: 607650 bytes
- License: Apache License 2.0, copied verbatim from the release tag to `LICENSE` (including upstream notices).
- Downloaded: 2026-09-14.

`build.ps1` adds this JAR to the Java 8 compile classpath and the D8 inputs. Version 3.5.3 is pinned for the existing Java 8 Android build. Only QR_CODE is allowed by the local decoder; scanned text is returned to the calling Activity and must pass its same-server pairing URL validation before showing the confirmation screen.
