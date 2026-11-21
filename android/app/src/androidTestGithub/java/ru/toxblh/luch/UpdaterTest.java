// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Self-update from GitHub releases (only in the "github" build: F-Droid and Play update apps themselves). */
@RunWith(AndroidJUnit4.class)
public class UpdaterTest {
    private Context context;
    private Server server;

    @Before
    public void setUp() throws IOException {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        server = new Server();
    }

    @After
    public void tearDown() {
        server.close();
    }

    @Test
    public void onlyTheGithubBuildUpdatesItself() {
        assertTrue(Updater.enabled());
    }

    @Test
    public void versionsCompareByNumber() {
        assertTrue(Updater.compare("0.3", "0.2") > 0);
        assertTrue(Updater.compare("0.10", "0.9") > 0);
        assertTrue(Updater.compare("0.2.1", "0.2") > 0);
        assertEquals(0, Updater.compare("v1.0", "1.0"));
        assertEquals(0, Updater.compare("1.0", "1.0.0"));
        assertTrue(Updater.compare("0.2", "0.3") < 0);
    }

    @Test
    public void theReleaseApkIsPickedFromTheRelease() throws Exception {
        Updater.Release r = Updater.parse(release("v0.3", false, false, asset("luch-release.apk", "sha256:" + hex(32)),
                asset("luch-debug.apk", "sha256:" + hex(32))));
        assertNotNull(r);
        assertEquals("0.3", r.version);
        assertTrue(r.url.endsWith("/luch-release.apk"));
        assertEquals(hex(32), r.sha256);
    }

    @Test
    public void releasesWithoutASignedApkOrNotFinalAreIgnored() throws Exception {
        assertNull(Updater.parse(release("v0.3", false, false, asset("luch-debug.apk", "sha256:" + hex(32)))));
        assertNull(Updater.parse(release("v0.3", true, false, asset("luch-release.apk", "sha256:" + hex(32)))));
        assertNull(Updater.parse(release("v0.3", false, true, asset("luch-release.apk", "sha256:" + hex(32)))));
        // Without a digest there is nothing to check the download against.
        assertNull(Updater.parse(release("v0.3", false, false, asset("luch-release.apk", null))));
    }

    @Test
    public void aNewerReleaseIsFoundAndTheSameOneIsNot() throws Exception {
        server.put("/latest", release("v99.0", false, false, asset("luch-release.apk", "sha256:" + hex(32))));
        Updater.Release r = Updater.check(context, server.url("/latest"));
        assertNotNull(r);
        assertEquals("99.0", r.version);

        server.put("/latest", release("v" + Updater.currentVersion(context), false, false,
                asset("luch-release.apk", "sha256:" + hex(32))));
        assertNull(Updater.check(context, server.url("/latest")));
    }

    @Test
    public void aDownloadWithTheRightDigestAndSignerIsAccepted() throws Exception {
        byte[] apk = Compat.readFile(new File(context.getApplicationInfo().sourceDir));
        server.put("/a.apk", apk);
        Updater.Release r = new Updater.Release("99.0", server.url("/a.apk"), sha256(apk));
        File f = Updater.download(context, r);
        assertTrue(Updater.sameSigner(context, f));
    }

    @Test
    public void aDownloadWithTheWrongDigestIsRefused() throws Exception {
        byte[] apk = Compat.readFile(new File(context.getApplicationInfo().sourceDir));
        server.put("/a.apk", apk);
        Updater.Release r = new Updater.Release("99.0", server.url("/a.apk"), hex(32));
        try {
            Updater.download(context, r);
            fail("accepted an APK whose digest does not match");
        } catch (IOException expected) {
            assertTrue(String.valueOf(expected.getMessage()), String.valueOf(expected.getMessage()).contains("digest"));
        }
    }

    @Test
    public void anApkSignedBySomeoneElseIsRefused() throws Exception {
        ApplicationInfo other = context.getPackageManager().getApplicationInfo("com.android.shell", 0);
        assertFalse(Updater.sameSigner(context, new File(other.sourceDir)));
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private static JSONObject release(String tag, boolean draft, boolean prerelease, JSONObject... assets)
            throws Exception {
        JSONArray a = new JSONArray();
        for (JSONObject x : assets) a.put(x);
        return new JSONObject().put("tag_name", tag).put("draft", draft).put("prerelease", prerelease)
                .put("assets", a);
    }

    private static JSONObject asset(String name, String digest) throws Exception {
        JSONObject o = new JSONObject().put("name", name)
                .put("browser_download_url", "https://github.com/Toxblh/luch-android/releases/download/v0.3/" + name)
                .put("size", 123);
        if (digest != null) o.put("digest", digest);
        return o;
    }

    private static String hex(int bytes) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < bytes; i++) b.append("ab");
        return b.toString();
    }

    private static String sha256(byte[] data) throws Exception {
        StringBuilder b = new StringBuilder();
        for (byte x : MessageDigest.getInstance("SHA-256").digest(data)) b.append(String.format("%02x", x));
        return b.toString();
    }

    /** A tiny HTTP server on localhost standing in for GitHub. */
    private static final class Server {
        private final ServerSocket socket = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        private final Map<String, byte[]> files = new ConcurrentHashMap<>();

        Server() throws IOException {
            Thread t = new Thread(() -> {
                while (!socket.isClosed()) {
                    try (Socket s = socket.accept()) {
                        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(),
                                StandardCharsets.ISO_8859_1));
                        String path = in.readLine().split(" ")[1];
                        for (String l; (l = in.readLine()) != null && !l.isEmpty(); ) {
                            // headers
                        }
                        byte[] body = files.get(path);
                        OutputStream out = s.getOutputStream();
                        String head = body == null ? "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n"
                                : "HTTP/1.1 200 OK\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
                        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
                        if (body != null) out.write(body);
                        out.flush();
                    } catch (IOException ignored) {
                        // next one
                    }
                }
            }, "test-http");
            t.setDaemon(true);
            t.start();
        }

        void put(String path, JSONObject json) {
            files.put(path, json.toString().getBytes(StandardCharsets.UTF_8));
        }

        void put(String path, byte[] data) {
            files.put(path, data);
        }

        String url(String path) {
            return "http://127.0.0.1:" + socket.getLocalPort() + path;
        }

        void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
