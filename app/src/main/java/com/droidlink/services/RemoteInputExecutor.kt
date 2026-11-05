package com.droidlink.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import com.droidlink.domain.model.AppEntry
import com.droidlink.domain.model.Scope
import com.droidlink.network.KeyAction
import com.droidlink.network.MsgType
import com.droidlink.network.NavAction
import com.droidlink.network.RemoteMessage
import com.droidlink.security.InputValidator
import com.droidlink.security.SessionGuard

/**
 * Turns authenticated remote messages into Android accessibility actions.
 * Safety rules enforced here (in addition to the relay's scope checks):
 *  - nothing runs unless the [SessionGuard] holds an unexpired CONTROL grant;
 *  - DroidLink's own screens (approvals, emergency disconnect) cannot be operated remotely;
 *  - text is never typed into password fields and typed text is never logged or stored.
 */
class RemoteInputExecutor(private val context: Context, private val guard: SessionGuard) {

    sealed interface Outcome {
        data object Ok : Outcome
        data class Denied(val reason: String) : Outcome
        data class Unsupported(val reason: String) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    fun isServiceEnabled(): Boolean = DroidLinkAccessibilityService.instance != null

    fun execute(msg: RemoteMessage): Outcome {
        if (!guard.isAllowed(Scope.CONTROL)) return Outcome.Denied("control_not_authorised")
        val svc = DroidLinkAccessibilityService.instance ?: return Outcome.Unsupported("accessibility_service_disabled")
        return try {
            when (msg.t) {
                MsgType.NAV -> nav(svc, msg.a)
                MsgType.TAP, MsgType.DOUBLE_TAP, MsgType.LONG_PRESS, MsgType.SWIPE, MsgType.SCROLL,
                MsgType.TEXT, MsgType.KEY, MsgType.LAUNCH,
                -> if (isOwnAppInForeground(svc)) Outcome.Denied("droidlink_screen_protected") else interact(svc, msg)
                else -> Outcome.Unsupported("unknown_command")
            }
        } catch (e: SecurityException) {
            Outcome.Failed("blocked_by_system")
        } catch (e: RuntimeException) {
            Outcome.Failed("execution_error")
        }
    }

    private fun interact(svc: AccessibilityService, msg: RemoteMessage): Outcome {
        val metrics = ScreenSize.of(context)
        return when (msg.t) {
            MsgType.TAP -> gesture(svc, tapStroke(msg, metrics, 0))
            MsgType.DOUBLE_TAP -> gesture(svc, tapStroke(msg, metrics, 0), tapStroke(msg, metrics, 140))
            MsgType.LONG_PRESS -> gesture(
                svc,
                tapStroke(msg, metrics, 0, ViewConfiguration.getLongPressTimeout() + 200L),
            )
            MsgType.SWIPE -> {
                val path = Path().apply {
                    moveTo(Geometry.toPixel(msg.x ?: return Outcome.Failed("bad_args"), metrics.first), Geometry.toPixel(msg.y ?: return Outcome.Failed("bad_args"), metrics.second))
                    lineTo(Geometry.toPixel(msg.x2 ?: return Outcome.Failed("bad_args"), metrics.first), Geometry.toPixel(msg.y2 ?: return Outcome.Failed("bad_args"), metrics.second))
                }
                gesture(svc, GestureDescription.StrokeDescription(path, 0, Geometry.clampDuration(msg.d, 350, 100, 3000)))
            }
            MsgType.SCROLL -> {
                val p = Geometry.scrollPath(msg.a.orEmpty(), metrics.first, metrics.second) ?: return Outcome.Failed("bad_args")
                val path = Path().apply { moveTo(p[0], p[1]); lineTo(p[2], p[3]) }
                gesture(svc, GestureDescription.StrokeDescription(path, 0, Geometry.clampDuration(msg.d, 300, 100, 1500)))
            }
            MsgType.TEXT -> typeText(svc, msg.s.orEmpty())
            MsgType.KEY -> key(svc, msg.k.orEmpty())
            MsgType.LAUNCH -> launch(svc, msg.pkg.orEmpty())
            else -> Outcome.Unsupported("unknown_command")
        }
    }

    // ---- gestures ---------------------------------------------------------------------------------
    private fun tapStroke(msg: RemoteMessage, size: Pair<Int, Int>, startMs: Long, duration: Long = 50): GestureDescription.StrokeDescription {
        val path = Path().apply { moveTo(Geometry.toPixel(msg.x ?: 0.5f, size.first), Geometry.toPixel(msg.y ?: 0.5f, size.second)) }
        return GestureDescription.StrokeDescription(path, startMs, duration)
    }

    private fun gesture(svc: AccessibilityService, vararg strokes: GestureDescription.StrokeDescription): Outcome {
        val builder = GestureDescription.Builder()
        strokes.forEach { builder.addStroke(it) }
        return if (svc.dispatchGesture(builder.build(), null, null)) Outcome.Ok else Outcome.Failed("gesture_rejected")
    }

    private fun nav(svc: AccessibilityService, action: String?): Outcome {
        val global = when (action) {
            NavAction.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
            NavAction.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
            NavAction.RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
            NavAction.NOTIFICATIONS -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            NavAction.QUICK_SETTINGS -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            else -> return Outcome.Failed("bad_args")
        }
        return if (svc.performGlobalAction(global)) Outcome.Ok else Outcome.Failed("action_rejected")
    }

