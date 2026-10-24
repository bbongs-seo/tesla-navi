# Tesla protocol attribution

`protocol/src/main/proto/*.proto` originates from:

- Repository: https://github.com/teslamotors/vehicle-command
- Revision: `a4b43c1eff0e09d77deb9f2dce97031141fe8c8a`
- Original directory: `pkg/protocol/protobuf`
- License: Apache-2.0, included in `TESLA-LICENSE` and in the APK assets.
- Local modification: `option java_multiple_files = true;` and a modification notice were added to generate individual Java classes. Protocol field numbers and types are unchanged.

`TeslaCrypto.kt` is a Kotlin adaptation of `internal/authentication/{native,metadata,peer,signer}.go`. It uses Java cryptography and Android-compatible keys, a monotonic session clock, and explicit response validation.

The fixed AES-GCM and leading-zero ECDH test vectors in `ProtocolTest.kt` are adapted from the upstream `internal/authentication/{protocol_doc,ecdh}_test.go` tests. Other tests and application code are local implementations.

The app is a personal third-party application and is not an official Tesla product.

## Kakao navigation SDK

The Android app uses the published binary dependency `com.kakaomobility.knsdk:knsdk:1.12.8-hotfix03` from Kakao Mobility's official Maven repository. The binary is not vendored into source control. SDK use is subject to the provider's terms, account permissions, and applicable usage limits.

`app/src/main/res/values/kakao_sdk_compat.xml` overrides the SDK's user-agent header resource with equivalent positional string placeholders to satisfy Android resource validation; rendered header contents are unchanged.

Official integration documentation: https://developers.kakaomobility.com/guide/android/start.html

## Supertonic voice (sherpa-onnx)

The optional offline guidance voice uses the official k2-fsa release binary `sherpa-onnx-1.12.34.aar` (SHA-256 `9042637b206ef2dd98ea3a2fe28ffaa7e8767557848d94b5e25c268db188535b`). Gradle task `:app:fetchSherpaOnnx` downloads it from https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.12.34 into `app/libs/`, verifies the checksum and fails the build on mismatch. The binary is not vendored into source control.

- sherpa-onnx: Apache-2.0 (https://github.com/k2-fsa/sherpa-onnx). The license text is the same Apache-2.0 text shipped as `TESLA-LICENSE`.
- ONNX Runtime, bundled inside the AAR as `libonnxruntime.so`: MIT, Copyright (c) Microsoft Corporation.
- Apache Commons Compress `org.apache.commons:commons-compress:1.27.1` extracts the model archive: Apache-2.0.
- Supertonic 2 model (`sherpa-onnx-supertonic-tts-int8-2026-03-06.tar.bz2`, 84,692,981 bytes, SHA-256 `8c74359f63edd5045d47747f65331f0f6dbcbc91d7e898dd756d631295fe3259`): not included in the APK. The app downloads it only when the user taps the download button, verifies size and checksum, and installs it under app-private storage. Supertone Inc. publishes the sample code under MIT (the archive's `LICENSE`, kept next to the model) and the model weights under OpenRAIL-M (https://huggingface.co/Supertone/supertonic-2/blob/main/LICENSE), which carries use-based restrictions.

Notices for the bundled binaries are in `app/src/main/assets/THIRD-PARTY-NOTICES.txt`.
