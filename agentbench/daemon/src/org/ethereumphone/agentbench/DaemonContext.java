package org.ethereumphone.agentbench;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.ContentProviderHolder;
import android.app.Instrumentation;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.IContentProvider;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;

import java.util.HashMap;
import java.util.Map;

/**
 * The system context, minus the parts that need a process the ActivityManager knows.
 *
 * In system_server the system ActivityThread is registered with AMS; here it is not, so every
 * call that passes our {@code IApplicationThread} as the caller is refused ("Unable to find app
 * for caller"). The three paths AgentDisplayService uses are re-routed the way the shell's
 * {@code am}/{@code settings}/{@code content} commands do it: a null caller, identified by uid.
 */
final class DaemonContext extends ContextWrapper {
    private static final String PACKAGE = "android";
    private final ContentResolver mResolver;

    DaemonContext(Context base) {
        super(base);
        mResolver = new ExternalProviderResolver(this);
    }

    @Override public Context getApplicationContext() { return this; }

    @Override public ContentResolver getContentResolver() { return mResolver; }

    @Override
    public void startActivity(Intent intent) { startActivity(intent, null); }

    @Override
    public void startActivity(Intent intent, Bundle options) {
        startActivityAsUser(intent, options, UserHandle.of(ActivityManager.getCurrentUser()));
    }

    @Override
    public void startActivityAsUser(Intent intent, Bundle options, UserHandle user) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            int result = ActivityTaskManager.getService().startActivityAsUser(null, PACKAGE, null,
                    intent, intent.resolveTypeIfNeeded(mResolver), null, null, 0, 0, null,
                    options, user.getIdentifier());
            Instrumentation.checkStartActivityResult(result, intent);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public Intent registerReceiverAsUser(BroadcastReceiver receiver, UserHandle user,
            IntentFilter filter, String permission, Handler scheduler) {
        return registerReceiverAsUser(receiver, user, filter, permission, scheduler, 0);
    }

    @Override
    public Intent registerReceiverAsUser(BroadcastReceiver receiver, UserHandle user,
            IntentFilter filter, String permission, Handler scheduler, int flags) {
        final Handler handler = scheduler != null ? scheduler : new Handler(Looper.getMainLooper());
        final Context self = this;
        IIntentReceiver.Stub dispatcher = new IIntentReceiver.Stub() {
            @Override
            public void performReceive(Intent intent, int resultCode, String data, Bundle extras,
                    boolean ordered, boolean sticky, int sendingUser) {
                handler.post(() -> receiver.onReceive(self, intent));
            }
        };
        try {
            return ActivityManager.getService().registerReceiverWithFeature(null, null, null, null,
                    dispatcher, filter, permission, user.getIdentifier(),
                    flags | Context.RECEIVER_EXPORTED);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** Providers fetched the way {@code adb shell content} does: getContentProviderExternal. */
    private static final class ExternalProviderResolver extends ContentResolver {
        private final IBinder mToken = new Binder();
        private final Map<String, IContentProvider> mProviders = new HashMap<>();

        ExternalProviderResolver(Context context) { super(context); }

        private synchronized IContentProvider get(String authority) {
            IContentProvider provider = mProviders.get(authority);
            if (provider != null && provider.asBinder().isBinderAlive()) return provider;
            try {
                ContentProviderHolder holder = ActivityManager.getService()
                        .getContentProviderExternal(authority, UserHandle.USER_SYSTEM, mToken,
                                "agentdisplayd");
                provider = holder != null ? holder.provider : null;
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
            if (provider != null) mProviders.put(authority, provider);
            return provider;
        }

        @Override protected IContentProvider acquireProvider(Context c, String name) { return get(name); }
        @Override protected IContentProvider acquireExistingProvider(Context c, String name) { return get(name); }
        @Override public boolean releaseProvider(IContentProvider provider) { return true; }
        @Override protected IContentProvider acquireUnstableProvider(Context c, String name) { return get(name); }
        @Override public boolean releaseUnstableProvider(IContentProvider icp) { return true; }
        @Override public void unstableProviderDied(IContentProvider icp) { }
        @Override public void appNotRespondingViaProvider(IContentProvider icp) { }
    }
}
