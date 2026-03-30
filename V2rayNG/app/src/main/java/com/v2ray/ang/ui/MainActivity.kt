package com.v2ray.ang.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.content.res.Configuration
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.EditText
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import com.google.android.material.navigation.NavigationView
import com.google.android.material.tabs.TabLayout
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.VPN
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityMainBinding
import com.v2ray.ang.dto.EConfigType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.helper.SimpleItemTouchHelperCallback
import com.v2ray.ang.handler.SpeedtestManager
import com.v2ray.ang.handler.V2RayServiceManager
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : BaseActivity(), NavigationView.OnNavigationItemSelectedListener {
    private val binding by lazy {
        ActivityMainBinding.inflate(layoutInflater)
    }

    private val adapter by lazy { MainRecyclerAdapter(this) }

    // VseMoiOnline: Provisioning configuration
    companion object {
        private const val PREFS_NAME = "vsemoionline_prefs"
        private const val PREF_LAST_WORKING_URL = "last_working_provisioning_url"
        private const val PREF_XRAY_UUID = "xray_uuid"
        private const val PREF_PLAN = "plan"
        private const val PREF_BACKEND_BASE_URL = "backend_base_url"
        private const val PREF_VLESS_URI = "vless_uri"
        private const val PREF_PAID_DAYS_REMAINING = "paid_days_remaining"
        private const val PREF_TRAFFIC_REMAINING_GB = "traffic_remaining_gb"
        private const val PREF_TRAFFIC_TOTAL_GB = "traffic_total_gb"
        private const val PREF_THROTTLE_MBPS = "throttle_mbps"
        private const val PREF_CABINET_URL = "cabinet_url"

        // Two-cycle provisioning timeouts for better UX
        private const val PROVISION_TIMEOUT_QUICK_MS = 3000  // 3 seconds - Cycle 1: quick scan
        private const val PROVISION_TIMEOUT_PATIENT_MS = 10000 // 10 seconds - Cycle 2: patient retry

        private const val PRIMARY_PROVISION_URL = "https://vmonl.store/provision"

        // Fallback platform URLs - return plain text with current provisioning domain/IP
        private val FALLBACK_PLATFORM_URLS = listOf(
            "https://gist.githubusercontent.com/dmitry-ulyanichev/0b552cd7f00f8735f8a4832321825162/raw/a1592ec7783bd7c5248a19892779aa199feddd64/endpoint.txt"
//            "https://raw.githubusercontent.com/YOUR_USERNAME/provision/main/endpoint.txt",
//            "https://YOUR_WORKER.YOUR_SUBDOMAIN.workers.dev/provision",
//            "https://YOUR_PROJECT.vercel.app/api/provision",
//            "https://YOUR_SITE.netlify.app/.netlify/functions/provision",
//            "https://pastebin.com/raw/YOUR_PASTE_ID",
//            "https://gitlab.com/snippets/YOUR_SNIPPET_ID/raw"
            // Add more as needed
        )
    }

    internal val requestVpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            startV2Ray()
        }
    }
    private val requestSubSettingActivity = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        initGroupTab()
    }
    private val tabGroupListener = object : TabLayout.OnTabSelectedListener {
        override fun onTabSelected(tab: TabLayout.Tab?) {
            val selectId = tab?.tag.toString()
            if (selectId != mainViewModel.subscriptionId) {
                mainViewModel.subscriptionIdChanged(selectId)
            }
        }

        override fun onTabUnselected(tab: TabLayout.Tab?) {
        }

        override fun onTabReselected(tab: TabLayout.Tab?) {
        }
    }
    private var mItemTouchHelper: ItemTouchHelper? = null
    val mainViewModel: MainViewModel by viewModels()

    // VseMoiOnline: Flag to prevent duplicate provisioning attempts
    private var isProvisioningInProgress = false

    // VseMoiOnline: Set when an activation deep link is being processed.
    // checkAndAutoProvision skips if this is non-null to avoid a free-provision race.
    private var pendingActivationToken: String? = null

    // VseMoiOnline: Status polling job — active only while VPN is running
    private var statusPollingJob: Job? = null

    // VseMoiOnline: Code input fields and paste-in-progress flag
    private lateinit var codeFields: Array<EditText>
    private var isPasting = false

    // register activity result for requesting permission
    private val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->
            if (isGranted) {
                when (pendingAction) {
                    Action.IMPORT_QR_CODE_CONFIG ->
                        scanQRCodeForConfig.launch(Intent(this, ScannerActivity::class.java))

                    Action.READ_CONTENT_FROM_URI ->
                        chooseFileForCustomConfig.launch(Intent.createChooser(Intent(Intent.ACTION_GET_CONTENT).apply {
                            type = "*/*"
                            addCategory(Intent.CATEGORY_OPENABLE)
                        }, getString(R.string.title_file_chooser)))

                    Action.POST_NOTIFICATIONS -> {}
                    else -> {}
                }
            } else {
                toast(R.string.toast_permission_denied)
            }
            pendingAction = Action.NONE
        }

    private var pendingAction: Action = Action.NONE

    enum class Action {
        NONE,
        IMPORT_QR_CODE_CONFIG,
        READ_CONTENT_FROM_URI,
        POST_NOTIFICATIONS
    }

    private val chooseFileForCustomConfig = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val uri = it.data?.data
        if (it.resultCode == RESULT_OK && uri != null) {
            readContentFromUri(uri)
        }
    }

    private val scanQRCodeForConfig = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            importBatchConfig(it.data?.getStringExtra("SCAN_RESULT"))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        title = getString(R.string.vsm_toolbar_title)
        setSupportActionBar(binding.toolbar)

        binding.fab.setOnClickListener {
            if (mainViewModel.isRunning.value == true) {
                V2RayServiceManager.stopVService(this)
            } else if ((MmkvManager.decodeSettingsString(AppConfig.PREF_MODE) ?: VPN) == VPN) {
                val intent = VpnService.prepare(this)
                if (intent == null) {
                    startV2Ray()
                } else {
                    requestVpnPermission.launch(intent)
                }
            } else {
                startV2Ray()
            }
        }
        binding.layoutTest.setOnClickListener {
            if (mainViewModel.isRunning.value == true) {
                setTestState(getString(R.string.connection_test_testing))
                mainViewModel.testCurrentServerRealPing()
            } else {
//                tv_test_state.text = getString(R.string.connection_test_fail)
            }
        }

        binding.recyclerView.setHasFixedSize(true)
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_DOUBLE_COLUMN_DISPLAY, false)) {
            binding.recyclerView.layoutManager = GridLayoutManager(this, 2)
        } else {
            binding.recyclerView.layoutManager = GridLayoutManager(this, 1)
        }
        addCustomDividerToRecyclerView(binding.recyclerView, this, R.drawable.custom_divider)
        binding.recyclerView.adapter = adapter

        mItemTouchHelper = ItemTouchHelper(SimpleItemTouchHelperCallback(adapter))
        mItemTouchHelper?.attachToRecyclerView(binding.recyclerView)

        val toggle = ActionBarDrawerToggle(
            this, binding.drawerLayout, binding.toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close
        )
        binding.drawerLayout.addDrawerListener(toggle)
        toggle.syncState()
        binding.navView.setNavigationItemSelectedListener(this)

        // Set version in navigation header
        binding.navView.getHeaderView(0)?.findViewById<android.widget.TextView>(R.id.tv_version_header)?.text =
            "v${com.v2ray.ang.BuildConfig.VERSION_NAME} (${SpeedtestManager.getLibVersion()})"

        initGroupTab()
        setupViewModel()
        setupVsmUi()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                pendingAction = Action.POST_NOTIFICATIONS
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        // VseMoiOnline: Handle magic link on app startup
        handleMagicLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // VseMoiOnline: Handle magic link when app is already running
        handleMagicLink(intent)
    }

    /**
     * VseMoiOnline: Handle deep links
     * vsemoionline://import?url=...    — fetch VLESS config from URL (legacy magic link)
     * vsemoionline://activate?token=... — activate paid subscription token
     */
    private fun handleMagicLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "vsemoionline") return

        when (data.host) {
            "import" -> {
                val provisionUrl = data.getQueryParameter("url") ?: run {
                    Log.w(AppConfig.TAG, "VseMoiOnline: 'import' deep link missing 'url' parameter")
                    return
                }
                Log.i(AppConfig.TAG, "VseMoiOnline: Import deep link — provisioning from: $provisionUrl")
                binding.pbWaiting.show()
                lifecycleScope.launch(Dispatchers.IO) {
                    val success = fetchAndImportConfig(provisionUrl, PROVISION_TIMEOUT_PATIENT_MS)
                    if (success) saveLastWorkingProvisionUrl(provisionUrl)
                    withContext(Dispatchers.Main) { binding.pbWaiting.hide() }
                }
            }
            "activate" -> {
                val token = data.getQueryParameter("token") ?: run {
                    Log.w(AppConfig.TAG, "VseMoiOnline: 'activate' deep link missing 'token' parameter")
                    return
                }
                Log.i(AppConfig.TAG, "VseMoiOnline: Activate deep link received")
                pendingActivationToken = token
                handleActivateDeepLink(token)
            }
            else -> Log.w(AppConfig.TAG, "VseMoiOnline: Unknown deep link host: ${data.host}")
        }
    }

    /**
     * VseMoiOnline: Consume an activation token via POST /activate, then re-provision
     * to get the paid-inbound VLESS config.
     */
    private fun handleActivateDeepLink(token: String) {
        // On first launch backendBaseUrl may not yet be stored — fall back to primary URL.
        val backendBaseUrl = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(PREF_BACKEND_BASE_URL, null)
            ?: PRIMARY_PROVISION_URL.removeSuffix("/provision").trimEnd('/')

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val connection = java.net.URL("$backendBaseUrl/activate").openConnection()
                    as java.net.HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connection.instanceFollowRedirects = false
                connection.outputStream.use {
                    it.write("token=${java.net.URLEncoder.encode(token, "UTF-8")}".toByteArray(Charsets.UTF_8))
                }
                val responseCode = connection.responseCode
                connection.disconnect()

                if (responseCode in 301..303) {
                    // Backend redirects to /activate/success on valid token — re-provision for paid config
                    val success = fetchAndImportConfig("$backendBaseUrl/provision", PROVISION_TIMEOUT_PATIENT_MS)
                    withContext(Dispatchers.Main) {
                        pendingActivationToken = null
                        if (success) {
                            toastSuccess("Подписка активирована!")
                            binding.tvCodeSuccess.visibility = View.VISIBLE
                            binding.tvCodeSuccess.postDelayed(
                                { binding.tvCodeSuccess.visibility = View.GONE }, 3000
                            )
                            if (mainViewModel.isRunning.value == true) restartV2Ray()
                        } else {
                            toast("Подписка активирована. Перезапустите приложение для обновления конфигурации.")
                        }
                    }
                } else {
                    // Token rejected — could be genuinely invalid, or user tapped the browser's
                    // "Continue" chip a second time after a successful activation.
                    // Check stored plan to avoid a confusing error in the latter case.
                    val currentPlan = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .getString(PREF_PLAN, "free")
                    withContext(Dispatchers.Main) {
                        pendingActivationToken = null
                        if (currentPlan == "paid") {
                            toastSuccess("Подписка уже активирована!")
                        } else {
                            toastError("Код недействителен или уже использован")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "VseMoiOnline: Activation error", e)
                withContext(Dispatchers.Main) {
                    pendingActivationToken = null
                    toastError("Ошибка активации. Проверьте подключение к интернету.")
                }
            }
        }
    }

    /**
     * VseMoiOnline: Get or create persistent device UUID
     * Stored in SharedPreferences to identify this device across sessions
     */
    private fun getOrCreateDeviceId(): String {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        var deviceId = prefs.getString("device_id", null)

        if (deviceId == null) {
            deviceId = java.util.UUID.randomUUID().toString()
            prefs.edit().putString("device_id", deviceId).apply()
            Log.i(AppConfig.TAG, "VseMoiOnline: Generated new device UUID: $deviceId")
        } else {
            Log.i(AppConfig.TAG, "VseMoiOnline: Using existing device UUID: $deviceId")
        }

        return deviceId
    }

    /**
     * VseMoiOnline: Save last successful provisioning URL
     * This URL will be tried first on next provisioning attempt
     */
    private fun saveLastWorkingProvisionUrl(url: String) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit().putString(PREF_LAST_WORKING_URL, url).apply()
        Log.i(AppConfig.TAG, "VseMoiOnline: Saved last working provisioning URL: $url")
    }

    /**
     * VseMoiOnline: Get last successful provisioning URL
     * Returns null if no URL was previously saved
     */
    private fun getLastWorkingProvisionUrl(): String? {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        return prefs.getString(PREF_LAST_WORKING_URL, null)
    }

    private fun getAndroidId(): String? {
        return try {
            android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID)
        } catch (e: Exception) {
            null
        }
    }

    private fun saveProvisionData(xrayUuid: String, plan: String, vlessUri: String, provisionUrl: String, cabinetUrl: String?) {
        val baseUrl = provisionUrl.removeSuffix("/provision").trimEnd('/')
        val editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(PREF_XRAY_UUID, xrayUuid)
            .putString(PREF_PLAN, plan)
            .putString(PREF_VLESS_URI, vlessUri)
            .putString(PREF_BACKEND_BASE_URL, baseUrl)
        if (cabinetUrl != null) editor.putString(PREF_CABINET_URL, cabinetUrl)
        editor.apply()
        Log.i(AppConfig.TAG, "VseMoiOnline: Provision data saved — plan=$plan uuid=$xrayUuid base=$baseUrl")
    }

    /**
     * VseMoiOnline: Try provisioning with fallback URLs using two-cycle approach
     *
     * Cycle 1 (Quick scan - 3s timeout):
     *   - Tries all URLs quickly to find working endpoints fast
     *
     * Cycle 2 (Patient retry - 10s timeout):
     *   - Only retries URLs that timed out in Cycle 1
     *   - Shows user-friendly message to maintain patience
     *
     * Priority order:
     * 1. Last working URL (if exists)
     * 2. Primary hardcoded URL
     * 3. Fallback platform URLs (which return the current provisioning endpoint)
     */
    private fun tryProvisioningWithFallback() {
        // Prevent duplicate provisioning attempts
        if (isProvisioningInProgress) {
            Log.i(AppConfig.TAG, "VseMoiOnline: Provisioning already in progress, skipping")
            return
        }

        isProvisioningInProgress = true
        binding.pbWaiting.show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val urlsToTry = mutableListOf<Pair<String, Boolean>>()

                // Priority 1: Last working URL
                getLastWorkingProvisionUrl()?.let {
                    urlsToTry.add(it to true) // true = direct provisioning URL
                    Log.i(AppConfig.TAG, "VseMoiOnline: Will try last working URL first: $it")
                }

                // Priority 2: Primary URL
                urlsToTry.add(PRIMARY_PROVISION_URL to true)

                // Priority 3: Fallback platforms
                FALLBACK_PLATFORM_URLS.forEach { platformUrl ->
                    urlsToTry.add(platformUrl to false) // false = platform URL, needs fetching
                }

                // CYCLE 1: Quick scan with 3s timeout
                Log.i(AppConfig.TAG, "VseMoiOnline: Starting Cycle 1 - quick scan (${PROVISION_TIMEOUT_QUICK_MS}ms timeout)")
                var success = false
                val timedOutUrls = mutableListOf<Pair<String, Boolean>>()

                for ((url, isDirect) in urlsToTry) {
                    if (success) break

                    try {
                        Log.i(AppConfig.TAG, "VseMoiOnline: Cycle 1 - Attempting provisioning from: $url")

                        val provisionUrl = if (isDirect) {
                            url
                        } else {
                            // Fetch the actual provisioning endpoint from the platform
                            fetchProvisioningEndpoint(url, PROVISION_TIMEOUT_QUICK_MS)
                        }

                        if (provisionUrl != null) {
                            val result = fetchAndImportConfig(provisionUrl, PROVISION_TIMEOUT_QUICK_MS)
                            if (result) {
                                success = true
                                saveLastWorkingProvisionUrl(provisionUrl)
                                Log.i(AppConfig.TAG, "VseMoiOnline: Cycle 1 - Provisioning succeeded from: $url")
                                break
                            }
                        }
                    } catch (e: java.net.SocketTimeoutException) {
                        Log.w(AppConfig.TAG, "VseMoiOnline: Cycle 1 - Timeout from $url, will retry in Cycle 2")
                        timedOutUrls.add(url to isDirect)
                    } catch (e: Exception) {
                        Log.w(AppConfig.TAG, "VseMoiOnline: Cycle 1 - Failed from $url: ${e.message}")
                        // Don't retry non-timeout errors (HTTP errors, etc.)
                    }
                }

                // CYCLE 2: Patient retry for timed-out URLs only
                if (!success && timedOutUrls.isNotEmpty()) {
                    // Show encouraging message to user
                    withContext(Dispatchers.Main) {
                        toast(R.string.provisioning_retry_message)
                    }

                    Log.i(AppConfig.TAG, "VseMoiOnline: Starting Cycle 2 - patient retry (${PROVISION_TIMEOUT_PATIENT_MS}ms timeout) for ${timedOutUrls.size} URLs")

                    for ((url, isDirect) in timedOutUrls) {
                        if (success) break

                        try {
                            Log.i(AppConfig.TAG, "VseMoiOnline: Cycle 2 - Retrying provisioning from: $url")

                            val provisionUrl = if (isDirect) {
                                url
                            } else {
                                fetchProvisioningEndpoint(url, PROVISION_TIMEOUT_PATIENT_MS)
                            }

                            if (provisionUrl != null) {
                                val result = fetchAndImportConfig(provisionUrl, PROVISION_TIMEOUT_PATIENT_MS)
                                if (result) {
                                    success = true
                                    saveLastWorkingProvisionUrl(provisionUrl)
                                    Log.i(AppConfig.TAG, "VseMoiOnline: Cycle 2 - Provisioning succeeded from: $url")
                                    break
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(AppConfig.TAG, "VseMoiOnline: Cycle 2 - Failed from $url: ${e.message}")
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    if (!success) {
                        Log.e(AppConfig.TAG, "VseMoiOnline: All provisioning URLs failed after both cycles")
                        toastError(R.string.provisioning_failed_message)
                    }
                    binding.pbWaiting.hide()
                }
            } finally {
                // Always clear the flag, even if an exception occurred
                isProvisioningInProgress = false
            }
        }
    }

    /**
     * VseMoiOnline: Fetch provisioning endpoint from a platform URL
     * Platform URLs return plain text like "103.241.67.124:8888" or "provision.example.com"
     * This method constructs the full provisioning URL from that response
     *
     * @param platformUrl The platform URL to fetch from
     * @param timeoutMs Timeout in milliseconds for this request
     */
    private fun fetchProvisioningEndpoint(platformUrl: String, timeoutMs: Int): String? {
        return try {
            val url = java.net.URL(platformUrl)
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs

            val responseCode = connection.responseCode
            if (responseCode == java.net.HttpURLConnection.HTTP_OK) {
                val endpointText = connection.inputStream.bufferedReader().readText().trim()
                connection.disconnect()

                // Construct full provisioning URL
                // If response contains "http://" or "https://", use as-is
                // Otherwise, assume it's "host:port" or "domain" and construct URL
                if (endpointText.startsWith("http://") || endpointText.startsWith("https://")) {
                    endpointText
                } else {
                    // Assume HTTP for now; will migrate to HTTPS with real domains
                    "http://$endpointText/provision"
                }
            } else {
                connection.disconnect()
                null
            }
        } catch (e: Exception) {
            Log.w(AppConfig.TAG, "VseMoiOnline: Failed to fetch from platform $platformUrl: ${e.message}")
            throw e // Re-throw to allow timeout detection
        }
    }

    /**
     * VseMoiOnline: Provision this device with the backend.
     * POSTs { device_fingerprint, android_id } to the provision endpoint,
     * parses the JSON response, stores xray_uuid/plan/vless_uri, and imports the VLESS config.
     *
     * @param provisionUrl Full URL of the /provision endpoint
     * @param timeoutMs Timeout in milliseconds
     * @return true if successful, false otherwise
     */
    private suspend fun fetchAndImportConfig(provisionUrl: String, timeoutMs: Int): Boolean {
        return try {
            val deviceFingerprint = getOrCreateDeviceId()
            val androidId = getAndroidId()

            val jsonBody = buildString {
                append("{\"device_fingerprint\":\"$deviceFingerprint\"")
                if (androidId != null) append(",\"android_id\":\"$androidId\"")
                append("}")
            }

            Log.i(AppConfig.TAG, "VseMoiOnline: POSTing to provision: $provisionUrl")

            val url = java.net.URL(provisionUrl)
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }

            val responseCode = connection.responseCode
            if (responseCode == java.net.HttpURLConnection.HTTP_OK) {
                val responseText = connection.inputStream.bufferedReader().readText()
                connection.disconnect()

                val json = org.json.JSONObject(responseText)
                val vlessUri = json.getString("vless_uri")
                val xrayUuid = json.getString("xray_uuid")
                val plan = json.getString("plan")
                val cabinetUrl = if (json.isNull("cabinet_url")) null else json.getString("cabinet_url")

                saveProvisionData(xrayUuid, plan, vlessUri, provisionUrl, cabinetUrl)
                Log.i(AppConfig.TAG, "VseMoiOnline: Provisioned — plan=$plan, importing config")

                // Immediately fetch status so days_remaining/traffic are populated before UI renders
                try { pollStatus(xrayUuid) } catch (e: Exception) {
                    Log.w(AppConfig.TAG, "VseMoiOnline: Post-provision status poll failed: ${e.message}")
                }

                withContext(Dispatchers.Main) {
                    // Remove any existing VseMoiVPN server before importing updated config
                    mainViewModel.removeAllServer()
                    importBatchConfig(vlessUri)
                    toast(R.string.toast_success)
                    updateSubscriptionHeader()
                    updateSubBlock()
                }
                true
            } else {
                connection.disconnect()
                Log.w(AppConfig.TAG, "VseMoiOnline: Provision HTTP error: $responseCode")
                false
            }

        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "VseMoiOnline: Provision failed for $provisionUrl", e)
            throw e // Re-throw to allow timeout detection in two-cycle logic
        }
    }

    private fun startStatusPolling() {
        stopStatusPolling()
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val xrayUuid = prefs.getString(PREF_XRAY_UUID, null) ?: run {
            Log.w(AppConfig.TAG, "VseMoiOnline: No xray_uuid stored, skipping status polling")
            return
        }
        statusPollingJob = lifecycleScope.launch(Dispatchers.IO) {
            while (true) {
                try {
                    pollStatus(xrayUuid)
                } catch (e: Exception) {
                    Log.w(AppConfig.TAG, "VseMoiOnline: Status poll failed: ${e.message}")
                }
                delay(45_000)
            }
        }
        Log.i(AppConfig.TAG, "VseMoiOnline: Status polling started")
    }

    private fun stopStatusPolling() {
        statusPollingJob?.cancel()
        statusPollingJob = null
    }

    private suspend fun pollStatus(xrayUuid: String) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val backendBaseUrl = prefs.getString(PREF_BACKEND_BASE_URL, null) ?: return

        val connection = java.net.URL("$backendBaseUrl/status/$xrayUuid").openConnection()
            as java.net.HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000

        val responseCode = connection.responseCode
        if (responseCode != java.net.HttpURLConnection.HTTP_OK) {
            connection.disconnect()
            Log.w(AppConfig.TAG, "VseMoiOnline: Status poll HTTP $responseCode")
            return
        }

        val json = org.json.JSONObject(connection.inputStream.bufferedReader().readText())
        connection.disconnect()

        val plan = json.getString("plan")
        val currentDomain = if (json.isNull("current_domain")) null else json.getString("current_domain")
        val daysRemaining = if (json.isNull("days_remaining")) 0 else json.getInt("days_remaining")
        val throttleMbps = if (json.isNull("throttle_mbps")) 0f else json.getDouble("throttle_mbps").toFloat()
        val trafficTotalGb = if (!json.isNull("traffic_cap_mb"))
            json.getDouble("traffic_cap_mb").toFloat() / 1000f
        else
            if (plan == "paid") 250f else 25f
        Log.d(AppConfig.TAG, "VseMoiOnline: Status — plan=$plan domain=$currentDomain throttle=${throttleMbps}Mbps cap=${trafficTotalGb}GB")
        val trafficRemainingGb: Float = when {
            !json.isNull("traffic_consumed_mb") -> {
                val consumedGb = json.getDouble("traffic_consumed_mb").toFloat() / 1000f
                (trafficTotalGb - consumedGb).coerceAtLeast(0f)
            }
            else -> trafficTotalGb
        }

        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(PREF_PLAN, plan)
            .putInt(PREF_PAID_DAYS_REMAINING, daysRemaining)
            .putFloat(PREF_TRAFFIC_TOTAL_GB, trafficTotalGb)
            .putFloat(PREF_TRAFFIC_REMAINING_GB, trafficRemainingGb)
            .putFloat(PREF_THROTTLE_MBPS, throttleMbps)
            .apply()

        withContext(Dispatchers.Main) {
            updateSubscriptionHeader()
            updateSubBlock()
        }

        // Phase 2: if current_domain changed, update backend_base_url and re-provision.
        // Skipped for now — domain_names table is empty in Phase 1.
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun setupViewModel() {
        mainViewModel.updateListAction.observe(this) { index ->
            if (index >= 0) {
                adapter.notifyItemChanged(index)
            } else {
                adapter.notifyDataSetChanged()
            }
        }
        mainViewModel.updateTestResultAction.observe(this) { setTestState(it) }
        mainViewModel.isRunning.observe(this) { isRunning ->
            adapter.isRunning = isRunning
            adapter.notifyDataSetChanged()  // Refresh adapter to update switch states
            if (isRunning) {
                binding.fab.setImageResource(R.drawable.ic_stop_24dp)
                binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.vsm_fab_stop))
                binding.fab.contentDescription = getString(R.string.action_stop_service)
                setTestState(getString(R.string.connection_connected))
                binding.layoutTest.isFocusable = true
                startStatusPolling()
            } else {
                binding.fab.setImageResource(R.drawable.ic_play_24dp)
                binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.vsm_mint))
                binding.fab.contentDescription = getString(R.string.tasker_start_service)
                setTestState(getString(R.string.connection_not_connected))
                binding.layoutTest.isFocusable = false
                stopStatusPolling()
            }
        }
        mainViewModel.startListenBroadcast()
        mainViewModel.initAssets(assets)
    }

    private fun initGroupTab() {
        binding.tabGroup.removeOnTabSelectedListener(tabGroupListener)
        binding.tabGroup.removeAllTabs()
        binding.tabGroup.isVisible = false

        val (listId, listRemarks) = mainViewModel.getSubscriptions(this)
        if (listId == null || listRemarks == null) {
            return
        }

        for (it in listRemarks.indices) {
            val tab = binding.tabGroup.newTab()
            tab.text = listRemarks[it]
            tab.tag = listId[it]
            binding.tabGroup.addTab(tab)
        }
        val selectIndex =
            listId.indexOf(mainViewModel.subscriptionId).takeIf { it >= 0 } ?: (listId.count() - 1)
        binding.tabGroup.selectTab(binding.tabGroup.getTabAt(selectIndex))
        binding.tabGroup.addOnTabSelectedListener(tabGroupListener)
        binding.tabGroup.isVisible = false  // VseMoiOnline: tab group hidden in main UX
    }

    internal fun startV2Ray() {
        if (MmkvManager.getSelectServer().isNullOrEmpty()) {
            toast(R.string.title_file_chooser)
            return
        }
        V2RayServiceManager.startVService(this)
    }

    private fun restartV2Ray() {
        if (mainViewModel.isRunning.value == true) {
            V2RayServiceManager.stopVService(this)
        }
        lifecycleScope.launch {
            delay(500)
            startV2Ray()
        }
    }

    public override fun onResume() {
        super.onResume()
        mainViewModel.reloadServerList()
        updateSubscriptionHeader()
        updateSubBlock()

        // VseMoiOnline: Auto-provision on first launch if no servers configured
        checkAndAutoProvision()
    }

    /**
     * VseMoiOnline: Auto-provision if no servers configured.
     *
     * Three cases:
     * 1. Servers already in MMKV → nothing to do.
     * 2. No servers, no stored xray_uuid → fresh device, full provisioning via fallback chain.
     * 3. No servers, but xray_uuid + vless_uri stored → device is registered in the backend and
     *    Xray, local MMKV was just cleared (reinstall / data wipe). Re-import the stored URI
     *    directly without a network round-trip.
     */
    private fun checkAndAutoProvision() {
        // Activation deep link takes ownership of provisioning — don't race it.
        if (pendingActivationToken != null) {
            Log.i(AppConfig.TAG, "VseMoiOnline: Skipping auto-provision — activation in progress")
            return
        }

        val serverList = MmkvManager.decodeServerList()
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        if (serverList.size > 1) {
            // Multiple configs — stale entries from a previous free→paid upgrade. Clean up.
            val storedVlessUri = prefs.getString(PREF_VLESS_URI, null)
            if (storedVlessUri != null) {
                Log.w(AppConfig.TAG, "VseMoiOnline: Found ${serverList.size} configs, expected 1 — removing stale entries")
                mainViewModel.removeAllServer()
                importBatchConfig(storedVlessUri)
                return
            }
            mainViewModel.removeAllServer()
            // fall through to full provisioning below
        } else if (serverList.size == 1) {
            return
        }

        val storedVlessUri = prefs.getString(PREF_VLESS_URI, null)
        val storedXrayUuid = prefs.getString(PREF_XRAY_UUID, null)

        if (storedXrayUuid != null && storedVlessUri != null) {
            Log.i(AppConfig.TAG, "VseMoiOnline: No servers in MMKV but provisioned before — re-importing stored config")
            importBatchConfig(storedVlessUri)
        } else {
            Log.i(AppConfig.TAG, "VseMoiOnline: No servers configured, starting provisioning")
            tryProvisioningWithFallback()
        }
    }

    public override fun onPause() {
        super.onPause()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.service_restart -> {
            restartV2Ray()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    private fun importManually(createConfigType: Int) {
        if (createConfigType == EConfigType.POLICYGROUP.value) {
            startActivity(
                Intent()
                    .putExtra("subscriptionId", mainViewModel.subscriptionId)
                    .setClass(this, ServerGroupActivity::class.java)
            )
        } else {
            startActivity(
                Intent()
                    .putExtra("createConfigType", createConfigType)
                    .putExtra("subscriptionId", mainViewModel.subscriptionId)
                    .setClass(this, ServerActivity::class.java)
            )
        }
    }

    /**
     * import config from qrcode
     */
    private fun importQRcode(): Boolean {
        val permission = Manifest.permission.CAMERA
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            scanQRCodeForConfig.launch(Intent(this, ScannerActivity::class.java))
        } else {
            pendingAction = Action.IMPORT_QR_CODE_CONFIG
            requestPermissionLauncher.launch(permission)
        }
        return true
    }

    /**
     * import config from clipboard
     */
    private fun importClipboard()
            : Boolean {
        try {
            val clipboard = Utils.getClipboard(this)
            importBatchConfig(clipboard)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to import config from clipboard", e)
            return false
        }
        return true
    }

    private fun importBatchConfig(server: String?) {
        binding.pbWaiting.show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val (count, countSub) = AngConfigManager.importBatchConfig(server, mainViewModel.subscriptionId, true)
                delay(500L)
                withContext(Dispatchers.Main) {
                    when {
                        count > 0 -> {
                            toast(getString(R.string.title_import_config_count, count))
                            mainViewModel.reloadServerList()
                        }

                        countSub > 0 -> initGroupTab()
                        else -> toastError(R.string.toast_failure)
                    }
                    binding.pbWaiting.hide()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    toastError(R.string.toast_failure)
                    binding.pbWaiting.hide()
                }
                Log.e(AppConfig.TAG, "Failed to import batch config", e)
            }
        }
    }

    /**
     * import config from local config file
     */
    private fun importConfigLocal(): Boolean {
        try {
            showFileChooser()
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to import config from local file", e)
            return false
        }
        return true
    }


    /**
     * import config from sub
     */
    private fun importConfigViaSub(): Boolean {
        binding.pbWaiting.show()

        lifecycleScope.launch(Dispatchers.IO) {
            val count = mainViewModel.updateConfigViaSubAll()
            delay(500L)
            launch(Dispatchers.Main) {
                if (count > 0) {
                    toast(getString(R.string.title_update_config_count, count))
                    mainViewModel.reloadServerList()
                } else {
                    toastError(R.string.toast_failure)
                }
                binding.pbWaiting.hide()
            }
        }
        return true
    }

    private fun exportAll() {
        binding.pbWaiting.show()
        lifecycleScope.launch(Dispatchers.IO) {
            val ret = mainViewModel.exportAllServer()
            launch(Dispatchers.Main) {
                if (ret > 0)
                    toast(getString(R.string.title_export_config_count, ret))
                else
                    toastError(R.string.toast_failure)
                binding.pbWaiting.hide()
            }
        }
    }

    private fun delAllConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                binding.pbWaiting.show()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeAllServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        toast(getString(R.string.title_del_config_count, ret))
                        binding.pbWaiting.hide()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                //do noting
            }
            .show()
    }

    private fun delDuplicateConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                binding.pbWaiting.show()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeDuplicateServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        toast(getString(R.string.title_del_duplicate_config_count, ret))
                        binding.pbWaiting.hide()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                //do noting
            }
            .show()
    }

    private fun delInvalidConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_invalid_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                binding.pbWaiting.show()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeInvalidServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        toast(getString(R.string.title_del_config_count, ret))
                        binding.pbWaiting.hide()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                //do noting
            }
            .show()
    }

    private fun sortByTestResults() {
        binding.pbWaiting.show()
        lifecycleScope.launch(Dispatchers.IO) {
            mainViewModel.sortByTestResults()
            launch(Dispatchers.Main) {
                mainViewModel.reloadServerList()
                binding.pbWaiting.hide()
            }
        }
    }

    /**
     * show file chooser
     */
    private fun showFileChooser() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "*/*"
        intent.addCategory(Intent.CATEGORY_OPENABLE)

        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            pendingAction = Action.READ_CONTENT_FROM_URI
            chooseFileForCustomConfig.launch(Intent.createChooser(intent, getString(R.string.title_file_chooser)))
        } else {
            requestPermissionLauncher.launch(permission)
        }
    }

    /**
     * read content from uri
     */
    private fun readContentFromUri(uri: Uri) {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            try {
                contentResolver.openInputStream(uri).use { input ->
                    importBatchConfig(input?.bufferedReader()?.readText())
                }
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Failed to read content from URI", e)
            }
        } else {
            requestPermissionLauncher.launch(permission)
        }
    }

    private fun setTestState(content: String?) {
        binding.tvTestState.text = content
    }

