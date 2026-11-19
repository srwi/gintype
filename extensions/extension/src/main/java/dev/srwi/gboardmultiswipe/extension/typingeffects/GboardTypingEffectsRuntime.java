package dev.srwi.gboardmultiswipe.extension.typingeffects;

import android.content.Context;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

/**
 * Runtime hook entry points for the Typing Effects patch.
 *
 * <p>Hooked into:
 * <ul>
 *   <li>{@code SoftKeyboardView.dispatchTouchEvent(MotionEvent)} — captures all 1-finger and
 *       multi-finger touch events in exact keyboard view coordinates.</li>
 *   <li>{@code SoftKeyboardView.onDetachedFromWindow()} — cleans up overlay drawables and stops
 *       animation loops cleanly.</li>
 *   <li>{@code GestureOverlayView.onDraw(Canvas)} — optionally suppresses Gboard's stock swipe
 *       trail when Typing Effects' custom Rainbow Swipe Trail is active.</li>
 * </ul>
 */
public final class GboardTypingEffectsRuntime {

    private static final String TAG = "GboardTypingEffects";
    private static final long CONFIG_REFRESH_INTERVAL_MS = 350L;

    private static final WeakHashMap<View, OverlayController> CONTROLLERS =
            new WeakHashMap<View, OverlayController>();

    private static volatile TypingEffectsConfig cachedConfig = TypingEffectsConfig.defaults();
    private static volatile long lastConfigReadMs = 0L;

    private GboardTypingEffectsRuntime() {
    }

    public static void invalidateCachedConfig() {
        lastConfigReadMs = 0L;
    }

    /**
     * Called at the entry of {@code SoftKeyboardView.dispatchTouchEvent(MotionEvent)}.
     */
    public static void onKeyboardTouchEvent(Object keyboardViewObj, MotionEvent event) {
        if (!(keyboardViewObj instanceof View) || event == null) {
            return;
        }
        try {
            View view = (View) keyboardViewObj;
            TypingEffectsConfig cfg = resolveConfig(view.getContext(),
                    event.getActionMasked() == MotionEvent.ACTION_DOWN);
            if (!cfg.hasAnyVisualEffectEnabled()) {
                OverlayController existing = CONTROLLERS.get(view);
                if (existing != null) {
                    existing.setConfig(cfg);
                }
                return;
            }

            OverlayController controller = getOrCreateController(view);
            controller.setConfig(cfg);
            controller.onMotionEvent(event);
        } catch (Throwable t) {
            Log.w(TAG, "Error handling keyboard touch event for typing effects", t);
        }
    }

    /**
     * Called in {@code SoftKeyboardView.onDetachedFromWindow()}.
     */
    public static void onKeyboardDetached(Object keyboardViewObj) {
        if (!(keyboardViewObj instanceof View)) {
            return;
        }
        try {
            View view = (View) keyboardViewObj;
            OverlayController controller = CONTROLLERS.remove(view);
            if (controller != null) {
                controller.detach();
            }
        } catch (Throwable t) {
            Log.w(TAG, "Error detaching typing effects overlay", t);
        }
    }

