package com.lordoftheping.home;

import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Wakes a sleeping Cast device on the home network (the SHIELD) the way a phone casting to it would: launch the
 * Default Media Receiver, stop it as soon as the device is up. The SHIELD then turns the TV on over CEC.
 * No keys or pairing: Cast on the LAN is open by design. A device that is already awake is left alone.
 */
final class CastWake {
    private static final int PORT = 8009;
    private static final String DEFAULT_RECEIVER = "CC1AD845";
    private static final String NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection";
    private static final String NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat";
    private static final String NS_RECEIVER = "urn:x-cast:com.google.cast.receiver";
    private static final long TIMEOUT_MS = 12000;

    /** Cast devices present their own certificate chain; this only sends a launch and a stop. */
    private static final TrustManager TRUST_ANY = new X509TrustManager() {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String type) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String type) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    };

    private CastWake() {
    }

    /** Whether the device sleeps, from one status request. Blocking, a few seconds at most; throws if unreachable. */
    static boolean isStandBy(String host) throws IOException, GeneralSecurityException {
        try (SSLSocket s = open(host)) {
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            DataInputStream in = new DataInputStream(s.getInputStream());
            send(out, NS_CONNECTION, "{\"type\":\"CONNECT\"}");
            send(out, NS_RECEIVER, "{\"type\":\"GET_STATUS\",\"requestId\":1}");
            long end = SystemClock.uptimeMillis() + 5000;
            while (SystemClock.uptimeMillis() < end) {
                String p = read(in);
                if (p != null && p.contains("\"isStandBy\"")) return p.contains("\"isStandBy\":true");
            }
            throw new IOException("no status from " + host);
        }
    }

    /** Blocking, up to ~12 s; never call on the UI thread. Returns whether the device was asleep and got woken. */
    static boolean wakeIfAsleep(String host) throws IOException, GeneralSecurityException {
        try (SSLSocket s = open(host)) {
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            DataInputStream in = new DataInputStream(s.getInputStream());
            send(out, NS_CONNECTION, "{\"type\":\"CONNECT\"}");
            send(out, NS_RECEIVER, "{\"type\":\"GET_STATUS\",\"requestId\":1}");
            boolean launched = false;
            long end = SystemClock.uptimeMillis() + TIMEOUT_MS;
            while (SystemClock.uptimeMillis() < end) {
                String p = read(in);
                if (p == null) continue;
                if (p.contains("\"PING\"")) {
                    send(out, NS_HEARTBEAT, "{\"type\":\"PONG\"}");
                    continue;
                }
                if (!p.contains("\"isStandBy\"")) continue; // only receiver status tells the power state
                boolean standby = p.contains("\"isStandBy\":true");
                if (!launched) {
                    if (!standby) return false;
                    send(out, NS_RECEIVER, "{\"type\":\"LAUNCH\",\"appId\":\"" + DEFAULT_RECEIVER + "\",\"requestId\":2}");
                    launched = true;
                    continue;
                }
                String session = value(p, "sessionId");
                if (session != null && !standby) {
                    send(out, NS_RECEIVER, "{\"type\":\"STOP\",\"sessionId\":\"" + session + "\",\"requestId\":3}");
                    return true;
                }
            }
            return launched;
        }
    }

    private static SSLSocket open(String host) throws IOException, GeneralSecurityException {
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(null, new TrustManager[]{TRUST_ANY}, null);
        SSLSocket s = (SSLSocket) tls.getSocketFactory().createSocket();
        try {
            s.connect(new InetSocketAddress(host, PORT), 3000);
            s.setSoTimeout(3000);
            return s;
        } catch (IOException e) {
            s.close();
            throw e;
        }
    }

    /** One CastMessage: protocol 0, sender-0 → receiver-0, string payload; length-prefixed protobuf. */
    private static void send(DataOutputStream out, String namespace, String json) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(0x08);
        b.write(0);
        field(b, 0x12, "sender-0");
        field(b, 0x1a, "receiver-0");
        field(b, 0x22, namespace);
        b.write(0x28);
        b.write(0);
        field(b, 0x32, json);
        out.writeInt(b.size());
        b.writeTo(out);
        out.flush();
    }

    private static void field(ByteArrayOutputStream b, int tag, String s) {
        byte[] v = s.getBytes(StandardCharsets.UTF_8);
        b.write(tag);
        int n = v.length;
        while (n >= 0x80) {
            b.write((n & 0x7f) | 0x80);
            n >>>= 7;
        }
        b.write(n);
        b.write(v, 0, v.length);
    }

    /** The JSON payload of the next message, or null if nothing arrived in time. */
    private static String read(DataInputStream in) throws IOException {
        byte[] m;
        try {
            m = new byte[in.readInt()];
        } catch (SocketTimeoutException e) {
            return null;
        }
        in.readFully(m);
        String s = new String(m, StandardCharsets.UTF_8);
        int i = s.indexOf('{');
        return i < 0 ? "" : s.substring(i);
    }

    private static String value(String json, String key) {
        int k = json.indexOf("\"" + key + "\":\"");
        if (k < 0) return null;
        int from = k + key.length() + 4;
        int to = json.indexOf('"', from);
        return to < 0 ? null : json.substring(from, to);
    }
}
