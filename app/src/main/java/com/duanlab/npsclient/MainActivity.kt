package com.duanlab.npsclient

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.lifecycleScope
import com.duanlab.npsclient.ui.theme.NPSClientTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var preferences: SharedPreferences
    private val defaultCmd =
        "-server=xxx:123,yyy:456 -vkey=vkey1,vkey2 -type=tls,tcp -log_level=6"

    private var cmdTextState = mutableStateOf("")
    private var autoStartState = mutableStateOf(false)
    private var shellRunningState = mutableStateOf(false)
    private var logOutputEnabledState = mutableStateOf(true)
    private val logText = MutableStateFlow("")

    private lateinit var mService: ShellService
    private var mBound by mutableStateOf(false)
    private var bindingRegistered = false
    private var serviceCollection: Job? = null
    private var lastError by mutableStateOf<String?>(null)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            mService = (service as ShellService.LocalBinder).getService()
            mBound = true
            serviceCollection?.cancel()
            serviceCollection = lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    launch { mService.logText.collect { logText.value = it } }
                    launch { mService.activeConfigs.collect { shellRunningState.value = it.isNotEmpty() } }
                    launch { mService.lastError.collect { lastError = it } }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceCollection?.cancel()
            mBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = getSharedPreferences(PreferencesKey.PREF_NAME, Context.MODE_PRIVATE)
        cmdTextState.value = preferences.getString(PreferencesKey.CMD_STR, defaultCmd) ?: defaultCmd
        autoStartState.value = preferences.getBoolean(PreferencesKey.AUTO_START, false)
        shellRunningState.value = preferences.getBoolean(PreferencesKey.SHELL_RUNNING, false)
        logOutputEnabledState.value = preferences.getBoolean(PreferencesKey.LOG_OUTPUT_ENABLED, true)

        if (shellRunningState.value && cmdTextState.value.isNotEmpty()) {
            startShell()
        }

        checkNotificationPermission()

        enableEdgeToEdge()
        setContent {
            NPSClientTheme {
                MainContent()
            }
        }
        Intent(this, ShellService::class.java).also {
            bindingRegistered = bindService(
                it,
                connection,
                Context.BIND_AUTO_CREATE
            )
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Preview(showBackground = true)
    @Composable
    fun MainContent() {
        val clipboardManager = LocalClipboardManager.current
        val log by logText.collectAsState()
        val logScrollState = rememberScrollState()
        LaunchedEffect(log) {
            logScrollState.animateScrollTo(logScrollState.maxValue)
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text("NPC - ${BuildConfig.VERSION_NAME}/${BuildConfig.NpsVersion}")
                    },
                    actions = {
                        Text(
                            text = getString(R.string.about),
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .clickable {
                                    startActivity(
                                        Intent(
                                            this@MainActivity,
                                            AboutActivity::class.java
                                        )
                                    )
                                }
                        )
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(getString(R.string.start_switch))
                            Switch(
                                checked = shellRunningState.value,
                                enabled = mBound,
                                onCheckedChange = { newVal ->
                                    if (newVal) startShell() else stopShell()
                                }
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(getString(R.string.auto_start_switch))
                            Switch(
                                checked = autoStartState.value,
                                onCheckedChange = { newVal ->
                                    autoStartState.value = newVal
                                    preferences.edit()
                                        .putBoolean(PreferencesKey.AUTO_START, newVal)
                                        .apply()
                                }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = cmdTextState.value,
                        onValueChange = { newVal -> cmdTextState.value = newVal },
                        label = { Text(getString(R.string.cmd_str)) },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace)
                    )
                    Button(
                        onClick = {
                            if (cmdTextState.value.isEmpty()) {
                                cmdTextState.value = defaultCmd
                            }
                            preferences.edit().putString(PreferencesKey.CMD_STR, cmdTextState.value)
                                .apply()
                            if (shellRunningState.value) {
                                stopShell()
                                startShell()
                            }
                        },
                        enabled = mBound,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(getString(R.string.save))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = getString(R.string.log),
                            style = MaterialTheme.typography.titleLarge
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Switch(
                            checked = logOutputEnabledState.value,
                            onCheckedChange = { newVal ->
                                logOutputEnabledState.value = newVal
                                preferences.edit()
                                    .putBoolean(PreferencesKey.LOG_OUTPUT_ENABLED, newVal)
                                    .apply()
                            }
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Button(enabled = mBound, onClick = { mService.clearLog() }) {
                            Text(getString(R.string.clear))
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        Button(onClick = {
                            clipboardManager.setText(AnnotatedString(log))
                            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2)
                                Toast.makeText(
                                    this@MainActivity, getString(R.string.copied), Toast.LENGTH_SHORT
                                ).show()
                        }) {
                            Text(stringResource(R.string.copy))
                        }
                    }
                }
                lastError?.let { message ->
                    Text(text = message, color = MaterialTheme.colorScheme.error)
                }
                SelectionContainer {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(logScrollState)
                    ) {
                        Text(
                            text = if (logOutputEnabledState.value)
                                log.ifEmpty { getString(R.string.no_log) }
                            else getString(R.string.no_log),
                            textAlign = TextAlign.Start,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }

    private fun startShell() {
        val config = NpsConfig(cmdTextState.value)
        val intent = Intent(this, ShellService::class.java).apply {
            action = ShellServiceAction.START
            putParcelableArrayListExtra(IntentExtraKey.NpsConfig, arrayListOf(config))
        }
        startService(intent)
    }

    private fun stopShell() {
        if (!mBound) return
        // Stop actual running configurations, not the currently edited command.
        val configs = ArrayList(mService.activeConfigs.value)
        val intent = Intent(this, ShellService::class.java).apply {
            action = ShellServiceAction.STOP
            putParcelableArrayListExtra(IntentExtraKey.NpsConfig, configs)
        }
        startService(intent)
    }

    private fun checkNotificationPermission() {
        val requestPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->
            if (isGranted) {
                // Permission is granted. Continue the action or workflow in your
                // app.
            } else {
                // Explain to the user that the feature is unavailable because the
                // feature requires a permission that the user has denied. At the
                // same time, respect the user's decision. Don't link to system
                // settings in an effort to convince the user to change their
                // decision.
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onDestroy() {
        serviceCollection?.cancel()
        super.onDestroy()
        if (bindingRegistered) {
            unbindService(connection)
            bindingRegistered = false
        }
        mBound = false
    }
}
