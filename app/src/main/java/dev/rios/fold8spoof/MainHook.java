package dev.rios.fold8spoof;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.Locale;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class MainHook implements IXposedHookLoadPackage {
    private static final String TAG = "Fold8FeatureSpoofer";
    private static final String AI_VERSION = "SEC_FLOATING_FEATURE_COMMON_CONFIG_AI_VERSION";
    private static final String LLM_VERSION = "SEC_FLOATING_FEATURE_GENAI_CONFIG_LLM_VERSION";
    private static final String NOW_NUDGE_VERSION = "SEC_FLOATING_FEATURE_FRAMEWORK_CONFIG_NOW_NUDGE_VERSION";

    private static final String ANDROID_PACKAGE = "android";
    private static final String SETTINGS_PACKAGE = "com.android.settings";
    private static final String SETTINGS_NOTIFICATION_AI_GATE = "config_na_enable";
    private static final String SCS_CONFIGURATION_CLASS =
            "com.samsung.android.sdk.scs.ai.language.Configuration";
    private static final String NOTI_SUMMARY_CALLBACK_CLASS =
            "com.android.server.notification.sec.summarize.NotiSummaryManager$$ExternalSyntheticLambda0";
    private static final String ACTION_POINT_SUMMARY = "ActionPointSummary";
    private static final String SCS_NOTIFICATION_SUMMARY_CAPABILITY =
            "[{\"supportFunction\":[\"ActionPointSummary\"]}]";

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lp) {
        hookFeatureClass(lp, "com.samsung.android.feature.SemFloatingFeature", true);
        hookFeatureClass(lp, "com.samsung.android.feature.SemCscFeature", false);

        if (SETTINGS_PACKAGE.equals(lp.packageName)) {
            hookSettingsNotificationIntelligenceGate(lp);
        }

        if ("com.android.systemui".equals(lp.packageName)) {
            hookSysUiSummarization(lp);
        }

        if (ANDROID_PACKAGE.equals(lp.packageName)) {
            hookScsOndeviceCapability(lp);
            hookSummaryLanguageNormalization(lp);
            hookSummaryInference(lp);
        }
    }

    private static void hookFeatureClass(final XC_LoadPackage.LoadPackageParam lp,
                                         final String className,
                                         final boolean spoofFloating) {
        Class<?> clazz = findFeatureClass(className, lp.classLoader);
        if (clazz == null) return;

        final String source = className.substring(className.lastIndexOf('.') + 1);

        try {
            XposedBridge.hookAllMethods(clazz, "getString", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    String key = firstStringArg(param);
                    if (key == null) return;
                    Object original = safeResult(param);
                    String replacement = spoofFloating ? stringOverride(key) : null;
                    if (replacement != null) {
                        param.setResult(replacement);
                        log(lp, source, "getString", key, original, replacement);
                    } else if (isInterestingKey(key)) {
                        log(lp, source, "getString", key, original, null);
                    }
                }
            });
        } catch (Throwable t) {
            logError(lp, source + ".getString", t);
        }

        try {
            XposedBridge.hookAllMethods(clazz, "getInt", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    String key = firstStringArg(param);
                    if (key == null) return;
                    Object original = safeResult(param);
                    Integer replacement = spoofFloating ? intOverride(key) : null;
                    if (replacement != null) {
                        param.setResult(replacement);
                        log(lp, source, "getInt", key, original, replacement);
                    } else if (isInterestingKey(key)) {
                        log(lp, source, "getInt", key, original, null);
                    }
                }
            });
        } catch (Throwable t) {
            logError(lp, source + ".getInt", t);
        }

        try {
            XposedBridge.hookAllMethods(clazz, "getBoolean", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    String key = firstStringArg(param);
                    if (key != null && isInterestingKey(key)) {
                        log(lp, source, "getBoolean", key, safeResult(param), null);
                    }
                }
            });
        } catch (Throwable t) {
            logError(lp, source + ".getBoolean", t);
        }

        XposedBridge.log("[" + TAG + "] hooked " + source + " in " + lp.packageName + " / " + lp.processName);
    }

    private static void hookSettingsNotificationIntelligenceGate(final XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> resourcesClass = XposedHelpers.findClass("android.content.res.Resources", null);
            XposedBridge.hookAllMethods(resourcesClass, "getBoolean", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args == null || param.args.length == 0 || !(param.args[0] instanceof Integer)) {
                        return;
                    }

                    int resId = (Integer) param.args[0];
                    Resources resources = (Resources) param.thisObject;

                    try {
                        String entryName = resources.getResourceEntryName(resId);
                        if (!SETTINGS_NOTIFICATION_AI_GATE.equals(entryName)) return;

                        String packageName = resources.getResourcePackageName(resId);
                        if (!SETTINGS_PACKAGE.equals(packageName)) return;

                        Object original = safeResult(param);
                        param.setResult(true);
                        log(lp, "Resources", "getBoolean", SETTINGS_NOTIFICATION_AI_GATE,
                                original, true);
                    } catch (Resources.NotFoundException ignored) {
                    } catch (Throwable t) {
                        logError(lp, "Resources.getBoolean(" + Integer.toHexString(resId) + ")", t);
                    }
                }
            });
            XposedBridge.log("[" + TAG + "] hooked Settings notification intelligence gate in "
                    + lp.packageName + " / " + lp.processName);
        } catch (Throwable t) {
            logError(lp, "Settings config_na_enable", t);
        }
    }

    private static void hookScsOndeviceCapability(final XC_LoadPackage.LoadPackageParam lp) {
        Class<?> clazz = findFeatureClass(SCS_CONFIGURATION_CLASS, lp.classLoader);
        if (clazz == null) {
            XposedBridge.log("[" + TAG + "] SCS Configuration class not found in "
                    + lp.packageName + " / " + lp.processName);
            return;
        }

        try {
            XposedBridge.hookAllMethods(clazz, "getOndeviceCapability", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable()) {
                        XposedBridge.log("[" + TAG + "] SCS getOndeviceCapability() threw: "
                                + String.valueOf(param.getThrowable()));
                        return;
                    }

                    Object original = safeResult(param);
                    String value = original instanceof String ? (String) original : null;
                    String replacement = injectActionPointSummary(value);

                    if (replacement != null && !replacement.equals(value)) {
                        param.setResult(replacement);
                        XposedBridge.log("[" + TAG + "] SCS capability patched: ActionPointSummary=true"
                                + " originalLength=" + (value == null ? -1 : value.length())
                                + " patchedLength=" + replacement.length());
                    } else {
                        XposedBridge.log("[" + TAG + "] SCS capability unchanged: ActionPointSummary="
                                + containsActionPointSummary(value));
                    }
                }
            });
            XposedBridge.log("[" + TAG + "] hooked SCS getOndeviceCapability in "
                    + lp.packageName + " / " + lp.processName);
        } catch (Throwable t) {
            logError(lp, "SCS getOndeviceCapability", t);
        }
    }

    private static void hookSummaryLanguageNormalization(final XC_LoadPackage.LoadPackageParam lp) {
        Class<?> clazz = findFeatureClass(NOTI_SUMMARY_CALLBACK_CLASS, lp.classLoader);
        if (clazz == null) {
            XposedBridge.log("[" + TAG + "] summary callback class not found in "
                    + lp.packageName + " / " + lp.processName);
            return;
        }

        try {
            XposedBridge.hookAllMethods(clazz, "accept", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args == null || param.args.length < 2
                            || !(param.args[1] instanceof String)) {
                        return;
                    }

                    String original = (String) param.args[1];
                    String normalized = normalizeDetectedSummaryLanguage(original);
                    if (!original.equals(normalized)) {
                        param.args[1] = normalized;
                        XposedBridge.log("[" + TAG + "] summary language normalized: "
                                + original + " -> " + normalized);
                    }
                }
            });
            XposedBridge.log("[" + TAG + "] hooked notification summary language normalization in "
                    + lp.packageName + " / " + lp.processName);
        } catch (Throwable t) {
            logError(lp, "notification summary language normalization", t);
        }
    }

    private static String normalizeDetectedSummaryLanguage(String value) {
        if (value == null) return null;
        String compact = value.trim().toLowerCase(Locale.ROOT)
                .replace("-", "")
                .replace("_", "");
        if ("ptbr".equals(compact)) return "pt";
        return value;
    }

    private static String injectActionPointSummary(String value) {
        String normalized = value == null ? null : value.trim();
        if (normalized == null || normalized.isEmpty() || "stub".equalsIgnoreCase(normalized)) {
            return SCS_NOTIFICATION_SUMMARY_CAPABILITY;
        }
        if (containsActionPointSummary(value)) return value;

        try {
            Object root;
            if (normalized.startsWith("[")) {
                root = new JSONArray(normalized);
            } else if (normalized.startsWith("{")) {
                root = new JSONObject(normalized);
            } else {
                return value;
            }

            boolean[] foundSupportFunction = new boolean[]{false};
            boolean changed = appendCapabilityRecursive(root, foundSupportFunction);

            if (!foundSupportFunction[0]) {
                JSONArray functions = new JSONArray();
                functions.put(ACTION_POINT_SUMMARY);
                if (root instanceof JSONArray) {
                    JSONObject extra = new JSONObject();
                    extra.put("supportFunction", functions);
                    ((JSONArray) root).put(extra);
                    changed = true;
                } else if (root instanceof JSONObject) {
                    ((JSONObject) root).put("supportFunction", functions);
                    changed = true;
                }
            }

            if (!changed) return value;
            return root.toString();
        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] failed to patch SCS capability JSON: " + t);
            return value;
        }
    }

    private static boolean appendCapabilityRecursive(Object node, boolean[] foundSupportFunction) throws Exception {
        boolean changed = false;

        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object child = obj.opt(key);

                if ("supportFunction".equals(key)) {
                    foundSupportFunction[0] = true;
                    if (child instanceof JSONArray) {
                        JSONArray functions = (JSONArray) child;
                        if (!arrayContains(functions, ACTION_POINT_SUMMARY)) {
                            functions.put(ACTION_POINT_SUMMARY);
                            changed = true;
                        }
                    } else if (child instanceof String) {
                        JSONArray functions = new JSONArray();
                        functions.put((String) child);
                        functions.put(ACTION_POINT_SUMMARY);
                        obj.put(key, functions);
                        changed = true;
                    }
                } else if (child instanceof JSONObject || child instanceof JSONArray) {
                    changed |= appendCapabilityRecursive(child, foundSupportFunction);
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                Object child = array.opt(i);
                if (child instanceof JSONObject || child instanceof JSONArray) {
                    changed |= appendCapabilityRecursive(child, foundSupportFunction);
                }
            }
        }

        return changed;
    }

    private static boolean arrayContains(JSONArray array, String target) {
        for (int i = 0; i < array.length(); i++) {
            Object item = array.opt(i);
            if (target.equals(String.valueOf(item))) return true;
        }
        return false;
    }

    private static boolean containsActionPointSummary(String value) {
        return value != null && value.contains(ACTION_POINT_SUMMARY);
    }

    private static Class<?> findFeatureClass(String className, ClassLoader appClassLoader) {
        try {
            return XposedHelpers.findClass(className, null);
        } catch (Throwable ignored) {
        }
        try {
            return XposedHelpers.findClass(className, appClassLoader);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String firstStringArg(XC_MethodHook.MethodHookParam param) {
        if (param.args == null || param.args.length == 0 || !(param.args[0] instanceof String)) return null;
        return (String) param.args[0];
    }

    private static Object safeResult(XC_MethodHook.MethodHookParam param) {
        try {
            return param.getResult();
        } catch (Throwable ignored) {
            return "<throw>";
        }
    }

    private static String stringOverride(String key) {
        if (AI_VERSION.equals(key)) return "20263";
        if (LLM_VERSION.equals(key)) return "2.81";
        if (NOW_NUDGE_VERSION.equals(key)) return "2";
        return null;
    }

    private static Integer intOverride(String key) {
        if (AI_VERSION.equals(key)) return 20263;
        if (NOW_NUDGE_VERSION.equals(key)) return 2;
        return null;
    }

    private static boolean isInterestingKey(String key) {
        String k = key.toUpperCase(Locale.ROOT);
        return k.contains("AI_") || k.contains("GENAI") || k.contains("NUDGE") || k.contains("NOW_")
                || k.contains("BRIEF") || k.contains("PDE") || k.contains("PERSONAL") || k.contains("SUGGEST")
                || k.contains("NOTI") || k.contains("SUMMARY") || k.contains("PRIORIT");
    }

    private static void log(XC_LoadPackage.LoadPackageParam lp, String source, String method,
                            String key, Object original, Object replacement) {
        StringBuilder b = new StringBuilder();
        b.append('[').append(TAG).append("] ")
                .append(lp.packageName).append('/').append(lp.processName)
                .append(' ').append(source).append('.').append(method)
                .append('(').append(key).append(") = ").append(String.valueOf(original));
        if (replacement != null) b.append(" -> ").append(String.valueOf(replacement));
        XposedBridge.log(b.toString());
    }

    private static void logError(XC_LoadPackage.LoadPackageParam lp, String where, Throwable t) {
        XposedBridge.log("[" + TAG + "] " + lp.packageName + " " + where + " hook error: " + t);
    }

    private static final String LLM_SUMMARY_FEATURE = "FEATURE_AI_GEN_SUMMARY";

    private static void hookSummaryInference(final XC_LoadPackage.LoadPackageParam lp) {
        hookFreshness(lp);
        hookSummaryLimiter(lp);
        hookDeviceGate(lp);
        hookSuccessRenotify(lp);
        try {
            Class<?> runnable = XposedHelpers.findClass(
                    "com.samsung.android.sdk.scs.ai.language.service.LlmServiceRunnable",
                    lp.classLoader);
            XposedBridge.hookAllMethods(runnable, "execute", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object feature = XposedHelpers.getObjectField(param.thisObject, "featureName");
                        if (!LLM_SUMMARY_FEATURE.equals(String.valueOf(feature))) return;
                        Object req = XposedHelpers.getObjectField(param.thisObject, "serviceRequest");
                        String text = String.valueOf(XposedHelpers.getObjectField(req, "f$2"));
                        String excerpt = text == null ? "" : text.replaceAll("\\s+", " ").trim();
                        if (excerpt.length() > 140) excerpt = excerpt.substring(0, 140) + "…";
                        broadcastEvent("requested", bestEffortKey(param.thisObject), "-", text == null ? -1 : text.length(), excerpt);
                        String summary = generateLocalSummary(lp, text);
                        if (summary == null || summary.isEmpty()) return;
                        Bundle b = new Bundle();
                        b.putString("content", summary);
                        b.putString("safety", "{\"Blocked\":false}");
                        b.putString("model_alias", "gemma3-npu");
                        Object result = XposedHelpers.newInstance(
                                XposedHelpers.findClass(
                                        "com.samsung.android.sdk.scs.ai.language.Result",
                                        lp.classLoader), b);
                        Object source = XposedHelpers.getObjectField(param.thisObject, "mSource");
                        Object task = XposedHelpers.getObjectField(source, "task");
                        XposedHelpers.callMethod(task, "setResult", result);
                        param.setResult(null);
                        XposedBridge.log("[" + TAG + "] local summary served, len="
                                + summary.length());
                    } catch (Throwable t) {
                        logError(lp, "localSummary", t);
                    }
                }
            });
            XposedBridge.log("[" + TAG + "] hooked LlmServiceRunnable.execute in "
                    + lp.packageName + " / " + lp.processName);
        } catch (Throwable t) {
            logError(lp, "hookSummaryInference", t);
        }
    }

    /** Best-effort notification key from the SCS runnable (may be absent). */
    private static String bestEffortKey(Object runnable) {
        try {
            Object source = XposedHelpers.getObjectField(runnable, "mSource");
            if (source == null) return "-";
            for (String f : new String[]{"key", "mKey", "notificationKey"}) {
                try {
                    Object v = XposedHelpers.getObjectField(source, f);
                    if (v instanceof String && !((String) v).isEmpty()) return (String) v;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return "-";
    }

    /** Mirrors a summary lifecycle event to the app for the in-app log viewer. */
    private static void broadcastEvent(String kind, String key, String status, int len, String detail) {
        try {
            Class<?> at = XposedHelpers.findClass("android.app.ActivityThread", null);
            Object app = XposedHelpers.callStaticMethod(at, "currentApplication");
            if (app == null) return;
            Context ctx = (Context) app;
            Intent i = new Intent("dev.rios.fold8spoof.SUMMARY_EVENT");
            i.setPackage("dev.rios.fold8spoof");
            i.putExtra("kind", kind);
            i.putExtra("key", key == null ? "-" : key);
            i.putExtra("status", status == null ? "-" : status);
            i.putExtra("len", len);
            i.putExtra("detail", detail == null ? "" : detail);
            ctx.sendBroadcast(i);
        } catch (Throwable t) {
            XposedBridge.log("[" + TAG + "] event mirror failed: " + t);
        }
    }

    private static void hookSysUiSummarization(final XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> decorator = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.notification.collection.coordinator.SummarizationDecorator",
                    lp.classLoader);
            XposedBridge.hookAllMethods(decorator, "decorateSummarization", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object entry = param.args != null && param.args.length > 0 ? param.args[0] : null;
                        Object sum = entry == null ? null
                                : XposedHelpers.callMethod(entry, "getSummarization");
                        XposedBridge.log("[" + TAG + "] sysui decorateSummarization called, text="
                                + (sum == null ? "null" : String.valueOf(sum).length() + "ch"));
                    } catch (Throwable t) {
                        logError(lp, "sysuiDecorateLog", t);
                    }
                }
            });
            XposedBridge.log("[" + TAG + "] hooked sysui SummarizationDecorator in "
                    + lp.packageName + " / " + lp.processName);
        } catch (Throwable t) {
            logError(lp, "hookSysUiSummarization", t);
        }
        hookSysUiRenderDebug(lp);
    }

    /** Debug-only: per-row summarization render inputs (remove after diagnosis). */
    private static void hookSysUiRenderDebug(final XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> entry = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.notification.collection.NotificationEntry",
                    lp.classLoader);
            XposedBridge.hookAllMethods(entry, "getSummarization", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object res = param.getResult();
                        Object key = XposedHelpers.getObjectField(param.thisObject, "key");
                        boolean hl = false;
                        int semPr = -1;
                        try {
                            hl = Boolean.TRUE.equals(
                                    XposedHelpers.callMethod(param.thisObject, "isHighlightsStyle"));
                        } catch (Throwable ignored) {
                        }
                        try {
                            Object sbn = XposedHelpers.getObjectField(param.thisObject, "mSbn");
                            Object notif = sbn == null ? null
                                    : XposedHelpers.callMethod(sbn, "getNotification");
                            if (notif != null) {
                                semPr = XposedHelpers.getIntField(notif, "semPriority");
                            }
                        } catch (Throwable ignored) {
                        }
                        String sum = (res == null || String.valueOf(res).isEmpty())
                                ? "empty" : ("len=" + String.valueOf(res).length());
                        XposedBridge.log("[" + TAG + "] sysui rowsec: key=" + key
                                + " sum=" + sum + " hlStyle=" + hl + " semPriority=" + semPr);
                    } catch (Throwable t) {
                        logError(lp, "sysuiEntrySumLog", t);
                    }
                }
            });
            XposedBridge.log("[" + TAG + "] hooked sysui NotificationEntry.getSummarization in "
                    + lp.packageName + " / " + lp.processName);
        } catch (Throwable t) {
            logError(lp, "hookSysUiEntrySum", t);
        }
        try {
            Class<?> layout = XposedHelpers.findClass(
                    "com.android.internal.widget.ConversationLayout", null);
            if (layout == null) {
                XposedBridge.log("[" + TAG + "] ConversationLayout not found in "
                        + lp.packageName + " / " + lp.processName);
                return;
            }
            XposedBridge.hookAllMethods(layout, "setIsCollapsed", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        XposedBridge.log("[" + TAG + "] sysui ConversationLayout.setIsCollapsed="
                                + (param.args != null && param.args.length > 0 ? param.args[0] : "?"));
                    } catch (Throwable t) {
                        logError(lp, "sysuiCollapseLog", t);
                    }
                }
            });
            XposedBridge.hookAllMethods(layout, "setData", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        boolean hasSum = false;
                        if (param.args != null && param.args.length > 0
                                && param.args[0] instanceof android.os.Bundle) {
                            hasSum = ((android.os.Bundle) param.args[0])
                                    .containsKey("android.summarization");
                        }
                        boolean collapsed = Boolean.TRUE.equals(
                                XposedHelpers.getObjectField(param.thisObject, "mIsCollapsed"));
                        XposedBridge.log("[" + TAG + "] sysui ConversationLayout.setData hasSumExtra="
                                + hasSum + " collapsed=" + collapsed);
                    } catch (Throwable t) {
                        logError(lp, "sysuiSetDataLog", t);
                    }
                }
            });
            XposedBridge.log("[" + TAG + "] hooked sysui ConversationLayout in "
                    + lp.packageName + " / " + lp.processName);
        } catch (Throwable t) {
            logError(lp, "hookSysUiLayout", t);
        }
    }

    private static void hookSummaryLimiter(final XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> limiter = XposedHelpers.findClass(
                    "com.android.server.notification.sec.summarize.NotiSumamryLimiter",
                    lp.classLoader);
            XposedBridge.hookAllMethods(limiter, "isLimited", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(false);
                }
            });
            XposedBridge.log("[" + TAG + "] hooked NotiSumamryLimiter.isLimited -> false in "
                    + lp.packageName + " / " + lp.processName);
        } catch (Throwable t) {
            logError(lp, "hookSummaryLimiter", t);
        }
    }

    /**
     * On-demand summaries: bypass screen-off / battery / powersave gates so
     * every new message triggers inference within seconds (NPU ~1-2 s).
     * The 12 h quota is already neutralized via hookSummaryLimiter.
     */
    private static void hookDeviceGate(final XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> mgr = XposedHelpers.findClass(
                    "com.android.server.notification.sec.summarize.NotiSummaryManager",
                    lp.classLoader);
            XposedBridge.hookAllMethods(mgr, "checkDeviceStateForSummary", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(true);
                }
            });
            // Force first=true: re-summaries use ALARM_FIRST_DELAY (~10 s)
            // instead of ALARM_INTERVAL (3 min), so a reset summary comes
            // back in seconds after any re-post.
            XposedBridge.hookAllMethods(mgr, "requestAll", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args != null && param.args.length > 1
                            && param.args[1] instanceof Boolean) {
                        param.args[1] = true;
                    }
                }
            });
            XposedBridge.log("[" + TAG + "] hooked NotiSummaryManager.checkDeviceStateForSummary -> true in "
                    + lp.packageName + " / " + lp.processName);
        } catch (Throwable t) {
            logError(lp, "hookDeviceGate", t);
        }
    }

    private static void hookSuccessRenotify(final XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> lambda15 = XposedHelpers.findClass(
                    "com.android.server.notification.NotificationManagerService$$ExternalSyntheticLambda15",
                    lp.classLoader);
            XposedBridge.hookAllMethods(lambda15, "accept", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object classId = XposedHelpers.getObjectField(param.thisObject, "$r8$classId");
                        if (!(classId instanceof Integer) || ((Integer) classId) != 0) return;
                        Object status = param.args != null && param.args.length > 0 ? param.args[0] : null;
                        if (status == null) return;
                        final Object nms = XposedHelpers.getObjectField(param.thisObject, "f$0");
                        final String key = String.valueOf(XposedHelpers.getObjectField(param.thisObject, "f$1"));
                        broadcastEvent("result", key, String.valueOf(status), -1, "");
                        if (!"SUCCESS".equals(String.valueOf(status))) return;
                        android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                        h.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    Object lock = XposedHelpers.getObjectField(nms, "mNotificationLock");
                                    synchronized (lock) {
                                        Object map = XposedHelpers.getObjectField(nms, "mNotificationsByKey");
                                        Object record = XposedHelpers.callMethod(map, "get", key);
                                        if (record == null) return;
                                        Object st = XposedHelpers.getObjectField(record, "mSummaryStatus");
                                        Object tx = XposedHelpers.getObjectField(record, "mSummarization");
                                        if (st == null || !"SUCCESS".equals(String.valueOf(st))) return;
                                        if (tx == null || String.valueOf(tx).isEmpty()) return;
                                        Object rankingHandler = XposedHelpers.getObjectField(nms, "mRankingHandler");
                                        XposedHelpers.callMethod(rankingHandler, "requestSort");
                                        XposedHelpers.callMethod(nms, "notifyListenersSilently", record);
                                        XposedBridge.log("[" + TAG + "] summary re-notified for rebind: " + key);
                                    }
                                } catch (Throwable t) {
                                    XposedBridge.log("[" + TAG + "] renotify error: " + t);
                                }
                            }
                        }, 3000);
                    } catch (Throwable t) {
                        logError(lp, "renotifyHook", t);
                    }
                }
            });
            XposedBridge.log("[" + TAG + "] hooked summary SUCCESS renotify in "
                    + lp.packageName + " / " + lp.processName);
        } catch (Throwable t) {
            logError(lp, "hookSuccessRenotify", t);
        }
    }

    private static void hookFreshness(final XC_LoadPackage.LoadPackageParam lp) {        try {
            Class<?> mgr = XposedHelpers.findClass(
                    "com.android.server.notification.sec.summarize.NotiSummaryManager",
                    lp.classLoader);
            XposedBridge.hookAllMethods(mgr, "isFresh", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(false);
                }
            });
            XposedBridge.log("[" + TAG + "] hooked NotiSummaryManager.isFresh -> false in "
                    + lp.packageName + " / " + lp.processName);
        } catch (Throwable t) {
            logError(lp, "hookFreshness", t);
        }
    }

    private static String generateLocalSummary(XC_LoadPackage.LoadPackageParam lp, String text) {
        try {
            String conversation = extractConversation(text);
            if (conversation == null || conversation.length() < 10) return null;
            ensureLlmServer();
            if (!waitForServer(150000)) {
                XposedBridge.log("[" + TAG + "] LLM server not ready, falling back");
                return null;
            }
            String prompt = conversation;
            String out = postChat(prompt, 120);
            if (out == null || out.trim().isEmpty()) return null;
            out = out.trim();
            if (out.regionMatches(true, 0, "Resumo:", 0, 7)) {
                out = out.substring(7).trim();
            } else if (out.regionMatches(true, 0, "Summary:", 0, 8)) {
                out = out.substring(8).trim();
            }
            if (out.length() > 400) {
                int cut = out.lastIndexOf('.', 400);
                if (cut < 100) cut = out.lastIndexOf(' ', 400);
                out = (cut > 100 ? out.substring(0, cut + 1) : out.substring(0, 400)).trim();
            }
            return out;
        } catch (Throwable t) {
            logError(lp, "generateLocalSummary", t);
            return null;
        }
    }

    private static String extractConversation(String text) {
        if (text == null) return null;
        try {
            String t = text.trim();
            if (t.startsWith("{")) {
                JSONObject o = new JSONObject(t);
                if (o.has("conversation")) return o.optString("conversation", null);
            }
        } catch (Throwable ignored) {
        }
        return text;
    }

    private static void ensureLlmServer() {
        try {
            Class<?> at = XposedHelpers.findClass("android.app.ActivityThread", null);
            Object app = XposedHelpers.callStaticMethod(at, "currentApplication");
            if (app == null) return;
            Context ctx = (Context) app;
            Intent i = new Intent();
            i.setComponent(new ComponentName("dev.rios.fold8spoof",
                    "dev.rios.fold8spoof.LlmServerService"));
            ctx.startService(i);
        } catch (Throwable ignored) {
        }
    }

    private static boolean waitForServer(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            java.net.HttpURLConnection c = null;
            try {
                java.net.URL url = new java.net.URL("http://127.0.0.1:"
                        + LlmServerService.PORT + "/health");
                c = (java.net.HttpURLConnection) url.openConnection();
                c.setConnectTimeout(2000);
                c.setReadTimeout(2000);
                if (c.getResponseCode() == 200) return true;
            } catch (Throwable ignored) {
            } finally {
                if (c != null) c.disconnect();
            }
            try {
                Thread.sleep(3000);
            } catch (InterruptedException ignored) {
                return false;
            }
        }
        return false;
    }

    private static String postChat(String conversation, int maxTokens) {
        java.net.HttpURLConnection c = null;
        try {
            java.net.URL url = new java.net.URL("http://127.0.0.1:"
                    + LlmServerService.PORT + "/v1/chat/completions");
            c = (java.net.HttpURLConnection) url.openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(180000);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            JSONObject req = new JSONObject();
            JSONArray messages = new JSONArray();
            JSONObject system = new JSONObject();
            system.put("role", "system");
            system.put("content", "Resuma as mensagens a seguir em ate tres frases curtas em "
                    + "portugues, focando nos pontos principais e nas acoes a tomar. "
                    + "Responda apenas com o resumo, sem traduzir.");
            JSONObject user = new JSONObject();
            user.put("role", "user");
            user.put("content", conversation);
            messages.put(system);
            messages.put(user);
            req.put("messages", messages);
            req.put("temperature", 0.2);
            req.put("max_tokens", maxTokens);
            req.put("cache_prompt", true);
            byte[] body = req.toString().getBytes("UTF-8");
            java.io.OutputStream os = c.getOutputStream();
            os.write(body);
            os.flush();
            os.close();
            if (c.getResponseCode() != 200) return null;
            java.io.InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) bos.write(buf, 0, n);
            in.close();
            JSONObject resp = new JSONObject(bos.toString("UTF-8"));
            JSONArray choices = resp.optJSONArray("choices");
            if (choices == null || choices.length() == 0) return null;
            JSONObject msg = choices.optJSONObject(0).optJSONObject("message");
            if (msg == null) return null;
            return msg.optString("content", null);
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String postCompletion(String prompt, int nPredict) {
        java.net.HttpURLConnection c = null;
        try {
            java.net.URL url = new java.net.URL("http://127.0.0.1:"
                    + LlmServerService.PORT + "/completion");
            c = (java.net.HttpURLConnection) url.openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(180000);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            JSONObject req = new JSONObject();
            req.put("prompt", prompt);
            req.put("n_predict", nPredict);
            req.put("temperature", 0.2);
            req.put("cache_prompt", true);
            byte[] body = req.toString().getBytes("UTF-8");
            java.io.OutputStream os = c.getOutputStream();
            os.write(body);
            os.flush();
            os.close();
            if (c.getResponseCode() != 200) return null;
            java.io.InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) bos.write(buf, 0, n);
            in.close();
            JSONObject resp = new JSONObject(bos.toString("UTF-8"));
            return resp.optString("content", null);
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
