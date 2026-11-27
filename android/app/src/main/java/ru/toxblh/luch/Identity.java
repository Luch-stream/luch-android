// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;
import android.security.KeyPairGeneratorSpec;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.math.BigInteger;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.Calendar;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;
import javax.security.auth.x500.X500Principal;

/**
 * This receiver's own TLS identity: a self-signed certificate whose key stays in the Android keystore (made once, it
 * never leaves the TV). Senders pin its fingerprint when they pair; the receiver pins theirs. There is no CA: a
 * certificate is trusted for being the one that was paired, so any client certificate is let through the handshake
 * and judged by its fingerprint afterwards (Gate).
 */
final class Identity {
    private static final String KEYSTORE = "AndroidKeyStore";
    static final String ALIAS = "luch-receiver";

    final PrivateKey key;
    final X509Certificate cert;
    final byte[] fingerprint;

    private Identity(PrivateKey key, X509Certificate cert) throws CertificateEncodingException {
        this.key = key;
        this.cert = cert;
        this.fingerprint = fingerprint(cert);
    }

    /** The identity under {@code alias}, made on first use (EC P-256 from Android 6, RSA 2048 before). */
    static Identity load(Context context, String alias) throws GeneralSecurityException, java.io.IOException {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        if (!ks.containsAlias(alias)) generate(context, alias);
        PrivateKey key = (PrivateKey) ks.getKey(alias, null);
        Certificate cert = ks.getCertificate(alias);
        if (key == null || !(cert instanceof X509Certificate)) throw new GeneralSecurityException("no key " + alias);
        return new Identity(key, (X509Certificate) cert);
    }

    @SuppressWarnings("deprecation")   // KeyPairGeneratorSpec: the only way on Android 5
    private static void generate(Context context, String alias) throws GeneralSecurityException {
        Calendar from = Calendar.getInstance();
        from.add(Calendar.DAY_OF_YEAR, -1);   // a TV whose clock is a little behind still takes it
        Calendar to = Calendar.getInstance();
        to.add(Calendar.YEAR, 30);
        X500Principal subject = new X500Principal("CN=Luch receiver");
        BigInteger serial = new BigInteger(63, new SecureRandom()).add(BigInteger.ONE);
        if (Build.VERSION.SDK_INT >= 23) {
            KeyPairGenerator g = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE);
            g.initialize(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                    // TLS asks the keystore to sign a digest it made itself: NONE as well as the named ones.
                    .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384,
                            KeyProperties.DIGEST_SHA512)
                    .setCertificateSubject(subject)
                    .setCertificateSerialNumber(serial)
                    .setCertificateNotBefore(from.getTime())
                    .setCertificateNotAfter(to.getTime())
                    .build());
            g.generateKeyPair();
        } else {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA", KEYSTORE);
            g.initialize(new KeyPairGeneratorSpec.Builder(context)
                    .setAlias(alias)
                    .setKeySize(2048)
                    .setSubject(subject)
                    .setSerialNumber(serial)
                    .setStartDate(from.getTime())
                    .setEndDate(to.getTime())
                    .build());
            g.generateKeyPair();
        }
    }

    /** SHA-256 of the certificate as sent in the handshake (DER): what both sides pin. */
    static byte[] fingerprint(X509Certificate cert) throws CertificateEncodingException {
        return Pairing.sha256().digest(cert.getEncoded());
    }

    /** TLS 1.2 or 1.3 with this identity, asking every client for its certificate. */
    SSLContext tls() throws GeneralSecurityException {
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(new KeyManager[]{keyManager(key, cert)}, new TrustManager[]{new AnyCertificate()}, null);
        return ctx;
    }

    /** One key, one certificate: whatever the other side asks for. */
    static X509KeyManager keyManager(PrivateKey key, X509Certificate cert) {
        return new X509KeyManager() {
            @Override
            public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
                return ALIAS;
            }

            @Override
            public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
                return ALIAS;
            }

            @Override
            public X509Certificate[] getCertificateChain(String alias) {
                return new X509Certificate[]{cert};
            }

            @Override
            public String[] getClientAliases(String keyType, Principal[] issuers) {
                return new String[]{ALIAS};
            }

            @Override
            public String[] getServerAliases(String keyType, Principal[] issuers) {
                return new String[]{ALIAS};
            }

            @Override
            public PrivateKey getPrivateKey(String alias) {
                return key;
            }
        };
    }

    /**
     * Lets any one certificate through the handshake: the handshake itself proves the peer holds its key, and whether
     * that key is a paired one is decided on its fingerprint, not by a CA (there is none on a LAN).
     */
    @SuppressLint({"TrustAllX509TrustManager", "CustomX509TrustManager"})
    static class AnyCertificate implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            if (chain == null || chain.length == 0) throw new CertificateException("no certificate");
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            if (chain == null || chain.length == 0) throw new CertificateException("no certificate");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