    // ---- keyboard ---------------------------------------------------------------------------------
    private fun focusedField(svc: AccessibilityService): AccessibilityNodeInfo? =
        svc.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

    @Suppress("DEPRECATION")
    private fun typeText(svc: AccessibilityService, text: String): Outcome {
        if (text.isEmpty() || text.length > MAX_TEXT) return Outcome.Failed("bad_args")
        val node = focusedField(svc) ?: return Outcome.Unsupported("no_focused_field")
        try {
            if (!node.isEditable) return Outcome.Unsupported("no_focused_field")
            if (node.isPassword) return Outcome.Denied("password_field_blocked")
            val current = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
            val edit = TextEditing.insert(current, node.textSelectionStart, node.textSelectionEnd, text)
            return applyText(node, edit)
        } finally {
            node.recycle()
        }
    }

    private fun applyText(node: AccessibilityNodeInfo, edit: TextEditing.Edit): Outcome {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, edit.text)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return Outcome.Failed("set_text_rejected")
        val sel = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, edit.cursor)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, edit.cursor)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
        return Outcome.Ok
    }

    @Suppress("DEPRECATION")
    private fun key(svc: AccessibilityService, key: String): Outcome {
        val node = focusedField(svc) ?: return Outcome.Unsupported("no_focused_field")
        try {
            if (node.isPassword) return Outcome.Denied("password_field_blocked")
            val current = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
            return when (key) {
                KeyAction.BACKSPACE ->
                    applyText(node, TextEditing.backspace(current, node.textSelectionStart, node.textSelectionEnd))
                KeyAction.ENTER -> enter(node, current)
                KeyAction.SELECT_ALL -> {
                    val sel = Bundle().apply {
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, current.length)
                    }
                    if (node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)) Outcome.Ok else Outcome.Failed("action_rejected")
                }
                // Clipboard actions run on the target's own clipboard; DroidLink never reads clipboard contents.
                KeyAction.COPY -> result(node.performAction(AccessibilityNodeInfo.ACTION_COPY))
                KeyAction.CUT -> result(node.performAction(AccessibilityNodeInfo.ACTION_CUT))
                KeyAction.PASTE -> result(node.performAction(AccessibilityNodeInfo.ACTION_PASTE))
                KeyAction.NEXT_FIELD -> moveFocus(node, View.FOCUS_FORWARD)
                KeyAction.PREV_FIELD -> moveFocus(node, View.FOCUS_BACKWARD)
                else -> Outcome.Failed("bad_args")
            }
        } finally {
            node.recycle()
        }
    }

    private fun result(ok: Boolean) = if (ok) Outcome.Ok else Outcome.Failed("action_rejected")

    private fun enter(node: AccessibilityNodeInfo, current: String): Outcome {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        ) {
            return Outcome.Ok
        }
        // Fallback: only multi-line fields can take a newline character.
        if (node.isMultiLine) {
            return applyText(node, TextEditing.insert(current, node.textSelectionStart, node.textSelectionEnd, "\n"))
        }
        return Outcome.Unsupported("enter_not_supported_here")
    }

    @Suppress("DEPRECATION")
    private fun moveFocus(node: AccessibilityNodeInfo, direction: Int): Outcome {
        val next = node.focusSearch(direction) ?: return Outcome.Unsupported("no_other_field")
        try {
            return result(next.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
        } finally {
            next.recycle()
        }
    }

    // ---- apps -------------------------------------------------------------------------------------
    private fun launch(svc: AccessibilityService, pkg: String): Outcome {
        if (!InputValidator.isValidPackageName(pkg)) return Outcome.Failed("bad_args")
        if (pkg == context.packageName) return Outcome.Denied("droidlink_screen_protected")
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return Outcome.Unsupported("app_not_launchable")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            svc.startActivity(intent)
            Outcome.Ok
        } catch (e: ActivityNotFoundException) {
            Outcome.Unsupported("app_not_launchable")
        }
    }

    /** Launchable apps (needs a CONTROL grant). Uses the <queries> LAUNCHER intent, not QUERY_ALL_PACKAGES. */
    @Suppress("DEPRECATION")
    fun listApps(): List<AppEntry>? {
        if (!guard.isAllowed(Scope.CONTROL)) return null
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcher, 0)
            .map { AppEntry(it.loadLabel(pm).toString().take(60), it.activityInfo.packageName) }
            .filter { it.p != context.packageName }
            .distinctBy { it.p }
            .sortedBy { it.l.lowercase() }
            .take(300)
    }

    private fun isOwnAppInForeground(svc: AccessibilityService): Boolean {
        val pkg = svc.rootInActiveWindow?.packageName?.toString()
        return pkg == context.packageName
    }

    private companion object {
        const val MAX_TEXT = 2000
    }
}

internal object ScreenSize {
    fun of(context: Context): Pair<Int, Int> {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        val p = android.graphics.Point()
        @Suppress("DEPRECATION")
        dm.getDisplay(android.view.Display.DEFAULT_DISPLAY).getRealSize(p)
        return p.x to p.y
    }
}
