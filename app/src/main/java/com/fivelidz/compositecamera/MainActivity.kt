package com.fivelidz.compositecamera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.fivelidz.compositecamera.common.HomeTutorialDialog
import com.fivelidz.compositecamera.common.Tutorial
import com.fivelidz.compositecamera.motion.MotionScreen
import com.fivelidz.compositecamera.pro.ProScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                AppRoot()
            }
        }
    }
}

private enum class Screen { Home, Pro, Motion }

@Composable
private fun AppRoot() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var hasPerms by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = (ctx as ComponentActivity).let { activity ->
        androidx.activity.compose.rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { grants -> hasPerms = grants.values.all { it } }
    }

    if (!hasPerms) {
        PermissionsGate {
            launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        }
        return
    }

    var screen by remember { mutableStateOf(Screen.Home) }
    when (screen) {
        Screen.Home   -> HomeScreen(
            onPro = { screen = Screen.Pro },
            onMotion = { screen = Screen.Motion }
        )
        Screen.Pro    -> ProScreen(onBack = { screen = Screen.Home })
        Screen.Motion -> MotionScreen(onBack = { screen = Screen.Home })
    }
}

@Composable
private fun PermissionsGate(onRequest: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Composite Camera", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "Needs camera + microphone\n(microphone is only used when you record video in Motion mode)",
                color = Color(0xFFB0B0B0),
                fontSize = 13.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onRequest) { Text("Grant permissions") }
        }
    }
}

@Composable
private fun HomeScreen(onPro: () -> Unit, onMotion: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var showTutorial by remember { mutableStateOf(Tutorial.shouldShowHome(ctx)) }

    if (showTutorial) {
        HomeTutorialDialog(onDismiss = {
            Tutorial.markHomeSeen(ctx)
            showTutorial = false
        })
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Spacer(Modifier.height(40.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Composite\nCamera",
                color = Color.White,
                fontSize = 38.sp,
                fontWeight = FontWeight.Black,
                lineHeight = 42.sp,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = { showTutorial = true },
                colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0xFF202020))
            ) {
                Icon(Icons.Filled.HelpOutline, contentDescription = "Help",
                    tint = Color(0xFF5FB0FF))
            }
        }
        Text(
            "Two modern photography pipelines, on-device, no cloud.",
            color = Color(0xFFA0A0A0),
            fontSize = 14.sp
        )
        Spacer(Modifier.height(20.dp))

        ModeCard(
            title = "Pro Composite",
            subtitle = "Real-estate / interior mode",
            body = "Captures a 3-shot ±2 EV exposure bracket, then fuses them on-device using " +
                   "true Mertens multi-band Laplacian-pyramid blending in linear light — the " +
                   "same algorithm as enfuse / Hugin. Use on a tripod facing a bright window. " +
                   "Optional focus-stacking for product shots.",
            icon = Icons.Filled.PhotoCamera,
            accent = Color(0xFF5FB0FF),
            onClick = onPro
        )

        ModeCard(
            title = "Motion Reveal",
            subtitle = "Delayed-inverse live overlay (GPU)",
            body = "GPU-accelerated delayed-inverse blend running at 30 fps. Each live frame is " +
                   "compared with the frame from N seconds ago in a fragment shader. " +
                   "Static scene cancels to mid-grey, only motion shows. Place phone on a " +
                   "tripod and walk past for the full effect.",
            icon = Icons.Filled.Videocam,
            accent = Color(0xFFFFB35F),
            onClick = onMotion
        )

        Spacer(Modifier.weight(1f))
        Text(
            "fivelidz · qalarc · 2026",
            color = Color(0xFF505050),
            fontSize = 11.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeCard(
    title: String,
    subtitle: String,
    body: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF101010)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(48.dp)
                        .background(accent.copy(alpha = 0.12f), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = accent)
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(subtitle, color = accent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(body, color = Color(0xFFB0B0B0), fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}
