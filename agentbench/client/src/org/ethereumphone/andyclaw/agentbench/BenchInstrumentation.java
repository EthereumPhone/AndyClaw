package org.ethereumphone.andyclaw.agentbench;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;

import org.ethereumphone.andyclaw.ipc.ILauncherCallback;
import org.ethereumphone.andyclaw.ipc.ILauncherService;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One agentbench operation per {@code am instrument} invocation, so the host gets a blocking,
 * streamed call per prompt:
 *
 * <pre>
 *   am instrument -w -r -e op prompt -e prompt_b64 &lt;base64&gt; -e session s1 -e run r1 \
 *       [-e settings_b64 &lt;base64 json&gt;] [-e timeout_s 300] [-e frames true] \
 *       org.ethereumphone.andyclaw.agentbench/.BenchInstrumentation
 * </pre>
 *
 * Every callback becomes one JSON line in {@code files/runs/<run>/events.jsonl} and one
 * {@code INSTRUMENTATION_STATUS: event=} line on stdout. Ops: {@code prompt}, {@code settings}
 * (apply {@code settings_b64}, print {@code getSettings()}), {@code clear} (drop a session).
 */
public class BenchInstrumentation extends Instrumentation {
    private static final String TAG = "AgentBench";
    private static final ComponentName SERVICE = new ComponentName(
            "org.ethereumphone.andyclaw",
            "org.ethereumphone.andyclaw.services.LauncherBindingService");

