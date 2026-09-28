package org.ethereumphone.agentbench;

import android.app.ActivityThread;
import android.content.Context;
import android.os.Looper;
import android.os.ServiceManager;
import android.util.Slog;

import com.android.server.AgentDisplayService;

/**
 * Hosts the ethOS {@code AgentDisplayService} — the real source from
 * {@code frameworks/base/services/java/com/android/server/AgentDisplayService.java}, compiled
 * unmodified — in an {@code app_process} running as the system uid on a stock userdebug
 * emulator, and publishes it as {@code agentdisplay} exactly as {@code SystemServer} does.
 *
 * <pre>
 *   adb push agentdisplayd.jar /data/local/tmp/
 *   adb shell su 1000 app_process -Djava.class.path=/data/local/tmp/agentdisplayd.jar \
 *       /system/bin org.ethereumphone.agentbench.AgentDisplayDaemon
 * </pre>
 *
 * Needs SELinux permissive: servicemanager must let an {@code su}-domain process add a
 * service and let {@code priv_app} find it.
 */
public final class AgentDisplayDaemon {
    private static final String TAG = "AgentDisplayDaemon";

    public static void main(String[] args) {
        // In system_server an uncaught exception on one of the service's threads is a runtime
        // restart of the whole OS; here it would just end the process with nothing said. Say it.
        Thread.setDefaultUncaughtExceptionHandler((thread, e) -> {
            Slog.wtf(TAG, "UNCAUGHT on " + thread.getName() + " — in system_server this restarts the OS", e);
            System.out.println("agentdisplayd: UNCAUGHT on " + thread.getName());
            e.printStackTrace(System.out);
            System.out.flush();
            System.exit(10);
        });
        Looper.prepareMainLooper();
        ActivityThread thread = ActivityThread.systemMain();
        Context context = new DaemonContext(thread.getSystemContext());
        AgentDisplayService service = new AgentDisplayService(context);
        ServiceManager.addService("agentdisplay", new StockImageGuard(service));
        Slog.i(TAG, "agentdisplay published from app_process (uid " + android.os.Process.myUid() + ")");
        System.out.println("agentdisplayd: ready");
        Looper.loop();
    }
}
