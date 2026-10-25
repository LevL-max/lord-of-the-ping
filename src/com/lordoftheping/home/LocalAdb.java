package com.lordoftheping.home;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

/**
 * The TV's own adbd on 127.0.0.1:5555, used for what the firmware refuses to normal apps (closing other apps,
 * reading CPU load). The launcher's RSA key lives in its private files and is approved once on screen.
 */
final class LocalAdb {
    private static final String KEY_NAME = "lord-of-the-ping@tv";

    private LocalAdb() {
    }

    /** Blocking; never call on the UI thread. May show the key prompt through {@code listener}. */
    static String shell(Context context, String command, AdbClient.Listener listener)
            throws IOException, GeneralSecurityException {
        return AdbClient.shell(address(), keys(context), KEY_NAME, command, listener);
    }

    /** Blocking until {@code socket} is closed or the command ends. Never prompts: fails if the key is unknown. */
    static void stream(Context context, Socket socket, String command, AdbClient.Chunks chunks)
            throws IOException, GeneralSecurityException {
        AdbClient.stream(socket, address(), keys(context), KEY_NAME, command, null, chunks);
    }

    private static InetSocketAddress address() {
        return new InetSocketAddress("127.0.0.1", 5555);
    }

    private static synchronized KeyPair keys(Context context) throws IOException, GeneralSecurityException {
        File priv = new File(context.getFilesDir(), "adbkey");
        File pub = new File(context.getFilesDir(), "adbkey.pub");
        KeyFactory rsa = KeyFactory.getInstance("RSA");
        if (priv.exists() && pub.exists()) {
            return new KeyPair(rsa.generatePublic(new X509EncodedKeySpec(Files.readAllBytes(pub.toPath()))),
                    rsa.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(priv.toPath()))));
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        Files.write(priv.toPath(), kp.getPrivate().getEncoded());
        Files.write(pub.toPath(), kp.getPublic().getEncoded());
        return kp;
    }
}
