package dev.busung.s25uroot

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * The tap feedback every press in this app answers with.
 *
 * One definition rather than one per screen: it was a private copy in the main window and another in the
 * run window, and anything shared by those two - like the button that returns a long screen to its top -
 * needed a third. Confirmation where the platform has it, and the older long-press pulse below API 30
 * where it does not.
 */
internal fun clickHaptic(view: View) {
    view.performHapticFeedback(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.LONG_PRESS
        },
    )
}
