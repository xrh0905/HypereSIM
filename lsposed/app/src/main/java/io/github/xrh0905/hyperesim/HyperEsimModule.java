package io.github.xrh0905.hyperesim;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import io.github.libxposed.api.XposedInterface.Chain;
import io.github.libxposed.api.XposedInterface.Hooker;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.regex.Pattern;

/**
 * HypereSIM behaviour layer (libxposed API 102, static scope).
 *
 * <p>Only the behaviours verified on the target ROM are implemented:
 *
 * <ul>
 *   <li>H1-H4: {@code setEsimState(int)} in {@code com.android.phone} writes the Secure state
 *       bit and returns success without ever reaching {@code IExtTelephony.setSimType}.
 *   <li>H5-H6: {@code getProductEid()} returns the EID of the external eUICC in slot 1 instead
 *       of the (empty) built-in EID NV item.
 *   <li>H7: {@code com.miui.euicc.profileassistant.internal.l.g()} skips the
 *       {@code EUICC_REFRESHED} wait for the first enabled profile by calling the LPA's own
 *       {@code k(true)} branch.
 *   <li>H8: {@code ActiveStatusActivity} gets {@code ACTIVATE_CHANNEL=lpa} when the MIUI scanner
 *       launches {@code ADD_ESIM} without it, so the activation code is used instead of falling
 *       back to SM-DS discovery.
 * </ul>
 *
 * <p>All hooks fail open when a class or method is missing, so a ROM change degrades to stock
 * behaviour instead of breaking telephony. Nothing here touches MEP: the KernelSU zip module
 * owns the {@code <unavailable-feature>} overlay.
 */
public final class HyperEsimModule extends XposedModule {

    private static final String TAG = "HypereSIM";

    private static final String PHONE_PKG = "com.android.phone";
    private static final String LPA_PKG = "com.miui.euicc";

    /** Static helper holding {@code setEsimSecureValue(int)}; the write is inverted by design. */
    private static final String STATE_BASE_CLASS = "com.android.phone.MiuiEsimManagerBase";
    /** Abstract base of EnableProfileService / DisableProfileService. */
    private static final String LPA_PROFILE_SERVICE_CLASS =
            "com.miui.euicc.profileassistant.internal.l";
    /** Holds the currently enabled profile; {@code c()} returns null when none is enabled. */
    private static final String LPA_PROFILE_STORE_CLASS = "q4.a";
    /** Activation UI that validates the ADD_ESIM extras before starting a download. */
    private static final String LPA_ACTIVATION_CLASS =
            "com.miui.euicc.ui.active.ActiveStatusActivity";
    private static final String ACTION_ADD_ESIM = "com.miui.euicc.action.ADD_ESIM";
    private static final String EXTRA_ACTIVATE_CODE = "com.miui.euicc.extra.ACTIVATE_CODE";
    private static final String EXTRA_ACTIVATE_CHANNEL = "com.miui.euicc.extra.ACTIVATE_CHANNEL";
    /** The LPA refuses every other channel unless the referrer is the MIUI scanner. */
    private static final String CHANNEL_LPA = "lpa";

