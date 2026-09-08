package dev.rios.fold8spoof;

import android.app.Service;
import android.content.Intent;
import android.content.res.AssetManager;
import android.os.IBinder;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Hosts llama-server (CPU) on localhost for notification summaries. No root needed. */
public final class LlmServerService extends Service {
    private static final String TAG = "Fold8LlmServer";
    public static final int PORT = 18089;
    private static final String[] BINARIES = {
            "llama-server",
            "libllama-server-impl.so",
            "libllama.so",
            "libllama-common.so",
            "libggml-base.so",
            "libggml-cpu.so",
            "libggml.so",
            "libmtmd.so",
            "libc++_shared.so",
            "libssl.so.3",
            "libcrypto.so.3",
    };

    private Process proc;
    private android.os.Handler watchdogHandler;

    private void note(String msg) {
        android.util.Log.i(TAG, msg);
        try {
            File dir = new File(getFilesDir(), "llm");
            dir.mkdirs();
            File log = new File(dir, "status.log");
            FileOutputStream fos = new FileOutputStream(log, true);
            String line = new java.util.Date() + " " + msg + "\n";
            fos.write(line.getBytes("UTF-8"));
            fos.close();
        } catch (Exception ignored) {
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ensureStarted();
        if (watchdogHandler == null) {
            watchdogHandler = new android.os.Handler(getMainLooper());
            final Runnable check = new Runnable() {
                @Override
                public void run() {
                    ensureStarted();
                    if (watchdogHandler != null) {
                        watchdogHandler.postDelayed(this, 60000);
                    }
                }
            };
            watchdogHandler.postDelayed(check, 60000);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (proc != null) {
            proc.destroy();
            proc = null;
        }
        super.onDestroy();
    }

    private synchronized void ensureStarted() {
        if (proc != null) {
            try {
                proc.exitValue();
            } catch (IllegalThreadStateException alive) {
                return;
            }
            note("previous server died, restarting");
            proc = null;
        }
        try {
            File dir = new File(getFilesDir(), "llm");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                Log.e(TAG, "cannot create " + dir);
                return;
            }
            AssetManager am = getAssets();
            for (String name : BINARIES) {
                File out = new File(dir, name);
                if (!out.exists() || out.length() == 0) {
                    copyAsset(am, "llm/" + name, out);
                }
                // Android 15 W^X: executables must not be writable.
                execChmod(out, "555");
            }
            File model = new File(dir, "model.gguf");
            execChmod(model, "444");
            if (!model.exists()) {
                note("model.gguf missing in " + dir + ", server not started");
                return;
            }
            if (isServing()) {
                note("another server already on port " + PORT + ", staying idle");
                return;
            }
            ProcessBuilder pb = new ProcessBuilder(
                    new File(dir, "llama-server").getAbsolutePath(),
                    "-m", model.getAbsolutePath(),
                    "--host", "127.0.0.1",
                    "--port", String.valueOf(PORT),
                    "-c", "2048",
                    "-t", "6",
                    "-n", "160",
                    "--no-webui");
            pb.environment().put("LD_LIBRARY_PATH", dir.getAbsolutePath());
            pb.redirectErrorStream(true);
            proc = pb.start();
            final InputStream in = proc.getInputStream();
            Thread drainer = new Thread(new Runnable() {
                @Override
                public void run() {
                    byte[] buf = new byte[8192];
                    try {
                        while (in.read(buf) >= 0) {
                        }
                    } catch (IOException ignored) {
                    }
                }
            });
            drainer.setDaemon(true);
            drainer.start();
            note("llama-server started on port " + PORT);
        } catch (Exception e) {
            note("server start failed: " + e);
        }
    }

    private static void copyAsset(AssetManager am, String asset, File out) throws IOException {
        InputStream in = null;
        OutputStream os = null;
        try {
            in = am.open(asset);
            os = new FileOutputStream(out);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) >= 0) {
                os.write(buf, 0, n);
            }
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
            if (os != null) {
                try {
                    os.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static boolean isServing() {
        java.net.Socket s = null;
        try {
            s = new java.net.Socket();
            s.connect(new java.net.InetSocketAddress("127.0.0.1", PORT), 2000);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (s != null) {
                try {
                    s.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static void execChmod(File f, String mode) {
        try {
            new ProcessBuilder("chmod", mode, f.getAbsolutePath()).start().waitFor();
        } catch (Exception ignored) {
        }
    }
}
