# CI-only personal Android signer

The single-secret path lets the owner generate 256 random bits on their phone and save them directly in the repository Actions Secret `POLARIS_ANDROID_SIGNING_SEED`. The value never goes through chat or repository contents. Treat it as a signing private key: anyone holding it can reproduce the full signing identity. See [owner setup](../../docs/qiufann/signing-setup.md).

## Frozen recovery profile

- Input: 32 cryptographically random bytes, encoded as exactly 64 hexadecimal characters (case-insensitive, surrounding whitespace ignored). The generator uses `crypto.getRandomValues`, never `Math.random`. Placeholder checks are not an entropy measurement.
- HKDF-SHA256 salt: ASCII `polaris-android-signing-v1`.
- HKDF info prefix: ASCII `Qiufannn/polaris-local-first|com.alyssa.polaris|signing-v1`.
- Private scalar: HKDF info `<prefix>|private-key`, 64 bytes interpreted unsigned big-endian, reduced modulo `(P-256 order - 1)`, plus one. 512-bit reduction makes modulo bias negligible.
- EC public/private key encoding: Bouncy Castle 1.87 `secp256r1` named-curve representation. All three dependency JARs are SHA256 pinned by `prepare.sh`.
- Certificate: owner `CN=Polaris Personal Android,O=Qiufannn`; serial first 20 bytes of SHA256(SubjectPublicKeyInfo) with high bit cleared; validity 2026-01-01 to 2126-01-01 UTC; critical BasicConstraints(false) and digitalSignature KeyUsage. RFC 6979 SHA256 ECDSA yields the same certificate bytes each time. Android compares the entire signing certificate, so a stable public key alone is insufficient.
- Temporary PKCS12 password: HKDF info `<prefix>|temporary-pkcs12-password`, 32 bytes, unpadded base64url. It is independent of the private-key derivation. PKCS12 encryption randomness does not change the recovered identity.

**Do not change any profile detail after installation.** The public fixture cert fingerprint is frozen in `PersonalApkSigner.java`, catching accidental encoding/provider changes. After first real signing, pin the personal public certificate fingerprint in `expected-certificate.sha256` (one lowercase SHA256 line, no private material); production signing and upload verification reject a mismatch. Before that first pin exists, the owner must confirm and retain the first real public certificate receipt.

## Isolation and checks

`prepare.sh` downloads checksummed public dependencies, compiles with Java 17, and runs self-tests. `self-test-apk` uses the explicitly public byte sequence 00 through 1f as a test vector, signs only a temporary APK, verifies it for Android 10+, then deletes it. No fixture APK is uploaded. The production `sign` command always reads the GitHub Secret from the environment and never defaults to test material.

Production makes a temporary 0600 PKCS12 on the runner, passes the temporary password through stdin to Android build-tools 35.0.0 apksigner, removes the seed from the signing child environment, cleans up, and verifies the public certificate. It does not persist a backup key. The runner, workflow code, trusted Actions and repository write access remain part of the secret's trust boundary, as with conventional CI signing. Revoking a GitHub Secret does not invalidate already installed APKs.

Commands (no secret arguments):

```sh
bash tools/android-signing/prepare.sh
java -cp "$RUNNER_TEMP/polaris-signing-tools/classes:$RUNNER_TEMP/polaris-signing-tools/lib/*" PersonalApkSigner self-test-apk unsigned.apk
java -cp "$RUNNER_TEMP/polaris-signing-tools/classes:$RUNNER_TEMP/polaris-signing-tools/lib/*" PersonalApkSigner sign unsigned.apk signed.apk tools/android-signing/expected-certificate.sha256
```

A missing or malformed seed stops signing; mixed seed/legacy-keystore configuration stops the workflow. Actual signed artifacts contain only the APK, checksums, source receipt and public certificate information. Secrets are scoped to the signed job after the web, Android and public-fixture checks succeed, on the owner's trusted branch push only.
