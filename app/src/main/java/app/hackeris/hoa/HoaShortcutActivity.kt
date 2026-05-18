package app.hackeris.hoa

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast

/**
 * Relay activity for home screen shortcuts.
 *
 * Shortcuts pin a PendingIntent targeting this activity.  When launched,
 * it allocates a process slot and forwards to HoaAbilityActivity{N},
 * then finishes.  This indirection avoids pinning a slot-bound activity
 * (whose process may be stale by the time the user taps the shortcut).
 */
class HoaShortcutActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val bundleName = intent.getStringExtra("BUNDLE_NAME") ?: return finish()
        val moduleName = intent.getStringExtra("MODULE_NAME") ?: return finish()
        val abilityName = intent.getStringExtra("ABILITY_NAME") ?: return finish()

        val slot = ProcessSlotManager.allocateSlot(this)
        if (slot < 0) {
            val maxSlots = ProcessSlotManager.MAX_SLOTS
            Toast.makeText(
                this,
                getString(R.string.dialog_no_slots_msg, maxSlots),
                Toast.LENGTH_LONG
            ).show()
            Log.w(TAG, "Shortcut launch failed: all $maxSlots slots occupied")
            finish()
            return
        }

        Log.e(TAG, "Shortcut relay: $bundleName/$moduleName/$abilityName → slot $slot")

        val forward = Intent().apply {
            setClassName(packageName, "${packageName}.HoaAbilityActivity$slot")
            putExtra("BUNDLE_NAME", bundleName)
            putExtra("MODULE_NAME", moduleName)
            putExtra("ABILITY_NAME", abilityName)
            putExtra("PROCESS_SLOT", slot)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                     Intent.FLAG_ACTIVITY_NEW_DOCUMENT or
                     Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        }
        startActivity(forward)
        finish()
    }

    companion object {
        private const val TAG = "HOA.Shortcut"
    }
}
