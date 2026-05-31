package com.fivelidz.compositecamera.common

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/**
 * Lightweight tutorial / onboarding system.
 *
 *   - HomeTutorialDialog: first-launch overview of both modes + how to read the home cards
 *   - ProTutorialDialog:  first-open help for Pro mode (presets, capture, thumbnail strip)
 *   - MotionTutorialDialog: first-open help for Motion mode (place phone, watch the effect)
 *
 * Each dialog remembers itself as "seen" in SharedPreferences. Re-open from the (?) icon
 * on each screen.
 */
object Tutorial {
    private const val PREFS = "composite_camera_tutorial"
    private const val KEY_HOME = "seen_home"
    private const val KEY_PRO = "seen_pro"
    private const val KEY_MOTION = "seen_motion"

    fun shouldShowHome(ctx: Context) = !ctx.prefs().getBoolean(KEY_HOME, false)
    fun shouldShowPro(ctx: Context)    = !ctx.prefs().getBoolean(KEY_PRO, false)
    fun shouldShowMotion(ctx: Context) = !ctx.prefs().getBoolean(KEY_MOTION, false)

    fun markHomeSeen(ctx: Context)   = ctx.prefs().edit().putBoolean(KEY_HOME, true).apply()
    fun markProSeen(ctx: Context)    = ctx.prefs().edit().putBoolean(KEY_PRO, true).apply()
    fun markMotionSeen(ctx: Context) = ctx.prefs().edit().putBoolean(KEY_MOTION, true).apply()

    private fun Context.prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/* ---------- Reusable dialog shell ---------- */

@Composable
private fun TutorialDialog(
    title: String,
    accent: Color,
    onDismiss: () -> Unit,
    body: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = Color(0xFF121212),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(title, color = accent, fontSize = 20.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(12.dp))
                body()
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accent)
                ) {
                    Text("Got it", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun TipParagraph(text: String) {
    Text(text, color = Color(0xFFD0D0D0), fontSize = 13.sp, lineHeight = 18.sp)
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun TipBullet(label: String, body: String, accent: Color) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .padding(top = 4.dp)
                .size(6.dp)
                .background(accent, RoundedCornerShape(3.dp))
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(body, color = Color(0xFFB0B0B0), fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
    Spacer(Modifier.height(10.dp))
}

/* ---------- Concrete dialogs ---------- */

@Composable
fun HomeTutorialDialog(onDismiss: () -> Unit) {
    TutorialDialog("Welcome", Color(0xFF5FB0FF), onDismiss) {
        TipParagraph(
            "Composite Camera does two things your stock camera can't:"
        )
        TipBullet(
            "Pro Composite",
            "Multi-shot HDR + optional focus stacking. Stitches several frames at different " +
            "exposures and focus distances into one perfectly exposed and fully sharp image. " +
            "Best on a tripod or steady surface.",
            Color(0xFF5FB0FF)
        )
        TipBullet(
            "Motion Reveal",
            "Live GPU effect: everything static fades to grey, moving things light up. " +
            "Set the phone on a tripod and walk past for the magic. Great for motion detection " +
            "or just the visual effect.",
            Color(0xFFFFB35F)
        )
        TipParagraph("Tap either card to enter. Each mode has its own tutorial the first time.")
    }
}

@Composable
fun ProTutorialDialog(onDismiss: () -> Unit) {
    TutorialDialog("Pro Composite — how to use it", Color(0xFF5FB0FF), onDismiss) {
        TipParagraph("Four presets cover the main scenarios:")
        TipBullet(
            "Real-estate",
            "3 frames at ±2 EV. Perfect for interiors with bright windows — the dark frame " +
            "captures sky/window detail, the bright one lifts shadows. Fused with Mertens " +
            "Laplacian pyramid blending.",
            Color(0xFF5FB0FF)
        )
        TipBullet(
            "Close-up + distance",
            "5 focus planes from your closest subject to infinity. Use when you have something " +
            "near AND want the background sharp — flower on a table with room behind, food " +
            "with kitchen visible, etc. Adjust the 'Closest focus' slider to match your scene.",
            Color(0xFF5FB0FF)
        )
        TipBullet(
            "Full composite",
            "Both: 3 exposures × 5 focus planes = 15 frames. The big one. Takes ~30 s to process " +
            "but produces the best possible result for difficult scenes.",
            Color(0xFF5FB0FF)
        )
        TipBullet(
            "Custom",
            "Auto-selected when you adjust any stepper manually.",
            Color(0xFF5FB0FF)
        )
        Spacer(Modifier.height(6.dp))
        TipParagraph(
            "→ Hold the phone STILL during capture (~3 s for 3 frames, ~10 s for 15). " +
            "Tripod or wedge against something. After the burst, the source frames appear as " +
            "thumbnails — tap any to compare against the fused result."
        )
        TipParagraph(
            "Saved photos go to Pictures/composite_camera/ — visible in your Gallery app."
        )
    }
}

@Composable
fun MotionTutorialDialog(onDismiss: () -> Unit) {
    TutorialDialog("Motion Reveal — how to use it", Color(0xFFFFB35F), onDismiss) {
        TipParagraph(
            "Each live frame is compared with the frame from N seconds ago in a GPU shader. " +
            "Anything that hasn't moved cancels out; anything that moved shows up."
        )
        TipBullet(
            "Place the phone on a tripod or flat surface",
            "Even tiny camera shake will light up the whole frame. The cleaner you can mount it, " +
            "the more dramatic the effect.",
            Color(0xFFFFB35F)
        )
        TipBullet(
            "Pick a mode",
            "Inverse: static scene → mid-grey, motion in colour. Difference: static → black, " +
            "motion → bright white residual. Trail: dim version of the scene + motion streaks.",
            Color(0xFFFFB35F)
        )
        TipBullet(
            "Delay slider",
            "How far back in time to compare. 1 second is good for walking subjects. Increase for " +
            "slow movement, decrease for fast.",
            Color(0xFFFFB35F)
        )
        TipBullet(
            "Sensitivity slider",
            "How much to amplify the residual. Crank it up to see subtle motion (breathing, leaves " +
            "in a breeze); turn down for high-contrast obvious motion.",
            Color(0xFFFFB35F)
        )
        TipParagraph(
            "Try: set 2 s delay, point at a doorway, and walk through. You'll appear as a bright " +
            "ghost against the cancelled background."
        )
    }
}