    private Bundle mArgs;

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        mArgs = arguments;
        start();
    }

    @Override
    public void onStart() {
        Bundle result = new Bundle();
        int code = Activity.RESULT_OK;
        try {
            result.putString("result", run());
        } catch (Throwable t) {
            Log.e(TAG, "bench op failed", t);
            result.putString("error", String.valueOf(t));
            code = Activity.RESULT_CANCELED;
        }
        finish(code, result);
    }

    private String run() throws Exception {
        final Context context = getContext();
        final ILauncherService service = bind(context);
        applySettings(service);
        String op = mArgs.getString("op", "prompt");
        switch (op) {
            case "settings":
                return service.getSettings();
            case "clear":
                service.clearSession(mArgs.getString("session"));
                return "cleared";
            case "prompt":
                return prompt(context, service);
            default:
                throw new IllegalArgumentException("unknown op " + op);
        }
    }

    private ILauncherService bind(Context context) throws InterruptedException {
        final ILauncherService[] holder = new ILauncherService[1];
        final CountDownLatch connected = new CountDownLatch(1);
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                holder[0] = ILauncherService.Stub.asInterface(binder);
                connected.countDown();
            }
            @Override public void onServiceDisconnected(ComponentName name) {
                Log.w(TAG, "LauncherBindingService disconnected");
            }
        };
        Intent intent = new Intent().setComponent(SERVICE);
        if (!context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
            throw new IllegalStateException("bindService refused: is AndyClaw installed?");
        }
        if (!connected.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("bind timeout");
        return holder[0];
    }

    private void applySettings(ILauncherService service) throws Exception {
        String b64 = mArgs.getString("settings_b64");
        if (b64 == null || b64.isEmpty()) return;
        JSONObject settings = new JSONObject(decode(b64));
        for (Iterator<String> it = settings.keys(); it.hasNext(); ) {
            String key = it.next();
            boolean ok = service.setSetting(key, settings.getString(key));
            if (!ok) throw new IllegalArgumentException("setSetting refused " + key);
        }
    }

    private String prompt(Context context, ILauncherService service) throws Exception {
        final String prompt = decode(mArgs.getString("prompt_b64"));
        final String session = mArgs.getString("session", "agentbench-" + System.currentTimeMillis());
        final String runId = mArgs.getString("run", session);
        final long timeoutS = Long.parseLong(mArgs.getString("timeout_s", "300"));
        final boolean keepFrames = Boolean.parseBoolean(mArgs.getString("frames", "true"));

        final File dir = new File(context.getFilesDir(), "runs/" + runId);
        final File framesDir = new File(dir, "frames");
        if (!framesDir.mkdirs() && !framesDir.isDirectory()) throw new IOException("mkdir " + framesDir);
        final FileOutputStream events = new FileOutputStream(new File(dir, "events.jsonl"));
        final long t0 = SystemClock.elapsedRealtime();
        final AtomicInteger frameCount = new AtomicInteger();
        final CountDownLatch done = new CountDownLatch(1);
        final String[] end = new String[1];

        final Object lock = new Object();
        final class Recorder {
            void emit(JSONObject event) {
                try {
                    event.put("t", SystemClock.elapsedRealtime() - t0);
                    String line = event.toString();
                    synchronized (lock) {
                        events.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                        events.flush();
                    }
                    if (!"token".equals(event.optString("type"))) {
                        Bundle status = new Bundle();
                        status.putString("event", line.length() > 3000 ? line.substring(0, 3000) : line);
                        sendStatus(1, status);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "emit failed", e);
                }
            }
            JSONObject ev(String type) {
                try { return new JSONObject().put("type", type); } catch (Exception e) { throw new RuntimeException(e); }
            }
        }
        final Recorder rec = new Recorder();

        ILauncherCallback callback = new ILauncherCallback.Stub() {
            @Override public void onToken(String text) {
                try { rec.emit(rec.ev("token").put("text", text)); } catch (Exception ignored) { }
            }
            @Override public void onToolExecution(String toolName) {
                try { rec.emit(rec.ev("tool_start").put("tool", toolName)); } catch (Exception ignored) { }
            }
            @Override public void onToolResult(String toolName, String summary, String detail) {
                try {
                    rec.emit(rec.ev("tool_result").put("tool", toolName)
                            .put("summary", summary).put("detail", detail));
                } catch (Exception ignored) { }
            }
            @Override public void onComplete(String fullText) {
                try { rec.emit(rec.ev("complete").put("text", fullText)); } catch (Exception ignored) { }
                end[0] = "complete";
                done.countDown();
            }
            @Override public void onError(String message) {
                try { rec.emit(rec.ev("error").put("message", message)); } catch (Exception ignored) { }
                end[0] = "error";
                done.countDown();
            }
            @Override public void onTranscription(String text) { }
            @Override public void onDisplayCreated() { rec.emit(rec.ev("display_created")); }
            @Override public void onDisplayFrame(byte[] jpeg) {
                int n = frameCount.incrementAndGet();
                if (!keepFrames) return;
                String name = String.format("%04d_%06d.jpg", n, SystemClock.elapsedRealtime() - t0);
                try (FileOutputStream out = new FileOutputStream(new File(framesDir, name))) {
                    out.write(jpeg);
                } catch (IOException e) {
                    Log.w(TAG, "frame write failed", e);
                }
            }
            @Override public void onDisplayDestroyed() { rec.emit(rec.ev("display_destroyed")); }
            @Override public void onAgentStep(String json) {
                try { rec.emit(rec.ev("agent_step").put("step", new JSONObject(json))); } catch (Exception ignored) { }
            }
        };

        rec.emit(rec.ev("prompt").put("prompt", prompt).put("session", session));
        service.sendPrompt(prompt, session, callback);
        if (!done.await(timeoutS, TimeUnit.SECONDS)) {
            end[0] = "timeout";
            rec.emit(rec.ev("timeout").put("after_s", timeoutS));
            try { service.stopInference(session); } catch (Exception ignored) { }
            done.await(15, TimeUnit.SECONDS);
        }
        rec.emit(rec.ev("end").put("outcome", end[0]).put("frames", frameCount.get()));
        events.close();
        return end[0];
    }

    private static String decode(String b64) {
        return new String(Base64.decode(b64, Base64.DEFAULT), StandardCharsets.UTF_8);
    }
}