//    val mConnection = object : ServiceConnection {
//        override fun onServiceDisconnected(name: ComponentName?) {
//        }
//
//        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
//            sendMsg(AppConfig.MSG_REGISTER_CLIENT, "")
//        }
//    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            moveTaskToBack(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }


    // ── VseMoiOnline UI ──────────────────────────────────────────────────

    /**
     * Wire up all VseMoiOnline-specific click handlers and initialise the UI
     * from whatever data is already stored in SharedPreferences.
     */
    private fun setupVsmUi() {
        binding.layoutSubHeader.setOnClickListener { toggleSubBlock() }
        binding.tvSubLink.setOnClickListener { openCabinetUrl() }
        binding.rowServer.setOnClickListener { onServerRowTapped() }
        binding.btnPay.setOnClickListener { openCabinetUrl() }
        binding.btnFamily.setOnClickListener { /* Phase 3 — family referral */ }
        binding.btnRenew.setOnClickListener { openCabinetUrl() }
        binding.btnCodeSubmit.setOnClickListener { submitActivationCode() }
        setupCodeFields()
        updateSubscriptionHeader()
        updateServerRow()
        updateSubBlock()
    }

    private fun toggleSubBlock() {
        val expanding = binding.layoutSubBody.visibility != View.VISIBLE
        binding.layoutSubBody.visibility = if (expanding) View.VISIBLE else View.GONE
        binding.tvSubChevron.text = if (expanding) "∨" else "∧"
    }

    private fun openCabinetUrl() {
        val url = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(PREF_CABINET_URL, null) ?: return
        Utils.openUri(this, url)
    }

    private fun onServerRowTapped() {
        val plan = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(PREF_PLAN, "free")
        if (plan == "free") {
            AlertDialog.Builder(this)
                .setTitle(R.string.vsm_server_dialog_title)
                .setMessage(R.string.vsm_server_dialog_body)
                .setNegativeButton(R.string.vsm_server_dialog_cancel) { _, _ -> }
                .setPositiveButton(R.string.vsm_server_dialog_cta) { _, _ -> openCabinetUrl() }
                .show()
        }
        // Paid multi-server selection: Phase 2
    }

    private fun setupCodeFields() {
        codeFields = arrayOf(
            binding.etCode1, binding.etCode2, binding.etCode3, binding.etCode4,
            binding.etCode5, binding.etCode6, binding.etCode7, binding.etCode8
        )

        val alphanumericFilter = InputFilter { source, _, _, _, _, _ ->
            if (isPasting) return@InputFilter null  // handled by paste logic
            source.filter { it.isLetterOrDigit() }.toString().uppercase()
        }

        for (i in codeFields.indices) {
            val field = codeFields[i]
            field.filters = arrayOf(alphanumericFilter, InputFilter.LengthFilter(1))

            // Paste detection: if pasted text is longer than 1 char, try to fill all fields
            field.filters = arrayOf(
                InputFilter { source, start, end, _, _, _ ->
                    val chunk = source.subSequence(start, end).toString()
                    if (chunk.length > 1) {
                        // This is a paste — handle it asynchronously to avoid filter recursion
                        field.post { handleCodePaste(chunk) }
                        return@InputFilter ""  // block the raw paste into this single field
                    }
                    if (isPasting) return@InputFilter null
                    chunk.filter { it.isLetterOrDigit() }.toString().uppercase()
                        .takeIf { it.isNotEmpty() } ?: ""
                },
                InputFilter.LengthFilter(1)
            )

            field.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (isPasting) return
                    if ((s?.length ?: 0) == 1 && i < codeFields.lastIndex) {
                        codeFields[i + 1].requestFocus()
                    }
                    checkSubmitEnabled()
                }
            })

            field.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DEL && event.action == KeyEvent.ACTION_DOWN) {
                    if (field.text.isNullOrEmpty() && i > 0) {
                        val prev = codeFields[i - 1]
                        prev.requestFocus()
                        prev.text?.clear()
                        checkSubmitEnabled()
                        return@setOnKeyListener true
                    }
                }
                false
            }
        }
    }

    private fun handleCodePaste(raw: String) {
        // Strip hyphens/spaces, uppercase, keep only alphanumeric
        val clean = raw.replace("-", "").replace(" ", "")
            .filter { it.isLetterOrDigit() }.toString().uppercase()
        if (clean.length != 8) return  // wrong format — ignore
        isPasting = true
        for (i in codeFields.indices) {
            codeFields[i].setText(clean[i].toString())
        }
        isPasting = false
        codeFields.last().requestFocus()
        checkSubmitEnabled()
    }

    private fun checkSubmitEnabled() {
        val allFilled = codeFields.all { it.text?.length == 1 }
        binding.btnCodeSubmit.isEnabled = allFilled
        binding.btnCodeSubmit.alpha = if (allFilled) 1f else 0.4f
    }

    private fun submitActivationCode() {
        val code = codeFields.joinToString("") { it.text.toString() }
        if (code.length != 8) return
        codeFields.forEach { it.text?.clear() }
        codeFields.first().requestFocus()
        checkSubmitEnabled()
        pendingActivationToken = code
        handleActivateDeepLink(code)
    }

    /**
     * Refresh the subscription row text and colour from SharedPreferences.
     * Call after provisioning or status poll.
     */
    private fun updateSubscriptionHeader() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val plan = prefs.getString(PREF_PLAN, "free")
        val days = prefs.getInt(PREF_PAID_DAYS_REMAINING, 0)

        if (plan == "free") {
            binding.tvSubValue.text = getString(R.string.vsm_sub_free)
            binding.tvSubValue.setTextColor(ContextCompat.getColor(this, R.color.vsm_sub_free))
            binding.tvSubValue.clearAnimation()
        } else {
            binding.tvSubValue.text = resources.getQuantityString(R.plurals.vsm_days_remaining, days, days)
            when {
                days <= 1 -> {
                    binding.tvSubValue.setTextColor(ContextCompat.getColor(this, R.color.vsm_sub_urgent))
                    startBlinking(binding.tvSubValue)
                }
                days <= 5 -> {
                    binding.tvSubValue.setTextColor(ContextCompat.getColor(this, R.color.vsm_sub_warn))
                    binding.tvSubValue.clearAnimation()
                }
                else -> {
                    binding.tvSubValue.setTextColor(ContextCompat.getColor(this, R.color.vsm_sub_ok))
                    binding.tvSubValue.clearAnimation()
                }
            }
        }

        // Cabinet link is always shown; openCabinetUrl() is a no-op if URL not yet known
        binding.tvSubLink.visibility = View.VISIBLE
    }

    private fun startBlinking(view: View) {
        val anim = AlphaAnimation(1.0f, 0.15f).apply {
            duration = 550
            repeatMode = Animation.REVERSE
            repeatCount = Animation.INFINITE
        }
        view.startAnimation(anim)
    }

    /**
     * Update the server row flag, name, and arrow colour.
     * Phase 1: single Frankfurt server, hardcoded.
     * TODO Phase 2: derive from /servers response and user selection.
     */
    private fun updateServerRow() {
        binding.tvServerFlag.text = "🇩🇪"
        binding.tvServerName.text = "Франкфурт, Германия"

        val plan = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_PLAN, "free")
        // Arrow is active (darker) only for paid users who can actually change servers
        binding.tvServerArrow.setTextColor(
            ContextCompat.getColor(
                this,
                if (plan == "paid") R.color.vsm_sub_free else R.color.vsm_border
            )
        )
    }

    /**
     * Update the collapsible sub-block: donut charts, comparison table, action buttons.
     * Uses values saved by the last /provision or /status response.
     */
    private fun updateSubBlock() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val plan = prefs.getString(PREF_PLAN, "free")
        val days = prefs.getInt(PREF_PAID_DAYS_REMAINING, 0)
        val trafficTotalGb = prefs.getFloat(PREF_TRAFFIC_TOTAL_GB, if (plan == "paid") 250f else 25f)
        val trafficRemainingGb = prefs.getFloat(PREF_TRAFFIC_REMAINING_GB, trafficTotalGb)
        val throttleMbps = prefs.getFloat(PREF_THROTTLE_MBPS, 0f)

        val isNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        val ringBg = ContextCompat.getColor(this, R.color.vsm_ring_bg)

        // ── Traffic ring ─────────────────────────────────────────────────
        val trafficFraction = (trafficRemainingGb / trafficTotalGb).coerceIn(0f, 1f)
        binding.chartTraffic.apply {
            ringBgColor = ringBg
            ringColor = trafficRingColor(trafficFraction)
            fraction = trafficFraction
            centerValue = formatGb(trafficRemainingGb)
            centerSub = getString(R.string.vsm_traffic_of_format, trafficTotalGb.toInt())
            subTextColor = ContextCompat.getColor(this@MainActivity, R.color.vsm_hint_text)
            showGhostArc = false
        }

        // ── Speed ring ───────────────────────────────────────────────────
        // throttle_mbps from /status represents the tier cap (0 = not yet polled)
        val speedFraction = if (throttleMbps > 0) (throttleMbps / 25f).coerceIn(0f, 1f) else 0f
        val speedColor = if (plan == "free")
            ContextCompat.getColor(this, R.color.vsm_speed_blue)
        else
            ContextCompat.getColor(this, R.color.vsm_mint)

        binding.chartSpeed.apply {
            ringBgColor = ringBg
            ringColor = speedColor
            fraction = speedFraction
            centerValue = if (throttleMbps > 0f)
                if (throttleMbps % 1f == 0f) throttleMbps.toInt().toString()
                else String.format("%.1f", throttleMbps)
            else "–"
            centerSub = getString(R.string.vsm_speed_of_format, 25)
            subTextColor = ContextCompat.getColor(this@MainActivity, R.color.vsm_hint_text)
            showGhostArc = (plan == "free")
            ghostColor = ContextCompat.getColor(this@MainActivity, R.color.vsm_mint)
            ghostAlpha = if (isNight) 77 else 56
        }

        // ── Table and buttons ────────────────────────────────────────────
        // Free traffic cell is dynamic (from traffic_cap_mb via PREF_TRAFFIC_TOTAL_GB).
        // Paid traffic cell stays static — /status for free users doesn't return the paid cap.
        binding.tvCmpFreeTraffic.text = "${trafficTotalGb.toInt()} ГБ"

        if (plan == "free") {
            binding.layoutCmpTable.visibility = View.VISIBLE
            binding.btnPay.visibility = View.VISIBLE
            binding.btnFamily.visibility = View.GONE
            binding.btnRenew.visibility = View.GONE
        } else {
            binding.layoutCmpTable.visibility = View.GONE
            binding.btnPay.visibility = View.GONE
            if (days <= 3) {
                binding.btnRenew.visibility = View.VISIBLE
                binding.btnFamily.visibility = View.GONE
            } else {
                binding.btnFamily.visibility = View.VISIBLE
                binding.btnRenew.visibility = View.GONE
            }
        }

        // ── Activation code section ───────────────────────────────────────
        // Show for free users and paid users running low on time; hide for comfortable paid
        binding.layoutActivationSection.visibility =
            if (plan == "free" || (plan == "paid" && days <= 3)) View.VISIBLE else View.GONE
    }

    private fun trafficRingColor(fraction: Float): Int = ContextCompat.getColor(
        this, when {
            fraction > 0.50f -> R.color.vsm_traffic_green
            fraction > 0.25f -> R.color.vsm_traffic_amber
            fraction > 0.10f -> R.color.vsm_traffic_orange
            else             -> R.color.vsm_traffic_red
        }
    )

    /** Format GB value: integer when ≥ 10, one decimal place when < 10. */
    private fun formatGb(gb: Float): String =
        if (gb >= 10f) gb.toInt().toString() else String.format("%.1f", gb)

    // ── End VseMoiOnline UI ──────────────────────────────────────────────

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        // Handle navigation view item clicks here.
        when (item.itemId) {
            R.id.per_app_proxy_settings -> startActivity(Intent(this, PerAppProxyActivity::class.java))
            R.id.source_code -> Utils.openUri(this, AppConfig.APP_URL)
            R.id.oss_licenses -> {
                val webView = android.webkit.WebView(this)
                webView.loadUrl("file:///android_asset/open_source_licenses.html")
                android.app.AlertDialog.Builder(this)
                    .setTitle("Open source licenses")
                    .setView(webView)
                    .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                    .show()
            }
            R.id.tg_channel -> Utils.openUri(this, AppConfig.TG_CHANNEL_URL)
            R.id.privacy_policy -> Utils.openUri(this, AppConfig.APP_PRIVACY_POLICY)
        }

        binding.drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }
}