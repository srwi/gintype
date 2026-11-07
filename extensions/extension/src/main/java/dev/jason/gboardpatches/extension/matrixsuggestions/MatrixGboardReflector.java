package dev.jason.gboardpatches.extension.matrixsuggestions;

import android.content.Context;
import android.content.ContextWrapper;
import android.inputmethodservice.InputMethodService;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputConnection;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Encapsulates Gboard internal reflection for candidate extraction,
 * SoftKeyView invocation, and slot view inspection.
 * Uses resilient signature- and type-based fallbacks so future Gboard version
 * updates with renamed obfuscated fields/methods do not break functionality.
 */
public final class MatrixGboardReflector {
    private static final String TAG = "MatrixGboardReflector";

    private static boolean candidateFieldsLookupAttempted = false;
    private static Field candidateTextField = null;
    private static Field candidateTypeField = null;
    private static Field candidateAutoCorrectField = null;

    private MatrixGboardReflector() {}

    /**
     * Extracts candidates directly from the holder view's slots (0, 1, 2) where
     * Gboard has assigned them. Falls back to candidateListObj if slots are empty.
     */
    public static List<GboardMatrixSuggestionsRuntime.CandidateItem> extractSlotCandidatesFromHolder(
            ViewGroup holderView, Object fallbackListObj) {
        List<GboardMatrixSuggestionsRuntime.CandidateItem> items = new ArrayList<>();

        try {
            Method slotCountMethod = null;
            Method softKeyAtSlotMethod = null;
            Method candidateAtSlotMethod = null;
            Method extractFromKeyMethod = null;

            // Search by signature to survive obfuscation changes in new Gboard versions
            for (Method m : holderView.getClass().getMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType() == int.class && ("a".equals(m.getName()) || slotCountMethod == null)) {
                    slotCountMethod = m;
                } else if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == int.class && View.class.isAssignableFrom(m.getReturnType())) {
                    softKeyAtSlotMethod = m;
                } else if (m.getParameterCount() == 1 && "z".equals(m.getName())) {
                    extractFromKeyMethod = m;
                }
            }

