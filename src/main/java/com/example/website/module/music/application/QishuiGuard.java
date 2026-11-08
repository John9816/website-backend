package com.example.website.module.music.application;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.*;
import java.util.*;

import javax.security.auth.x500.X500Principal;

/** P-256 / SHA-256 ECDSA signatures in ASN.1 DER, and a PKCS#10 CSR. */
final class QishuiGuard {
    private final PrivateKey privateKey;
    private final PublicKey publicKey;

    private QishuiGuard(PrivateKey privateKey, PublicKey publicKey) {
        this.privateKey = privateKey;
        this.publicKey = publicKey;
    }

    static QishuiGuard create() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
            gen.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair pair = gen.generateKeyPair();
            return new QishuiGuard(pair.getPrivate(), pair.getPublic());
        } catch (GeneralSecurityException ex) {
            throw QishuiHttpClient.failure();
        }
    }

    static QishuiGuard restore(String scalar) {
        try {
            if (scalar == null || !scalar.matches("[0-9a-fA-F]{64}"))
                throw new IllegalArgumentException();
            AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
            params.init(new ECGenParameterSpec("secp256r1"));
            ECParameterSpec spec = params.getParameterSpec(ECParameterSpec.class);
            BigInteger d = new BigInteger(scalar, 16);
            if (d.signum() <= 0 || d.compareTo(spec.getOrder()) >= 0)
                throw new IllegalArgumentException();
            KeyFactory factory = KeyFactory.getInstance("EC");
            return new QishuiGuard(
                    factory.generatePrivate(new ECPrivateKeySpec(d, spec)),
                    factory.generatePublic(new ECPublicKeySpec(multiply(spec, d), spec)));
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw QishuiHttpClient.failure();
        }
    }

    String privateScalar() {
        return String.format(Locale.ROOT, "%064x", ((ECPrivateKey) privateKey).getS());
    }

    PublicKey publicKey() {
        return publicKey;
    }

    private byte[] sign(byte[] bytes) throws GeneralSecurityException {
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(privateKey);
        signer.update(bytes);
        return signer.sign();
    }

    String csr() {
        try {
            byte[] info =
                    der(
                            0x30,
                            der(2, new byte[] {0}),
                            new X500Principal("CN=bd-ticket-guard").getEncoded(),
                            publicKey.getEncoded(),
                            der(0xa0, new byte[0]));
            byte[] algorithm =
                    der(
                            0x30,
                            der(
                                    6,
                                    new byte[] {
                                        0x2a, (byte) 0x86, 0x48, (byte) 0xce, 0x3d, 4, 3, 2
                                    }));
            byte[] csr = der(0x30, info, algorithm, der(3, new byte[] {0}, sign(info)));
            String pem =
                    "-----BEGIN CERTIFICATE REQUEST-----\n"
                            + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(csr)
                            + "\n-----END CERTIFICATE REQUEST-----\n";
            return Base64.getEncoder().encodeToString(pem.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException ex) {
            throw QishuiHttpClient.failure();
        }
    }

    Map<String, String> headers(QishuiCredential credential, ObjectMapper mapper, long timestamp) {
        try {
            String content =
                    "ticket="
                            + credential.getTicket()
                            + "&path=/api/luna/v1/platform/me/info/&timestamp="
                            + timestamp;
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("ts_sign", credential.getGuardTsSign());
            data.put("req_content", "ticket,path,timestamp");
            data.put(
                    "req_sign",
                    Base64.getEncoder()
                            .encodeToString(sign(content.getBytes(StandardCharsets.UTF_8))));
            data.put("timestamp", timestamp);
            Map<String, String> h = new LinkedHashMap<>();
            h.put("access-token", credential.getTicket());
            h.put("bd-ticket-guard-version", "1");
            h.put("bd-ticket-guard-client-cert", credential.getGuardCert());
            h.put(
                    "bd-ticket-guard-client-data",
                    Base64.getEncoder().encodeToString(mapper.writeValueAsBytes(data)));
            return h;
        } catch (java.io.IOException | GeneralSecurityException ex) {
            throw QishuiHttpClient.failure();
        }
    }

    private static byte[] der(int tag, byte[]... values) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int n = 0;
        for (byte[] v : values) n += v.length;
        out.write(tag);
        if (n < 128) out.write(n);
        else {
            int count = 0;
            for (int v = n; v != 0; v >>>= 8) count++;
            out.write(0x80 | count);
            for (int i = count - 1; i >= 0; i--) out.write(n >>> (8 * i));
        }
        for (byte[] v : values) out.write(v, 0, v.length);
        return out.toByteArray();
    }

    // Recover the public point for a persisted scalar. Signing itself uses the JCA provider.
    private static ECPoint multiply(ECParameterSpec spec, BigInteger d) {
        ECPoint result = null, point = spec.getGenerator();
        for (int i = 0; i < d.bitLength(); i++) {
            if (d.testBit(i)) result = add(spec, result, point);
            point = add(spec, point, point);
        }
        return result;
    }

    private static ECPoint add(ECParameterSpec s, ECPoint a, ECPoint b) {
        if (a == null) return b;
        if (b == null) return a;
        BigInteger p = ((ECFieldFp) s.getCurve().getField()).getP();
        BigInteger x = a.getAffineX(), y = a.getAffineY(), u = b.getAffineX(), v = b.getAffineY();
        if (x.equals(u) && y.add(v).mod(p).signum() == 0) return null;
        BigInteger slope =
                x.equals(u)
                        ? x.multiply(x)
                                .multiply(BigInteger.valueOf(3))
                                .add(s.getCurve().getA())
                                .multiply(y.shiftLeft(1).mod(p).modInverse(p))
                                .mod(p)
                        : v.subtract(y).multiply(u.subtract(x).mod(p).modInverse(p)).mod(p);
        BigInteger nx = slope.multiply(slope).subtract(x).subtract(u).mod(p);
        return new ECPoint(nx, slope.multiply(x.subtract(nx)).subtract(y).mod(p));
    }
}
