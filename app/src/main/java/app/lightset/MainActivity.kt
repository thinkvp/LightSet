package app.lightset

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.lightset.ui.LightsScreen
import app.lightset.ui.LightsViewModel
import app.lightset.ui.MeterScreen
import app.lightset.ui.MeterViewModel
import kotlinx.coroutines.launch

private val PERMISSIONS = buildList {
    add(Manifest.permission.CAMERA)
    if (Build.VERSION.SDK_INT >= 31) {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}.toTypedArray()

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val rig = (application as LightSetApp).rig
        // While the app is visible, keep a connection to the light (shows online/offline).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                if (hasPermissions()) rig.keepConnected()
            }
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                PermissionGate {
                    var tab by rememberSaveable { mutableIntStateOf(0) }
                    Scaffold(
                        bottomBar = {
                            NavigationBar {
                                NavigationBarItem(
                                    selected = tab == 0, onClick = { tab = 0 },
                                    icon = { Icon(Icons.Filled.CameraAlt, contentDescription = null) },
                                    label = { Text("Match") },
                                )
                                NavigationBarItem(
                                    selected = tab == 1, onClick = { tab = 1 },
                                    icon = { Icon(Icons.Filled.Lightbulb, contentDescription = null) },
                                    label = { Text("Lights") },
                                )
                            }
                        },
                    ) { padding ->
                        Box(Modifier.padding(padding)) {
                            when (tab) {
                                0 -> MeterScreen(viewModel<MeterViewModel>())
                                else -> LightsScreen(viewModel<LightsViewModel>())
                            }
                        }
                    }
                }
            }
        }
    }

    private fun hasPermissions() = PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }
}

@Composable
private fun PermissionGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(PERMISSIONS.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED })
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = it.values.all { ok -> ok }
        if (granted) (context as? ComponentActivity)?.recreate()
    }
    if (granted) {
        content()
    } else {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "LightSet uses the camera to measure the light on a white or grey card, " +
                    "and Bluetooth to control your Nanlite lights.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Button(onClick = { launcher.launch(PERMISSIONS) }, Modifier.padding(top = 16.dp)) {
                Text("Allow camera and Bluetooth")
            }
        }
    }
}