            try {
                // Gboard slot candidate getter (often named 'R' taking int)
                candidateAtSlotMethod = holderView.getClass().getDeclaredMethod("R", int.class);
                candidateAtSlotMethod.setAccessible(true);
            } catch (Throwable ignored) {
                for (Method m : holderView.getClass().getDeclaredMethods()) {
                    if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == int.class
                            && m.getReturnType() != void.class && !View.class.isAssignableFrom(m.getReturnType())) {
                        m.setAccessible(true);
                        candidateAtSlotMethod = m;
                        break;
                    }
                }
            }

            int count = 0;
            if (slotCountMethod != null) {
                count = (Integer) slotCountMethod.invoke(holderView);
            }

            if (count > 0) {
                for (int slot = 0; slot < count; slot++) {
                    Object cand = null;
                    if (candidateAtSlotMethod != null) {
                        try {
                            cand = candidateAtSlotMethod.invoke(holderView, slot);
                        } catch (Throwable ignored) {
                        }
                    }
                    View softKey = (softKeyAtSlotMethod != null) ? (View) softKeyAtSlotMethod.invoke(holderView, slot) : null;
                    if (cand == null && softKey != null && extractFromKeyMethod != null) {
                        try {
                            cand = extractFromKeyMethod.invoke(null, softKey);
                        } catch (Throwable ignored) {
                        }
                    }

                    if (cand != null) {
                        String text = extractCandidateText(cand);
                        if (text != null && !text.trim().isEmpty()) {
                            boolean isRaw = extractCandidateIsRaw(cand);
                            boolean isAuto = extractCandidateIsAutoCorrect(cand);
                            boolean isPred = extractCandidateIsPrediction(cand);
                            String type = extractCandidateType(cand);
                            items.add(new GboardMatrixSuggestionsRuntime.CandidateItem(
                                    text, slot, isRaw, isAuto, isPred, type, cand, softKey));
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "extractSlotCandidatesFromHolder error: " + t.getMessage());
        }

        // Fallback: parse raw list if slot views are not populated yet
        if (items.isEmpty() && fallbackListObj != null) {
            items = extractCandidatesFromIterable(fallbackListObj);
        }

        return items;
    }

    /**
     * Commits a candidate via Gboard's native SoftKeyboardView.a(softKey).
     * Returns true if native invocation succeeded.
     */
    public static boolean commitCandidateNative(ViewGroup holderView, GboardMatrixSuggestionsRuntime.CandidateItem selectedItem) {
        try {
            ViewGroup softKeyboardView = findSoftKeyboardView(holderView);

            View softKey = selectedItem.softKey;
            if (softKey == null && selectedItem.slotIndex >= 0) {
                try {
                    for (Method m : holderView.getClass().getMethods()) {
                        if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == int.class && View.class.isAssignableFrom(m.getReturnType())) {
                            softKey = (View) m.invoke(holderView, selectedItem.slotIndex);
                            break;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }

            if (softKeyboardView != null && softKey != null && softKey.isAttachedToWindow()) {
                for (Method m : softKeyboardView.getClass().getMethods()) {
                    if (m.getParameterCount() == 1 && m.getParameterTypes()[0].isAssignableFrom(softKey.getClass())) {
                        m.setAccessible(true);
                        m.invoke(softKeyboardView, softKey);
                        Log.i(TAG, "commitCandidateNative: successfully invoked " + m.getName() + " on SoftKeyboardView");
                        return true;
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "commitCandidateNative error: " + t.getMessage());
        }
        return false;
    }

    /**
     * Suppresses Gboard's native quick toggle and access point views while MatrixBar is active.
     */
    public static void suppressQuickToggles(ViewGroup attachTarget, boolean suppress) {
        if (attachTarget == null) return;
        try {
            float alpha = suppress ? 0.0f : 1.0f;
            int vis = suppress ? View.INVISIBLE : View.VISIBLE;

            // 1. Siblings inside attachTarget (e.g. native LatinFixedCountCandidatesHolderView & AccessPointsBar)
            for (int i = 0; i < attachTarget.getChildCount(); i++) {
                View child = attachTarget.getChildAt(i);
                if (child instanceof MatrixSuggestionBarView) {
                    continue;
                }
                child.setAlpha(alpha);
                child.setVisibility(vis);
            }

            // 2. Siblings of attachTarget in parent header (if candidates and access points are in sibling containers)
            ViewGroup parent = (ViewGroup) attachTarget.getParent();
            if (parent != null) {
                for (int i = 0; i < parent.getChildCount(); i++) {
                    View sibling = parent.getChildAt(i);
                    if (sibling != attachTarget && !(sibling instanceof MatrixSuggestionBarView)) {
                        String name = sibling.getClass().getName();
                        if (name.contains("AccessPoint") || name.contains("accesspoint")
                                || name.contains("Header") || name.contains("Strip") || name.contains("Toolbar")) {
                            sibling.setAlpha(alpha);
                            sibling.setVisibility(vis);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "suppressQuickToggles error: " + t.getMessage());
        }
    }

    public static ViewGroup findSoftKeyboardView(ViewGroup holderView) {
        View p = holderView;
        while (p != null) {
            if (p.getClass().getName().contains("SoftKeyboardView")) {
                return (ViewGroup) p;
            }
            if (p.getParent() instanceof View) {
                p = (View) p.getParent();
            } else {
                break;
            }
        }
        return null;
    }

    public static InputConnection resolveInputConnectionViaHierarchy(View view) {
        try {
            if (view instanceof ViewGroup vg) {
                Object keyboard = findKeyboardController(vg);
                if (keyboard != null) {
                    for (Class<?> c = keyboard.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                        for (Field f : c.getDeclaredFields()) {
                            if (Context.class.isAssignableFrom(f.getType())) {
                                f.setAccessible(true);
                                Object zContext = f.get(keyboard);
                                if (zContext instanceof Context) {
                                    Context kCtx = (Context) zContext;
                                    while (kCtx instanceof ContextWrapper && !(kCtx instanceof InputMethodService)) {
                                        kCtx = ((ContextWrapper) kCtx).getBaseContext();
                                    }
                                    if (kCtx instanceof InputMethodService ims) {
                                        InputConnection ic = ims.getCurrentInputConnection();
                                        if (ic != null) return ic;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Object findKeyboardController(ViewGroup holderView) {
        try {
            for (Field f : holderView.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                Object obj = f.get(holderView);
                if (obj != null && obj.getClass().getName().contains("keyboard")) {
                    return obj;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static String extractCandidateText(Object item) {
        if (item == null) return null;
        ensureCandidateFields(item);
        if (candidateTextField != null) {
            try {
                Object textObj = candidateTextField.get(item);
                if (textObj instanceof CharSequence cs && cs.length() > 0) {
                    return cs.toString();
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    public static String extractCandidateType(Object item) {
        if (item == null) return "";
        ensureCandidateFields(item);
        if (candidateTypeField != null) {
            try {
                Object typeObj = candidateTypeField.get(item);
                if (typeObj instanceof Enum<?> e) {
                    return e.name();
                } else if (typeObj != null) {
                    return typeObj.toString();
                }
            } catch (Throwable ignored) {
            }
        }
        return "";
    }

    public static boolean extractCandidateIsPrediction(Object item) {
        if (item == null) return false;
        ensureCandidateFields(item);
        if (candidateTypeField != null) {
            try {
                Object typeObj = candidateTypeField.get(item);
                if (typeObj instanceof Enum<?> e) {
                    return e.ordinal() == 2 || "PREDICTION".equalsIgnoreCase(e.name()) || e.name().contains("PREDICTION");
                } else if (typeObj != null) {
                    String name = typeObj.toString();
                    return "PREDICTION".equalsIgnoreCase(name) || name.contains("PREDICTION");
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    public static boolean extractCandidateIsRaw(Object item) {
        if (item == null) return false;
        ensureCandidateFields(item);
        if (candidateTypeField != null) {
            try {
                Object typeObj = candidateTypeField.get(item);
                if (typeObj instanceof Enum<?> e) {
                    return e.ordinal() == 1 || "RAW".equalsIgnoreCase(e.name()) || e.name().contains("RAW");
                } else if (typeObj != null) {
                    String name = typeObj.toString();
                    return "RAW".equalsIgnoreCase(name) || name.contains("RAW");
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    public static boolean extractCandidateIsAutoCorrect(Object item) {
        if (item == null) return false;
        ensureCandidateFields(item);
        if (candidateAutoCorrectField != null) {
            try {
                return candidateAutoCorrectField.getBoolean(item);
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static void ensureCandidateFields(Object item) {
        if (candidateFieldsLookupAttempted) return;
        candidateFieldsLookupAttempted = true;

        // 1. Text field lookup (starts with 'a', falls back to CharSequence field)
        try {
            candidateTextField = item.getClass().getDeclaredField("a");
            candidateTextField.setAccessible(true);
        } catch (Throwable ignored) {
            for (Field f : item.getClass().getDeclaredFields()) {
                if (CharSequence.class.isAssignableFrom(f.getType())) {
                    candidateTextField = f;
                    candidateTextField.setAccessible(true);
                    break;
                }
            }
        }

        // 2. Candidate type enum lookup (starts with 'e', falls back to Enum field)
        try {
            candidateTypeField = item.getClass().getDeclaredField("e");
            candidateTypeField.setAccessible(true);
        } catch (Throwable ignored) {
            for (Field f : item.getClass().getDeclaredFields()) {
                if (f.getType().isEnum()) {
                    candidateTypeField = f;
                    candidateTypeField.setAccessible(true);
                    break;
                }
            }
        }

        // 3. AutoCorrect flag lookup (starts with 'g', falls back to boolean field)
        try {
            candidateAutoCorrectField = item.getClass().getDeclaredField("g");
            candidateAutoCorrectField.setAccessible(true);
        } catch (Throwable ignored) {
            for (Field f : item.getClass().getDeclaredFields()) {
                if (f.getType() == boolean.class) {
                    candidateAutoCorrectField = f;
                    candidateAutoCorrectField.setAccessible(true);
                    break;
                }
            }
        }
    }

    public static List<GboardMatrixSuggestionsRuntime.CandidateItem> extractCandidatesFromIterable(Object candidateListObj) {
        if (!(candidateListObj instanceof Iterable<?> iterable)) {
            return Collections.emptyList();
        }

        List<GboardMatrixSuggestionsRuntime.CandidateItem> result = new ArrayList<>();
        int slotIndex = 0;

        for (Object item : iterable) {
            if (item == null) continue;
            String text = extractCandidateText(item);
            if (text == null || text.trim().isEmpty()) {
                slotIndex++;
                continue;
            }
            boolean isRaw = extractCandidateIsRaw(item);
            boolean isAutoCorrect = extractCandidateIsAutoCorrect(item);
            boolean isPred = extractCandidateIsPrediction(item);
            String type = extractCandidateType(item);
            result.add(new GboardMatrixSuggestionsRuntime.CandidateItem(
                    text, slotIndex, isRaw, isAutoCorrect, isPred, type, item, null));
            slotIndex++;
        }

        return result;
    }
}
