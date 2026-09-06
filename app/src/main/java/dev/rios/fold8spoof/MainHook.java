package dev.rios.fold8spoof;

import android.content.res.Resources;

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

        if (ANDROID_PACKAGE.equals(lp.packageName)) {
            hookScsOndeviceCapability(lp);
            hookSummaryLanguageNormalization(lp);
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
}
