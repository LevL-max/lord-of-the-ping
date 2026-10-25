package com.lordoftheping.home;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;

/**
 * Makes the remote's gear key open Settings in any app, and the Skyworth remote's input key open the inputs. The
 * firmwares (TCL, Skyworth) hand those keys to Google TV parts that are disabled here; an accessibility key filter
 * sees them first. It receives key events only: no window content (see res/xml/key_service.xml).
 */
public class KeyService extends AccessibilityService {
    /** The standard Android TV inputs panel (MediaTek's on the Digma). */
    private static final String ACTION_VIEW_INPUTS = "com.android.tv.action.VIEW_INPUTS";

    /** Both remotes so far (TCL RC813 scan 233, Skyworth scan 240) send NOTIFICATION for the gear key. */
    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (isSkyworthInputKey(e)) {
            if (e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()) open(new Intent(ACTION_VIEW_INPUTS));
            return true;
        }
        if (code != KeyEvent.KEYCODE_SETTINGS && code != KeyEvent.KEYCODE_NOTIFICATION) return false;
        if (e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()
                && e.getEventTime() - e.getDownTime() < ViewConfiguration.getLongPressTimeout()) {
            open(new Intent(Settings.ACTION_SETTINGS));
        }
        return true;
    }

    /**
     * The Skyworth (Digma) remote's input key: BUTTON_16 with scan code 383 (79 over IR), per the firmware's
     * skw_hot-key-info.ini. The firmware's key service asks the Google TV launcher about kids mode before opening
     * the inputs and crashes while that launcher is disabled, so the key did nothing.
     */
    private static boolean isSkyworthInputKey(KeyEvent e) {
        return e.getKeyCode() == KeyEvent.KEYCODE_BUTTON_16 && (e.getScanCode() == 383 || e.getScanCode() == 79);
    }

    private void open(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Key filtering only; no events are requested beyond the minimum.
    }

    @Override
    public void onInterrupt() {
    }
}
