package dev.jason.gboardpatches.extension.matrixsuggestions;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Configuration;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.inputmethodservice.InputMethodService;
import android.util.Log;
import android.util.TypedValue;
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
     * Checks if a candidate item represents an Undo or Delete recovery candidate.
     */
    public static boolean isUndoCandidate(GboardMatrixSuggestionsRuntime.CandidateItem ci) {
        if (ci == null) return false;
        String type = ci.candidateType != null ? ci.candidateType.toUpperCase() : "";
        if (type.contains("UNDO") || type.contains("DELETE") || type.contains("RESTORE") || type.contains("REVERT")) {
            return true;
        }
        if (ci.text != null) {
            String t = ci.text.trim();
            if (t.equalsIgnoreCase("Undo") || t.equalsIgnoreCase("Rückgängig")
                    || t.equalsIgnoreCase("Deshacer") || t.equalsIgnoreCase("Annuler")
                    || t.equalsIgnoreCase("Annulla") || t.equalsIgnoreCase("Desfazer")
                    || t.equalsIgnoreCase("Отменить") || t.equals("復原") || t.equals("撤销") || t.equals("元に戻す")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true if any candidate in items is an Undo / recovery candidate.
     */
    public static boolean hasUndoCandidate(List<GboardMatrixSuggestionsRuntime.CandidateItem> items) {
        if (items == null || items.isEmpty()) return false;
        for (GboardMatrixSuggestionsRuntime.CandidateItem ci : items) {
            if (isUndoCandidate(ci)) return true;
        }
        return false;
    }

    /**
     * Checks if a view represents the Undo action (button, chip, or banner).
     */
    public static boolean isUndoView(View view) {
        if (view == null) return false;
        try {
            CharSequence cd = view.getContentDescription();
            if (cd != null) {
                String s = cd.toString().toLowerCase();
                if (s.contains("undo") || s.contains("rückgängig") || s.contains("deshacer")
                        || s.contains("annuler") || s.contains("annulla") || s.contains("desfazer")
                        || s.contains("отменить") || s.contains("復原") || s.contains("撤销") || s.contains("元に戻す")) {
                    return true;
                }
            }
            Object tag = view.getTag();
            if (tag instanceof String t) {
                String s = t.toLowerCase();
                if (s.contains("undo")) {
                    return true;
                }
            }
            int id = view.getId();
            if (id != View.NO_ID && id != 0) {
                try {
                    String resName = view.getResources().getResourceEntryName(id);
                    if (resName != null && resName.toLowerCase().contains("undo")) {
                        return true;
                    }
                } catch (Throwable ignored) {
                }
            }
            String className = view.getClass().getName().toLowerCase();
            if (className.contains("undo") && !className.contains("undoredoaccesspoint")) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * Returns true if view is an Undo view or contains an Undo view among its descendants.
     */
    public static boolean containsUndoView(View view) {
        if (view == null) return false;
        if (isUndoView(view)) return true;
        if (view instanceof ViewGroup vg) {
            for (int i = 0; i < vg.getChildCount(); i++) {
                if (containsUndoView(vg.getChildAt(i))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Scans the view hierarchy to determine if an Undo button, Undo chip, or Undo banner
     * is actively visible (e.g. after swipe-delete/glide-delete).
     */
    public static boolean isUndoViewActive(View root) {
        if (root == null) return false;
        try {
            if (findUndoViewInHierarchy(root)) {
                return true;
            }
            if (root instanceof ViewGroup vg) {
                ViewGroup softKeyboard = findSoftKeyboardView(vg);
                if (softKeyboard != null && softKeyboard != root && findUndoViewInHierarchy(softKeyboard)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean findUndoViewInHierarchy(View view) {
        if (view == null || view.getVisibility() != View.VISIBLE) return false;
        try {
            if (isUndoView(view)) {
                return true;
            }
            if (view instanceof ViewGroup vg) {
                for (int i = 0; i < vg.getChildCount(); i++) {
                    if (findUndoViewInHierarchy(vg.getChildAt(i))) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * Comprehensive check for Undo state: either by candidate types or active view presence.
     */
    public static boolean isUndoActive(ViewGroup holderView, List<GboardMatrixSuggestionsRuntime.CandidateItem> items) {
        if (hasUndoCandidate(items)) return true;
        return isUndoViewActive(holderView);
    }

    /**
     * Applies an opaque background to MatrixSuggestionBarView matching the keyboard surface.
     * Prevents any underlying views or text from bleeding or showing through.
     */
    public static void applyKeyboardBackground(View barView, ViewGroup holderView) {
        if (barView == null) return;
        try {
            // 1. Try to copy background from holderView or attachTarget
            if (holderView != null) {
                if (holderView.getBackground() != null) {
                    barView.setBackground(holderView.getBackground().getConstantState().newDrawable().mutate());
                    return;
                }
                View parent = (View) holderView.getParent();
                if (parent != null && parent.getBackground() != null) {
                    barView.setBackground(parent.getBackground().getConstantState().newDrawable().mutate());
                    return;
                }
            }

            // 2. Try SoftKeyboardView
            ViewGroup softKeyboard = findSoftKeyboardView(holderView);
            if (softKeyboard != null && softKeyboard.getBackground() != null) {
                Drawable bg = softKeyboard.getBackground();
                if (bg instanceof ColorDrawable cd) {
                    barView.setBackgroundColor(cd.getColor());
                    return;
                }
                barView.setBackground(bg.getConstantState().newDrawable().mutate());
                return;
            }

            // 3. Resolve theme surface color
            Context context = barView.getContext();
            TypedValue tv = new TypedValue();
            if (context.getTheme().resolveAttribute(android.R.attr.colorBackground, tv, true)) {
                if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT && tv.type <= TypedValue.TYPE_LAST_COLOR_INT && tv.data != 0) {
                    barView.setBackgroundColor(tv.data);
                    return;
                }
            }

            // 4. Default adaptive surface colors (Google dark grey / light grey)
            boolean isDark = (barView.getContext().getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            barView.setBackgroundColor(isDark ? 0xFF202124 : 0xFFF8F9FA);
        } catch (Throwable t) {
            Log.w(TAG, "applyKeyboardBackground error: " + t.getMessage());
            barView.setBackgroundColor(0xFF202124);
        }
    }

    public static ViewGroup findHeaderContainer(View view) {
        if (view == null) return null;
        View cur = view;
        while (cur != null) {
            String name = cur.getClass().getName();
            if (name.contains("ConstraintHeaderView") || name.contains("HeaderView") || name.contains("header")) {
                if (cur instanceof ViewGroup vg) return vg;
            }
            if (cur.getParent() instanceof View parentView) {
                String parentName = parentView.getClass().getName();
                if (parentName.contains("SoftKeyboardView") || parentName.contains("KeyboardViewHolder")
                        || parentName.contains("InputView") || parentName.contains("MainKeyboardView")) {
                    if (cur instanceof ViewGroup vg) return vg;
                }
                cur = parentView;
            } else {
                break;
            }
        }
        return (view.getParent() instanceof ViewGroup vg) ? vg : null;
    }

    public static boolean isVoiceButton(View view) {
        if (view == null) return false;
        try {
            CharSequence cd = view.getContentDescription();
            if (cd != null) {
                String s = cd.toString().toLowerCase();
                if (s.contains("voice") || s.contains("sprache") || s.contains("mic")
                        || s.contains("diktier") || s.contains("vocal") || s.contains("voix")
                        || s.contains("голос") || s.contains("語音") || s.contains("语音") || s.contains("音声")) {
                    return true;
                }
            }
            int id = view.getId();
            if (id != View.NO_ID && id != 0) {
                try {
                    String resName = view.getResources().getResourceEntryName(id).toLowerCase();
                    if (resName.contains("voice") || resName.contains("mic")) {
                        return true;
                    }
                } catch (Throwable ignored) {
                }
            }
            Object tag = view.getTag();
            if (tag instanceof String t && t.toLowerCase().contains("voice")) {
                return true;
            }
            String className = view.getClass().getName().toLowerCase();
            if ((className.contains("voice") || className.contains("mic")) && !className.contains("setting")) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public static View findVoiceButton(View headerRoot) {
        if (headerRoot == null) return null;
        try {
            // 1. Recursive search for voice button by characteristics
            View match = findVoiceButtonRecursive(headerRoot);
            if (match != null) {
                // If match is a nested descendant inside a direct child of headerRoot,
                // return the direct child so LayoutParams match headerRoot
                View cur = match;
                while (cur.getParent() instanceof View p && p != headerRoot) {
                    cur = p;
                }
                return cur;
            }

            // 2. Structural search: In ConstraintHeaderViewImpl, find the rightmost clickable child
            if (headerRoot instanceof ViewGroup vg) {
                View rightmost = null;
                int maxRight = -1;
                for (int i = 0; i < vg.getChildCount(); i++) {
                    View child = vg.getChildAt(i);
                    if (child.getVisibility() == View.VISIBLE) {
                        String name = child.getClass().getName();
                        if (!name.contains("CandidatesHolder") && !name.contains("Matrix") && !name.contains("FixedCount")) {
                            if (child.getRight() > maxRight) {
                                maxRight = child.getRight();
                                rightmost = child;
                            }
                        }
                    }
                }
                if (rightmost != null && maxRight > 0) {
                    return rightmost;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "findVoiceButton error: " + t.getMessage());
        }
        return null;
    }

    private static View findVoiceButtonRecursive(View view) {
        if (view == null) return null;
        if (isVoiceButton(view)) return view;
        if (view instanceof ViewGroup vg) {
            for (int i = 0; i < vg.getChildCount(); i++) {
                View found = findVoiceButtonRecursive(vg.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * Manages header views visibility cleanly without tampering with parent ConstraintLayout.
     * When showMatrixBar is true:
     *   - holderView is set to INVISIBLE (preserves layout measurement while hiding native text and touch)
     *   - attachTarget is brought to front with its opaque MatrixBar covering siblings
     *   - sibling quick toggles inside attachTarget's direct container are set to GONE
     * When showMatrixBar is false:
     *   - if Undo is active: holderView is GONE (hides deleted text under Undo button)
     *   - if Undo is NOT active: holderView is restored to VISIBLE
     *   - sibling quick toggles in container are restored to VISIBLE
     * Crucially, we NEVER touch views in the top-level ConstraintLayout (parent's parent),
     * allowing Gboard to natively manage full topbar width without right-shift or layout distortion.
     */
    public static void updateHeaderViewsVisibility(ViewGroup attachTarget, ViewGroup holderView, boolean showMatrixBar) {
        if (attachTarget == null) return;
        try {
            // Ensure attachTarget fills full available width in container
            ViewGroup.LayoutParams lpTarget = attachTarget.getLayoutParams();
            if (lpTarget != null && lpTarget.width != ViewGroup.LayoutParams.MATCH_PARENT) {
                lpTarget.width = ViewGroup.LayoutParams.MATCH_PARENT;
                attachTarget.setLayoutParams(lpTarget);
            }

            if (showMatrixBar) {
                if (holderView != null) {
                    holderView.setAlpha(0.0f);
                    holderView.setVisibility(View.INVISIBLE);
                }

                ViewGroup parent = (ViewGroup) attachTarget.getParent();
                if (parent != null) {
                    if (parent.getChildCount() > 0 && parent.getChildAt(parent.getChildCount() - 1) != attachTarget) {
                        attachTarget.bringToFront();
                    }
                    for (int i = 0; i < parent.getChildCount(); i++) {
                        View sibling = parent.getChildAt(i);
                        if (sibling != attachTarget && !(sibling instanceof MatrixSuggestionBarView)) {
                            String name = sibling.getClass().getName();
                            if (name.contains("AccessPoint") || name.contains("accesspoint")
                                    || name.contains("Header") || name.contains("Strip") || name.contains("Toolbar")) {
                                sibling.setVisibility(View.GONE);
                            }
                        }
                    }
                }
            } else {
                boolean undoActive = isUndoViewActive(attachTarget);
                if (holderView != null) {
                    if (undoActive) {
                        holderView.setAlpha(0.0f);
                        holderView.setVisibility(View.GONE);
                    } else {
                        holderView.setAlpha(1.0f);
                        holderView.setVisibility(View.VISIBLE);
                    }
                }

                ViewGroup parent = (ViewGroup) attachTarget.getParent();
                if (parent != null) {
                    for (int i = 0; i < parent.getChildCount(); i++) {
                        View sibling = parent.getChildAt(i);
                        if (sibling != attachTarget && !(sibling instanceof MatrixSuggestionBarView)) {
                            String name = sibling.getClass().getName();
                            if (name.contains("AccessPoint") || name.contains("accesspoint")
                                    || name.contains("Header") || name.contains("Strip") || name.contains("Toolbar")) {
                                sibling.setAlpha(1.0f);
                                if (sibling.getVisibility() == View.GONE) {
                                    sibling.setVisibility(View.VISIBLE);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "updateHeaderViewsVisibility error: " + t.getMessage());
        }
    }

    /**
     * Convenience delegate for updateHeaderViewsVisibility.
     */
    public static void suppressQuickToggles(ViewGroup attachTarget, boolean suppress) {
        ViewGroup holder = null;
        if (attachTarget != null) {
            for (int i = 0; i < attachTarget.getChildCount(); i++) {
                View child = attachTarget.getChildAt(i);
                if (child.getClass().getName().contains("CandidatesHolderView") || child.getClass().getName().contains("FixedCount")) {
                    holder = (ViewGroup) child;
                    break;
                }
            }
        }
        updateHeaderViewsVisibility(attachTarget, holder, suppress);
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