    /**
     * Called at the start of {@code GestureOverlayView.onDraw(Canvas)}.
     *
     * @return {@code true} to skip Gboard's stock swipe trail when our custom Rainbow Swipe Trail
     *         is active (covering both 1-finger swipe and MultiSwipe).
     */
    public static boolean shouldSuppressStockGestureTrail(Object gestureOverlayViewObj) {
        try {
            Context context = gestureOverlayViewObj instanceof View
                    ? ((View) gestureOverlayViewObj).getContext()
                    : null;
            TypingEffectsConfig cfg = resolveConfig(context, false);
            return cfg.enabled && cfg.rainbowTrailEnabled;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static TypingEffectsConfig resolveConfig(Context context, boolean allowRefresh) {
        long now = SystemClock.uptimeMillis();
        if (context != null && (lastConfigReadMs == 0L
                || (allowRefresh && now - lastConfigReadMs >= CONFIG_REFRESH_INTERVAL_MS)
                || now - lastConfigReadMs >= CONFIG_REFRESH_INTERVAL_MS * 3L)) {
            cachedConfig = GboardTypingEffectsSettings.readConfig(context);
            lastConfigReadMs = now;
        }
        return cachedConfig;
    }

    private static OverlayController getOrCreateController(View view) {
        OverlayController controller = CONTROLLERS.get(view);
        if (controller == null) {
            controller = new OverlayController(view);
            CONTROLLERS.put(view, controller);
        }
        return controller;
    }

    private static final class OverlayController {
        private final WeakReference<View> viewRef;
        private final TypingEffectsEngine engine;
        private final TypingEffectsOverlayDrawable drawable;
        private final Rect tempKeyRect = new Rect();
        private boolean attached = false;
        private boolean animating = false;
        private int lastBoundsW = -1;
        private int lastBoundsH = -1;

        private final Runnable frameRunnable = new Runnable() {
            @Override
            public void run() {
                animating = false;
                View view = viewRef.get();
                if (view == null || !view.isAttachedToWindow()) {
                    detach();
                    return;
                }
                syncBounds(view);
                long nowMs = SystemClock.uptimeMillis();
                boolean stillActive = engine.step(nowMs);
                drawable.invalidateSelf();
                if (stillActive) {
                    scheduleNextFrame(view);
                }
            }
        };

        OverlayController(View view) {
            this.viewRef = new WeakReference<View>(view);
            float density = view.getResources() != null
                    ? view.getResources().getDisplayMetrics().density
                    : 2.5f;
            this.engine = new TypingEffectsEngine(density);
            this.drawable = new TypingEffectsOverlayDrawable(engine);
        }

        void setConfig(TypingEffectsConfig cfg) {
            engine.setConfig(cfg);
            if (!cfg.hasAnyVisualEffectEnabled() && animating) {
                engine.clearAll();
                drawable.invalidateSelf();
            }
        }

        void onMotionEvent(MotionEvent event) {
            View view = viewRef.get();
            if (view == null) return;

            ensureAttached(view);
            syncBounds(view);

            long nowMs = SystemClock.uptimeMillis();
            int action = event.getActionMasked();

            switch (action) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN: {
                    int idx = event.getActionIndex();
                    int pointerId = event.getPointerId(idx);
                    float x = event.getX(idx);
                    float y = event.getY(idx);
                    engine.onPointerDown(pointerId, x, y, nowMs);
                    notifyTouchedKey(view, x, y, nowMs);
                    break;
                }
                case MotionEvent.ACTION_MOVE: {
                    int pointerCount = event.getPointerCount();
                    int historySize = event.getHistorySize();
                    for (int p = 0; p < pointerCount; p++) {
                        int pointerId = event.getPointerId(p);
                        for (int h = 0; h < historySize; h++) {
                            engine.onPointerMove(
                                    pointerId,
                                    event.getHistoricalX(p, h),
                                    event.getHistoricalY(p, h),
                                    nowMs);
                        }
                        float x = event.getX(p);
                        float y = event.getY(p);
                        engine.onPointerMove(pointerId, x, y, nowMs);
                        notifyTouchedKey(view, x, y, nowMs);
                    }
                    break;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP: {
                    int idx = event.getActionIndex();
                    int pointerId = event.getPointerId(idx);
                    engine.onPointerUp(pointerId, event.getX(idx), event.getY(idx), nowMs);
                    break;
                }
                case MotionEvent.ACTION_CANCEL: {
                    engine.onCancel(nowMs);
                    break;
                }
                default:
                    break;
            }

            if (engine.hasActiveEffects()) {
                drawable.invalidateSelf();
                scheduleNextFrame(view);
            }
        }

        private void notifyTouchedKey(View root, float x, float y, long nowMs) {
            if (!engine.getConfig().keyHighlightEnabled) {
                return;
            }
            View keyView = findSoftKeyAt(root, x, y, 0, 0, tempKeyRect);
            if (keyView != null) {
                engine.onKeyTouched(
                        System.identityHashCode(keyView),
                        tempKeyRect.left,
                        tempKeyRect.top,
                        tempKeyRect.right,
                        tempKeyRect.bottom,
                        nowMs);
            }
        }

        private static View findSoftKeyAt(
                View node,
                float x,
                float y,
                int offsetX,
                int offsetY,
                Rect outRect) {
            if (node == null || node.getVisibility() != View.VISIBLE) {
                return null;
            }
            int w = node.getWidth();
            int h = node.getHeight();
            if (w <= 0 || h <= 0) {
                return null;
            }
            if (x < offsetX || x > offsetX + w || y < offsetY || y > offsetY + h) {
                return null;
            }
            String className = node.getClass().getName();
            if (className.endsWith("SoftKeyView")) {
                outRect.set(offsetX, offsetY, offsetX + w, offsetY + h);
                return node;
            }
            if (node instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) node;
                int childCount = group.getChildCount();
                int baseX = offsetX - group.getScrollX();
                int baseY = offsetY - group.getScrollY();
                for (int i = childCount - 1; i >= 0; i--) {
                    View child = group.getChildAt(i);
                    if (child == null || child.getVisibility() != View.VISIBLE) {
                        continue;
                    }
                    int childLeft = baseX + child.getLeft() + Math.round(child.getTranslationX());
                    int childTop = baseY + child.getTop() + Math.round(child.getTranslationY());
                    View hit = findSoftKeyAt(child, x, y, childLeft, childTop, outRect);
                    if (hit != null) {
                        return hit;
                    }
                }
            }
            return null;
        }

        private void ensureAttached(View view) {
            if (attached) return;
            try {
                view.getOverlay().add(drawable);
                attached = true;
            } catch (Throwable t) {
                Log.w(TAG, "Unable to attach typing effects overlay drawable", t);
            }
        }

        private void syncBounds(View view) {
            int w = view.getWidth();
            int h = view.getHeight();
            if (w > 0 && h > 0 && (w != lastBoundsW || h != lastBoundsH)) {
                drawable.setBounds(0, 0, w, h);
                lastBoundsW = w;
                lastBoundsH = h;
            }
        }

        private void scheduleNextFrame(View view) {
            if (animating) return;
            animating = true;
            view.postOnAnimation(frameRunnable);
        }

        void detach() {
            View view = viewRef.get();
            if (view != null) {
                view.removeCallbacks(frameRunnable);
                if (attached) {
                    try {
                        view.getOverlay().remove(drawable);
                    } catch (Throwable ignored) {
                    }
                }
            }
            animating = false;
            attached = false;
            engine.clearAll();
        }
    }
}
