package com.lordoftheping.home;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

/** The adb wire protocol, just enough to authenticate with an RSA key and run one shell command. Plain Java. */
final class AdbClient {
    interface Listener {
        /** adbd asked the user to approve our key ("Allow USB debugging?"). Called on the calling thread. */
        void needApproval();
    }

    /** Receives a command's output as it arrives. */
    interface Chunks {
        void accept(byte[] data) throws IOException;
    }

    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f;
    private static final int OKAY = 0x59414b4f, CLSE = 0x45534c43, WRTE = 0x45545257;
    private static final int VERSION = 0x01000001, MAX_PAYLOAD = 256 * 1024;
    private static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;
    private static final int KEY_WORDS = 64; // 2048-bit modulus as 32-bit words
    /** DER prefix of a SHA-1 DigestInfo: adbd treats its 20-byte token as a SHA-1 digest. */
    private static final byte[] SHA1_DIGEST_INFO = {
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};

    private static final class Message {
        int command, arg0;
        byte[] data;
    }

    private AdbClient() {
    }

    /** Runs {@code command} through "shell:" and returns its output. Blocking. */
    static String shell(InetSocketAddress address, KeyPair keys, String keyName, String command, Listener listener)
            throws IOException, GeneralSecurityException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Socket socket = new Socket()) {
            run(socket, address, keys, keyName, command, listener, output::write);
        }
        return output.toString("UTF-8");
    }

    /**
     * Runs a long-lived command, handing its output to {@code chunks} until it ends. Close {@code socket}
     * from another thread to stop it. With a null listener an unknown key fails instead of prompting the user.
     */
    static void stream(Socket socket, InetSocketAddress address, KeyPair keys, String keyName, String command,
                       Listener listener, Chunks chunks) throws IOException, GeneralSecurityException {
        try {
            run(socket, address, keys, keyName, command, listener, chunks);
        } finally {
            socket.close();
        }
    }

    private static void run(Socket socket, InetSocketAddress address, KeyPair keys, String keyName, String command,
                            Listener listener, Chunks chunks) throws IOException, GeneralSecurityException {
        socket.connect(address, 3000);
        socket.setSoTimeout(10000);
        DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        OutputStream out = new BufferedOutputStream(socket.getOutputStream());

        send(out, CNXN, VERSION, MAX_PAYLOAD, "host::\0".getBytes(StandardCharsets.US_ASCII));
        boolean signed = false, offeredKey = false;
        for (Message m = read(in); m.command != CNXN; m = read(in)) {
            if (m.command != AUTH || m.arg0 != AUTH_TOKEN) throw new IOException("unexpected adb reply");
            if (!signed) {
                send(out, AUTH, AUTH_SIGNATURE, 0, sign(keys.getPrivate(), m.data));
                signed = true;
            } else if (!offeredKey && listener != null) {
                // The signature was not accepted: offer the public key, the TV asks the user.
                send(out, AUTH, AUTH_RSAPUBLICKEY, 0,
                        (publicKey(keys.getPublic()) + " " + keyName + "\0").getBytes(StandardCharsets.US_ASCII));
                offeredKey = true;
                socket.setSoTimeout(120000);
                listener.needApproval();
            } else {
                throw new IOException("the key was not accepted");
            }
        }

        socket.setSoTimeout(30000);
        final int local = 1;
        send(out, OPEN, local, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8));
        while (true) {
            Message m = read(in);
            if (m.command == WRTE) {
                chunks.accept(m.data);
                send(out, OKAY, local, m.arg0, null);
            } else if (m.command == CLSE) {
                send(out, CLSE, local, m.arg0, null);
                return;
            }
            // OKAY only confirms the stream or our acknowledgements.
        }
    }

    /** adbd's key format, base64: len, n0inv, n[], rr[], exponent, all little endian. */
    static String publicKey(PublicKey key) {
        RSAPublicKey rsa = (RSAPublicKey) key;
        BigInteger n = rsa.getModulus();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + KEY_WORDS * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(KEY_WORDS);
        b.putInt(r32.subtract(n.mod(r32).modInverse(r32)).intValue());
        putWords(b, n);
        putWords(b, BigInteger.ONE.shiftLeft(32 * KEY_WORDS * 2).mod(n));
        b.putInt(rsa.getPublicExponent().intValue());
        return Base64.getEncoder().encodeToString(b.array());
    }

    private static void putWords(ByteBuffer b, BigInteger v) {
        BigInteger mask = BigInteger.ONE.shiftLeft(32).subtract(BigInteger.ONE);
        for (int i = 0; i < KEY_WORDS; i++) b.putInt(v.shiftRight(32 * i).and(mask).intValue());
    }

    private static byte[] sign(PrivateKey key, byte[] token) throws GeneralSecurityException {
        Signature s = Signature.getInstance("NONEwithRSA");
        s.initSign(key);
        s.update(SHA1_DIGEST_INFO);
        s.update(token);
        return s.sign();
    }

    private static Message read(DataInputStream in) throws IOException {
        byte[] header = new byte[24];
        in.readFully(header);
        ByteBuffer b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        Message m = new Message();
        m.command = b.getInt();
        m.arg0 = b.getInt();
        b.getInt(); // arg1
        int length = b.getInt();
        if (length < 0 || length > MAX_PAYLOAD) throw new IOException("bad adb message length " + length);
        m.data = new byte[length];
        in.readFully(m.data);
        return m;
    }

    private static void send(OutputStream out, int command, int arg0, int arg1, byte[] data) throws IOException {
        int length = data == null ? 0 : data.length;
        int sum = 0;
        for (int i = 0; i < length; i++) sum += data[i] & 0xff;
        ByteBuffer b = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(command).putInt(arg0).putInt(arg1).putInt(length).putInt(sum).putInt(~command);
        out.write(b.array());
        if (length > 0) out.write(data);
        out.flush();
    }
}
