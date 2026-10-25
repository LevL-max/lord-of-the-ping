package com.lordoftheping.home;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * A separate app entry, «Недавние», that opens the home screen's recents panel. The SHIELD remote can open an app
 * on holding its menu button, while its double Home shows NVIDIA's own switcher, which unloads nothing.
 */
public class RecentsShortcut extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        // Through the HOME intent, so the running home screen opens the panel instead of a second copy starting.
        try {
            startActivity(new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .setPackage(getPackageName())
                    .putExtra(HomeActivity.EXTRA_OPEN_RECENTS, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ignored) {
        }
        finish();
    }
}