    private static final int EXTERNAL_EUICC_SLOT = 1;
    private static final Pattern EID = Pattern.compile("[0-9]{32}");

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        String pkg = param.getPackageName();
        ClassLoader loader = param.getDefaultClassLoader();
        if (loader == null) {
            log(Log.WARN, TAG, "no class loader for " + pkg + ", hooks skipped");
            return;
        }
        if (PHONE_PKG.equals(pkg)) {
            guardEsimState(loader);
            fillProductEid(loader);
        } else if (LPA_PKG.equals(pkg)) {
            fillScannerChannel(loader);
            skipFirstProfileRefreshWait(loader);
        }
    }

    // ---------------------------------------------------------------- H1-H4

    private void guardEsimState(ClassLoader loader) {
        guardEsimState(loader, "com.android.phone.QcMiuiEsimManager", "setEsimState");
        guardEsimState(loader, "com.android.phone.QcMiuiEsimManager$QcAidlEsimInner",
                "setEsimState", "setEsimStateEx");
        guardEsimState(loader, "com.android.phone.MiuiEsimManager", "setEsimState");
        guardEsimState(loader, "com.android.phone.QcMiuiEsimManagerEx", "setEsimState");
    }

    private void guardEsimState(ClassLoader loader, String className, String... methodNames) {
        Class<?> clazz = loadClass(className, loader);
        if (clazz == null) {
            return;
        }
        for (String name : methodNames) {
            Method method = findMethod(clazz, name, int.class);
            if (method == null || Modifier.isAbstract(method.getModifiers())) {
                log(Log.DEBUG, TAG, "skip " + className + "#" + name + "(int)");
                continue;
            }
            hookMethod(method, "hyperesim.state." + className + "#" + name,
                    chain -> stateOnly(loader, method, chain));
        }
    }

    /**
     * Replaces the modem switch with the Secure state bit. The bit is inverted by the framework:
     * {@code setEsimState(0)} enables and writes 1, {@code setEsimState(1)} disables and writes 0,
     * so the argument is passed through unchanged.
     */
    private Object stateOnly(ClassLoader loader, Method target, Chain chain) throws Throwable {
        Object raw = chain.getArg(0);
        if (!(raw instanceof Number)) {
            return chain.proceed();
        }
        int value = ((Number) raw).intValue();
        if (value != 0 && value != 1) {
            return chain.proceed();
        }
        Method setter = findMethod(loadClass(STATE_BASE_CLASS, loader), "setEsimSecureValue",
                int.class);
        if (setter == null) {
            log(Log.WARN, TAG, "setEsimSecureValue(int) missing, state bit not written");
        } else {
            try {
                setter.setAccessible(true);
                setter.invoke(null, value);
            } catch (Throwable t) {
                log(Log.WARN, TAG, "setEsimSecureValue(" + value + ") failed", t);
            }
        }
        log(Log.INFO, TAG, "setEsimState(" + value + ") -> state bit only, modem untouched");
        return successValue(target.getReturnType());
    }

    // ---------------------------------------------------------------- H5-H6

    private void fillProductEid(ClassLoader loader) {
        fillProductEid(loader, "com.android.phone.QcMiuiEsimManager");
        fillProductEid(loader, "com.android.phone.QcMiuiEsimManagerEx");
    }

    private void fillProductEid(ClassLoader loader, String className) {
        Class<?> clazz = loadClass(className, loader);
        if (clazz == null) {
            return;
        }
        Method method = findMethod(clazz, "getProductEid");
        if (method == null || Modifier.isAbstract(method.getModifiers())) {
            log(Log.DEBUG, TAG, "skip " + className + "#getProductEid()");
            return;
        }
        hookMethod(method, "hyperesim.eid." + className, this::slotEidOrProceed);
    }

    private Object slotEidOrProceed(Chain chain) throws Throwable {
        try {
            Object self = chain.getThisObject();
            Method slotEid = self == null ? null : findMethod(self.getClass(),
                    "getEidFromSlotId", int.class);
            if (slotEid != null) {
                slotEid.setAccessible(true);
                Object value = slotEid.invoke(self, EXTERNAL_EUICC_SLOT);
                if (value instanceof String && EID.matcher((String) value).matches()) {
                    log(Log.INFO, TAG, "getProductEid() -> external slot EID");
                    return value;
                }
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "external EID lookup failed, keeping original getProductEid()", t);
        }
        return chain.proceed();
    }

    // ------------------------------------------------------------------- H8

    /**
     * The MIUI scanner ({@code com.xiaomi.scanner}) launches
     * {@code com.miui.euicc.action.ADD_ESIM} with the activation code but without
     * {@code ACTIVATE_CHANNEL}. {@code ActiveStatusActivity} rejects that with "Wrong activation
     * channel" and falls through to SM-DS discovery, which finds no profile. Supply the channel
     * the LPA's own scanner uses before the activity validates its intent.
     */
    private void fillScannerChannel(ClassLoader loader) {
        Class<?> activity = loadClass(LPA_ACTIVATION_CLASS, loader);
        Method onCreate = activity == null ? null : findMethod(activity, "onCreate", Bundle.class);
        if (onCreate == null) {
            log(Log.WARN, TAG, LPA_ACTIVATION_CLASS + "#onCreate missing, scanner fix inactive");
            return;
        }
        hookMethod(onCreate, "hyperesim.lpa.scannerChannel", chain -> {
            Object self = chain.getThisObject();
            if (self instanceof Activity) {
                Intent intent = ((Activity) self).getIntent();
                if (intent != null && ACTION_ADD_ESIM.equals(intent.getAction())) {
                    try {
                        if (intent.getStringExtra(EXTRA_ACTIVATE_CHANNEL) == null
                                && intent.getStringExtra(EXTRA_ACTIVATE_CODE) != null) {
                            intent.putExtra(EXTRA_ACTIVATE_CHANNEL, CHANNEL_LPA);
                            log(Log.INFO, TAG, "ADD_ESIM without channel -> " + CHANNEL_LPA);
                        }
                    } catch (Throwable t) {
                        log(Log.WARN, TAG, "cannot patch ADD_ESIM extras", t);
                    }
                }
            }
            return chain.proceed();
        });
    }

    // ------------------------------------------------------------------- H7

    /**
     * The first enabled profile never produces a SIM state change on a slot without a card in
     * the modem, so the {@code EUICC_REFRESHED} broadcast that closes the operation never fires.
     * When no profile is enabled yet, take the LPA's own {@code k(true)} branch instead.
     */
    private void skipFirstProfileRefreshWait(ClassLoader loader) {
        Class<?> service = loadClass(LPA_PROFILE_SERVICE_CLASS, loader);
        Class<?> profiles = loadClass(LPA_PROFILE_STORE_CLASS, loader);
        Method waitForRefresh = service == null ? null : findMethod(service, "g");
        Method skipRefresh = service == null ? null : findMethod(service, "k", boolean.class);
        Method currentProfile = profiles == null ? null : findMethod(profiles, "c");
        if (waitForRefresh == null || skipRefresh == null || currentProfile == null) {
            log(Log.WARN, TAG, "LPA refresh targets incomplete, first-profile fix inactive");
            return;
        }
        hookMethod(waitForRefresh, "hyperesim.lpa.skipFirstRefresh",
                chain -> skipRefreshWhenNoProfile(chain, currentProfile, skipRefresh));
    }

    private Object skipRefreshWhenNoProfile(Chain chain, Method currentProfile, Method skipRefresh)
            throws Throwable {
        Object enabled;
        try {
            enabled = currentProfile.invoke(null);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "q4.a.c() failed, keeping original refresh wait", t);
            return chain.proceed();
        }
        if (enabled != null) {
            return chain.proceed();
        }
        try {
            skipRefresh.setAccessible(true);
            skipRefresh.invoke(chain.getThisObject(), Boolean.TRUE);
            log(Log.INFO, TAG, "first profile -> skipped EUICC_REFRESHED wait via k(true)");
            return null;
        } catch (Throwable t) {
            log(Log.WARN, TAG, "k(true) failed, keeping original refresh wait", t);
            return chain.proceed();
        }
    }

    // --------------------------------------------------------------- helpers

    private Class<?> loadClass(String name, ClassLoader loader) {
        try {
            return Class.forName(name, false, loader);
        } catch (Throwable t) {
            log(Log.DEBUG, TAG, "class not found: " + name);
            return null;
        }
    }

    private static Method findMethod(Class<?> clazz, String name, Class<?>... parameterTypes) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(name, parameterTypes);
            } catch (NoSuchMethodException ignored) {
                // keep walking the hierarchy
            }
        }
        return null;
    }

    private void hookMethod(Method method, String id, Hooker hooker) {
        try {
            method.setAccessible(true);
            hook(method).setId(id).intercept(hooker);
            log(Log.INFO, TAG, "hooked " + method.getDeclaringClass().getName()
                    + "#" + method.getName() + " [" + id + "]");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook failed: " + id, t);
        }
    }

    /** Return value the guarded {@code setEsimState} reports to its caller. */
    private static Object successValue(Class<?> type) {
        if (type == void.class || type == Void.class) {
            return null;
        }
        if (type == int.class || type == Integer.class) {
            return 0;
        }
        if (type == boolean.class || type == Boolean.class) {
            return Boolean.TRUE;
        }
        if (type == long.class || type == Long.class) {
            return 0L;
        }
        return null;
    }
}
