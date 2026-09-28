package org.ethereumphone.agentbench;

import android.os.Bundle;
import android.os.IWalletService;
import android.os.ResultReceiver;
import android.os.ServiceManager;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Makes the call AndyClaw's onboarding makes (`WalletSignScreen`: WalletSDK.getAddress, then
 * signMessage("Signing into AndyClaw", chainId 8453, "personal_sign")) against the dgen1's own
 * `wallet` service, from the adb shell. The approval happens on the terminal screen like any
 * other signature request; this program never sees a key. Prints one JSON object on stdout.
 */
public final class WalletSignIn {
    private static final String MESSAGE = "Signing into AndyClaw";
    private static final String CHAIN_ID = "8453";

    public static void main(String[] args) {
        // Always one JSON line on stdout, whatever happens: the shell script shows it verbatim.
        try {
            run();
            System.exit(0);
        } catch (Throwable t) {
            System.out.println("{\"error\":" + quote(String.valueOf(t)) + "}");
            System.exit(2);
        }
    }

    private static String quote(String s) {
        return "\"" + (s == null ? "null" : s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")) + "\"";
    }

    private static void run() throws Exception {
        IWalletService wallet = IWalletService.Stub.asInterface(ServiceManager.getService("wallet"));
        if (wallet == null) throw new IllegalStateException("no 'wallet' service: is this an ethOS device?");
        String session = wallet.createSession();

        String address = await(cb -> wallet.getAddress(session, cb), 30);
        if (address == null || !address.startsWith("0x")) throw new IllegalStateException("getAddress returned " + address);
        System.err.println("address " + address);
        System.err.println(">>> Approve the signature request on the dgen1 now (keep the screen on) <<<");

        String signature = await(cb -> wallet.signMessage(session, MESSAGE, CHAIN_ID, address, "personal_sign", cb), 180);
        if (signature == null || !signature.startsWith("0x")) {
            // AndyClaw's WalletSignScreen refuses the same shape; show what the wallet said.
            throw new IllegalStateException("wallet answered " + quote(signature) + " for " + address);
        }
        System.out.println("{\"walletAddress\":\"" + address + "\",\"walletSignature\":\"" + signature + "\"}");
    }

    private interface Call { void run(ResultReceiver cb) throws Exception; }

    private static String await(Call call, int timeoutS) throws Exception {
        final String[] out = new String[1];
        final CountDownLatch done = new CountDownLatch(1);
        call.run(new ResultReceiver(null) {
            @Override protected void onReceiveResult(int code, Bundle data) {
                out[0] = data != null ? data.getString("result") : null;
                done.countDown();
            }
        });
        if (!done.await(timeoutS, TimeUnit.SECONDS)) throw new IllegalStateException("no answer in " + timeoutS + " s");
        return out[0];
    }
}
