package io.github.xrh0905.hyperesim;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

/**
 * Receives {@code LPA:1$<sm-dp+>$<matchingId>} VIEW intents and forwards them to the Xiaomi
 * LPA activation entry. The system has no handler for the {@code LPA} scheme on this ROM, so
 * without this bridge the URL falls through to Google's non-privileged eSIM app.
 *
 * <p>Mirrors the extras built by the LPA's own scanner ({@code CaptureActivity}): the activation
 * code goes in {@code ACTIVATE_CODE}, and channel {@code lpa} avoids the scanner-only referrer
 * check that {@code ActiveStatusActivity} performs for channel {@code scanner}.
 */
public final class LpaBridgeActivity extends Activity {

    private static final String TAG = "HypereSIM";
    private static final String LPA_PACKAGE = "com.miui.euicc";
    private static final String LPA_ACTIVITY = "com.miui.euicc.ui.active.ActiveStatusActivity";

    private static final String EXTRA_ACTIVATE_TYPE = "com.miui.euicc.extra.ACTIVATE_TYPE";
    private static final String EXTRA_ACTIVATE_CODE = "com.miui.euicc.extra.ACTIVATE_CODE";
    private static final String EXTRA_ACTIVATE_CHANNEL = "com.miui.euicc.extra.ACTIVATE_CHANNEL";
    private static final String EXTRA_OPERATE_TYPE = "com.miui.euicc.extra.OPERATE_TYPE";

    private static final String ACTIVATION_CODE_PREFIX = "LPA:1$";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent incoming = getIntent();
        Uri data = incoming == null ? null : incoming.getData();
        String code = data == null ? null : data.toString();
        if (code == null || !code.startsWith(ACTIVATION_CODE_PREFIX)) {
            Log.w(TAG, "LpaBridge: ignoring non-activation URI: " + code);
            finish();
            return;
        }
        Intent forward = new Intent();
        forward.setClassName(LPA_PACKAGE, LPA_ACTIVITY);
        forward.putExtra(EXTRA_ACTIVATE_TYPE, "activate_manual");
        forward.putExtra(EXTRA_ACTIVATE_CODE, code);
        forward.putExtra(EXTRA_ACTIVATE_CHANNEL, "lpa");
        forward.putExtra(EXTRA_OPERATE_TYPE, "scan");
        try {
            startActivity(forward);
            Log.i(TAG, "LpaBridge: handed activation code to " + LPA_ACTIVITY);
        } catch (Throwable t) {
            Log.w(TAG, "LpaBridge: cannot start " + LPA_ACTIVITY, t);
        }
        finish();
    }
}
