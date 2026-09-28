package org.ethereumphone.agentbench;

import android.os.Binder;
import android.os.IAgentDisplayService;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Slog;

/**
 * Stands in front of the service on a stock image. AgentDisplayService calls framework methods
 * that only ethOS has (IActivityTaskManager.moveRootTaskToDisplayPreservingState, from
 * 1e4154ea1d1); on a stock image those throw NoSuchMethodError, an Error, which Binder does not
 * hand back to the caller but lets kill the process — and with it every later call of the run.
 * Here it becomes an UnsupportedOperationException the caller receives, like any other failure.
 * On a dgen1 the methods exist and nothing of this applies.
 */
final class StockImageGuard extends Binder {
    private static final String TAG = "AgentDisplayDaemon";
    private final Binder mService;

    StockImageGuard(Binder service) {
        mService = service;
        attachInterface(null, IAgentDisplayService.DESCRIPTOR);
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        try {
            return mService.transact(code, data, reply, flags);
        } catch (LinkageError e) {
            Slog.w(TAG, "call " + code + " needs an ethOS framework method this image lacks: " + e.getMessage());
            throw new UnsupportedOperationException("not available on this emulator image (needs ethOS framework): "
                    + e.getMessage());
        }
    }
}
