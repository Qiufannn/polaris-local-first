import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.Security;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;
import org.bouncycastle.jce.spec.ECPrivateKeySpec;
import org.bouncycastle.jce.spec.ECPublicKeySpec;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.DefaultSignatureAlgorithmIdentifierFinder;

/** CI-only signer. The recovery seed is read exclusively from a GitHub Secret, never from arguments. */
public final class PersonalApkSigner {
    // Frozen profile: changing these values would change the Android update certificate.
    private static final byte[] SALT = "polaris-android-signing-v1".getBytes(StandardCharsets.US_ASCII);
    private static final String CONTEXT = "Qiufannn/polaris-local-first|com.alyssa.polaris|signing-v1";
    private static final String ALIAS = "polaris-personal";
    private static final String FIXTURE_CERTIFICATE = "d822d0ad4dfd8c955f61f23bc592b208836273335d80677412ae51feae669368";
    private record Identity(KeyPair keys, X509Certificate certificate, char[] password) {}

    private static byte[] expand(byte[] seed, String purpose, int length) {
        HKDFBytesGenerator hkdf = new HKDFBytesGenerator(new SHA256Digest());
        hkdf.init(new HKDFParameters(seed, SALT, (CONTEXT + "|" + purpose).getBytes(StandardCharsets.US_ASCII)));
        byte[] output = new byte[length]; hkdf.generateBytes(output, 0, output.length); return output;
    }

    private static byte[] parseSeed(String value) {
        if (value == null || !value.trim().matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("Use a phone-generated 64-character random hex seed in the signing Secret.");
        }
        String hex = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (hex.chars().distinct().count() < 8) throw new IllegalArgumentException("Use the local random generator, not a repeated placeholder.");
        return java.util.HexFormat.of().parseHex(hex);
    }

