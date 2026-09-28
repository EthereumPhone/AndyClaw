package org.ethereumphone.agentbench;

import android.os.IAgentDisplayService;
import android.os.ServiceManager;

import java.io.FileOutputStream;

/**
 * Exercises agentdisplayd without a model: create → launch Settings → settle → read the tree →
 * tap → capture → destroy. Run as AndyClaw's uid, since the service admits nobody else:
 *
 * <pre>
 *   adb shell run-as org.ethereumphone.andyclaw app_process \
 *       -Djava.class.path=/data/local/tmp/agentdisplayd.jar /system/bin \
 *       org.ethereumphone.agentbench.AgentDisplaySelfTest /data/local/tmp/selftest.png
 * </pre>
 */
public final class AgentDisplaySelfTest {
    public static void main(String[] args) throws Exception {
        IAgentDisplayService s = IAgentDisplayService.Stub.asInterface(ServiceManager.getService("agentdisplay"));
        if (s == null) throw new IllegalStateException("agentdisplay not published");
        long t0 = System.currentTimeMillis();
        System.out.println("apiVersion=" + s.getAgentApiVersion());
        s.createAgentDisplay(720, 720, 240);
        System.out.println("displayId=" + s.getDisplayId());
        s.launchApp("com.android.settings");
        System.out.println("waitForIdle " + s.waitForIdle(800, 8000));
        System.out.println("currentActivity=" + s.getCurrentActivity());
        String tree = s.getAccessibilityTree();
        System.out.println("tree chars=" + (tree == null ? -1 : tree.length()) + " head="
                + (tree == null ? "null" : tree.substring(0, Math.min(300, tree.length())).replace('\n', ' ')));
        s.tap(360, 400);
        System.out.println("after tap " + s.waitForIdle(800, 8000));
        System.out.println("currentActivity=" + s.getCurrentActivity());
        byte[] png = s.captureFrameAsPng();
        System.out.println("frame bytes=" + (png == null ? -1 : png.length));
        if (png != null && args.length > 0) {
            try (FileOutputStream out = new FileOutputStream(args[0])) { out.write(png); }
        }
        System.out.println("frameStats=" + s.getFrameStats());
        // Needs an ethOS-only framework method: on a stock image it must come back as an error,
        // not take the daemon down (StockImageGuard).
        try {
            s.destroyAgentDisplayAndPromote();
            System.out.println("destroyAndPromote ok");
        } catch (RuntimeException e) {
            System.out.println("destroyAndPromote refused (expected on a stock image): " + e.getMessage());
            s.destroyAgentDisplay();
        }
        if (ServiceManager.getService("agentdisplay") == null) throw new IllegalStateException("daemon died");
        System.out.println("ok in " + (System.currentTimeMillis() - t0) + " ms");
    }
}