    private static Identity identity(byte[] seed) throws Exception {
        ECNamedCurveParameterSpec curve = ECNamedCurveTable.getParameterSpec("secp256r1");
        byte[] material = expand(seed, "private-key", 64);
        // 512-bit reduction has negligible bias for a 256-bit curve order.
        BigInteger d = new BigInteger(1, material).mod(curve.getN().subtract(BigInteger.ONE)).add(BigInteger.ONE);
        Arrays.fill(material, (byte) 0);
        KeyFactory factory = KeyFactory.getInstance("EC", "BC");
        KeyPair keys = new KeyPair(factory.generatePublic(new ECPublicKeySpec(curve.getG().multiply(d).normalize(), curve)),
            factory.generatePrivate(new ECPrivateKeySpec(d, curve)));
        byte[] serialBytes = Arrays.copyOf(MessageDigest.getInstance("SHA-256").digest(keys.getPublic().getEncoded()), 20);
        serialBytes[0] &= 0x7f;
        BigInteger serial = new BigInteger(1, serialBytes);
        if (serial.signum() == 0) serial = BigInteger.ONE;
        X500Name owner = new X500Name("CN=Polaris Personal Android,O=Qiufannn");
        var builder = new JcaX509v3CertificateBuilder(owner, serial,
            Date.from(Instant.parse("2026-01-01T00:00:00Z")), Date.from(Instant.parse("2126-01-01T00:00:00Z")), owner, keys.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature));
        Signature signature = Signature.getInstance("SHA256withECDDSA", "BC"); // RFC 6979: certificate bytes stay stable.
        signature.initSign(keys.getPrivate());
        ByteArrayOutputStream tbs = new ByteArrayOutputStream();
        ContentSigner signer = new ContentSigner() {
            public AlgorithmIdentifier getAlgorithmIdentifier() {
                return new DefaultSignatureAlgorithmIdentifierFinder().find("SHA256withECDSA");
            }
            public OutputStream getOutputStream() { return tbs; }
            public byte[] getSignature() {
                try { signature.update(tbs.toByteArray()); return signature.sign(); }
                catch (Exception error) { throw new IllegalStateException("Certificate signing failed."); }
            }
        };
        X509Certificate cert = new JcaX509CertificateConverter().setProvider("BC").getCertificate(builder.build(signer));
        cert.verify(keys.getPublic());
        byte[] passwordBytes = expand(seed, "temporary-pkcs12-password", 32);
        char[] password = Base64.getUrlEncoder().withoutPadding().encodeToString(passwordBytes).toCharArray();
        Arrays.fill(passwordBytes, (byte) 0);
        return new Identity(keys, cert, password);
    }

    private static String fingerprint(X509Certificate certificate) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
    }

    private static KeyStore store(Identity identity) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12"); store.load(null, identity.password());
        store.setKeyEntry(ALIAS, identity.keys().getPrivate(), identity.password(), new java.security.cert.Certificate[] { identity.certificate() });
        return store;
    }

    private static void require(boolean condition) {
        if (!condition) throw new IllegalStateException("Signing profile self-test failed.");
    }

    private static Identity selfTest() throws Exception {
        byte[] fixture = new byte[32]; for (int i = 0; i < fixture.length; i++) fixture[i] = (byte) i;
        require(Arrays.equals(fixture, parseSeed(java.util.HexFormat.of().formatHex(fixture))));
        Identity first = identity(fixture), second = identity(fixture);
        require(FIXTURE_CERTIFICATE.equals(fingerprint(first.certificate())));
        require(Arrays.equals(first.certificate().getEncoded(), second.certificate().getEncoded()));
        require(Arrays.equals(first.keys().getPrivate().getEncoded(), second.keys().getPrivate().getEncoded()));
        fixture[0] ^= 0x40; Identity different = identity(fixture);
        require(!fingerprint(first.certificate()).equals(fingerprint(different.certificate())));
        Signature proof = Signature.getInstance("SHA256withECDSA"); proof.initSign(first.keys().getPrivate());
        byte[] message = "signing-profile-fixture".getBytes(StandardCharsets.US_ASCII); proof.update(message); byte[] signed = proof.sign();
        proof.initVerify(first.keys().getPublic()); proof.update(message); require(proof.verify(signed));
        ByteArrayOutputStream encoded = new ByteArrayOutputStream(); store(first).store(encoded, first.password());
        KeyStore restored = KeyStore.getInstance("PKCS12");
        restored.load(new java.io.ByteArrayInputStream(encoded.toByteArray()), first.password());
        require(Arrays.equals(first.certificate().getEncoded(), restored.getCertificate(ALIAS).getEncoded()));
        require(Arrays.equals(first.keys().getPrivate().getEncoded(), restored.getKey(ALIAS, first.password()).getEncoded()));
        for (String bad : new String[] {"", "not-a-seed", "0".repeat(64)}) {
            boolean rejected = false; try { parseSeed(bad); } catch (IllegalArgumentException expected) { rejected = true; }
            require(rejected);
        }
        Arrays.fill(second.password(), '\0'); Arrays.fill(different.password(), '\0'); Arrays.fill(fixture, (byte) 0);
        System.out.println("Signing profile self-test passed: stable certificate, distinct keys, signature, PKCS12 recovery and invalid-input rejection.");
        System.out.println("Public test certificate SHA256: " + fingerprint(first.certificate()));
        return first; // Public fixture only. Never used by the production sign entry point.
    }

    private static String apksigner() {
        String sdk = System.getenv("ANDROID_HOME");
        if (sdk == null) throw new IllegalStateException("Android SDK is required.");
        return Path.of(sdk, "build-tools", "35.0.0", "apksigner").toString();
    }

    private static void sign(Path input, Path output, Identity identity) throws Exception {
        Path directory = Files.createTempDirectory(Path.of(System.getenv().getOrDefault("RUNNER_TEMP", System.getProperty("java.io.tmpdir"))), "polaris-signing-");
        Path keyFile = directory.resolve("signing.p12");
        try {
            Files.createFile(keyFile, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(Set.of(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ, java.nio.file.attribute.PosixFilePermission.OWNER_WRITE)));
            try (OutputStream out = Files.newOutputStream(keyFile)) { store(identity).store(out, identity.password()); }
            ProcessBuilder command = new ProcessBuilder(apksigner(), "sign", "--ks", keyFile.toString(), "--ks-type", "PKCS12",
                "--ks-key-alias", ALIAS, "--v4-signing-enabled", "false", "--ks-pass", "stdin", "--key-pass", "stdin",
                "--out", output.toString(), input.toString()).redirectOutput(ProcessBuilder.Redirect.INHERIT).redirectError(ProcessBuilder.Redirect.INHERIT);
            command.environment().remove("POLARIS_ANDROID_SIGNING_SEED");
            Process process = command.start();
            try (var passwordInput = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {
                passwordInput.write(identity.password()); passwordInput.write('\n');
                passwordInput.write(identity.password()); passwordInput.write('\n');
            }
            if (!process.waitFor(2, TimeUnit.MINUTES)) { process.destroyForcibly(); throw new IllegalStateException("APK signing timed out."); }
            require(process.exitValue() == 0);
        } finally {
            Arrays.fill(identity.password(), '\0'); Files.deleteIfExists(keyFile); Files.deleteIfExists(directory);
        }
        Process verify = new ProcessBuilder(apksigner(), "verify", "--min-sdk-version", "29", "--verbose", "--print-certs", output.toString())
            .redirectError(ProcessBuilder.Redirect.INHERIT).start();
        String publicInfo = new String(verify.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        require(verify.waitFor() == 0 && publicInfo.toLowerCase(java.util.Locale.ROOT).contains(fingerprint(identity.certificate())));
        System.out.println("APK verified for Android 10+; certificate SHA256: " + fingerprint(identity.certificate()));
    }

    public static void main(String[] args) {
        try {
            Security.addProvider(new BouncyCastleProvider());
            if (args.length == 1 && args[0].equals("self-test")) { Arrays.fill(selfTest().password(), '\0'); return; }
            if (args.length == 2 && args[0].equals("self-test-apk")) {
                Path temporary = Files.createTempFile("polaris-public-fixture-", ".apk");
                try { sign(Path.of(args[1]), temporary, selfTest()); }
                finally { Files.deleteIfExists(temporary); }
                return;
            }
            if (args.length != 4 || !args[0].equals("sign")) throw new IllegalArgumentException("Invalid signing command.");
            byte[] seed = parseSeed(System.getenv("POLARIS_ANDROID_SIGNING_SEED"));
            Identity identity;
            try { identity = identity(seed); } finally { Arrays.fill(seed, (byte) 0); }
            Path expected = Path.of(args[3]);
            if (Files.exists(expected) && !MessageDigest.isEqual(Files.readString(expected).trim().getBytes(StandardCharsets.US_ASCII),
                fingerprint(identity.certificate()).getBytes(StandardCharsets.US_ASCII))) {
                Arrays.fill(identity.password(), '\0'); throw new IllegalStateException("The configured signing identity differs from the pinned certificate.");
            }
            sign(Path.of(args[1]), Path.of(args[2]), identity);
        } catch (Exception error) {
            System.err.println("Signing stopped (" + error.getClass().getSimpleName() + "). No secret value is included in this diagnostic.");
            System.exit(1);
        }
    }
}
