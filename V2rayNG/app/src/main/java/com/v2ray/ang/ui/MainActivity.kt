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
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
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
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.helper.SimpleItemTouchHelperCallback
import com.v2ray.ang.handler.SpeedtestManager
import com.v2ray.ang.handler.V2RayServiceManager
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
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

        // Two-cycle provisioning timeouts for better UX
        private const val PROVISION_TIMEOUT_QUICK_MS = 3000  // 3 seconds - Cycle 1: quick scan
        private const val PROVISION_TIMEOUT_PATIENT_MS = 10000 // 10 seconds - Cycle 2: patient retry

        // Primary provisioning URL (will be replaced with domain + HTTPS in production)
        private const val PRIMARY_PROVISION_URL = "http://103.241.67.124:8888/provision"

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
        title = getString(R.string.title_server)
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
     * VseMoiOnline: Handle magic link provisioning
     * Extracts URL from vsemoionline://import?url=... and fetches VLESS config
     */
    private fun handleMagicLink(intent: Intent?) {
        if (intent?.data?.scheme == "vsemoionline") {
            val provisionUrl = intent.data?.getQueryParameter("url")
            if (provisionUrl != null) {
                Log.i(AppConfig.TAG, "VseMoiOnline: Magic link detected, provisioning from: $provisionUrl")
                binding.pbWaiting.show()
                lifecycleScope.launch(Dispatchers.IO) {
                    val success = fetchAndImportConfig(provisionUrl, PROVISION_TIMEOUT_PATIENT_MS)
                    if (success) {
                        saveLastWorkingProvisionUrl(provisionUrl)
                    }
                    withContext(Dispatchers.Main) {
                        binding.pbWaiting.hide()
                    }
                }
            } else {
                Log.w(AppConfig.TAG, "VseMoiOnline: Magic link missing 'url' parameter")
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
     * VseMoiOnline: Fetch VLESS config from backend and import it
     * Downloads config from URL with device_id parameter appended
     *
     * @param baseUrl The provisioning URL to fetch from
     * @param timeoutMs Timeout in milliseconds for this request
     * @return true if successful, false otherwise
     */
    private suspend fun fetchAndImportConfig(baseUrl: String, timeoutMs: Int): Boolean {
        return try {
            val deviceId = getOrCreateDeviceId()
            val separator = if (baseUrl.contains("?")) "&" else "?"
            val fullUrl = "$baseUrl${separator}device_id=$deviceId"

            Log.i(AppConfig.TAG, "VseMoiOnline: Fetching config from: $fullUrl")

            // Download VLESS URI from backend
            val url = java.net.URL(fullUrl)
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs

            val responseCode = connection.responseCode
            if (responseCode == java.net.HttpURLConnection.HTTP_OK) {
                val vlessUri = connection.inputStream.bufferedReader().readText().trim()
                Log.i(AppConfig.TAG, "VseMoiOnline: Received config, importing...")

                connection.disconnect()

                // Import the VLESS URI using existing v2rayNG functionality
                withContext(Dispatchers.Main) {
                    importBatchConfig(vlessUri)
                    toast(R.string.toast_success)
                }
                true
            } else {
                connection.disconnect()
                Log.w(AppConfig.TAG, "VseMoiOnline: HTTP error: $responseCode")
                false
            }

        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "VseMoiOnline: Failed to fetch config from $baseUrl", e)
            throw e // Re-throw to allow timeout detection
        }
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
                binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_active))
                binding.fab.contentDescription = getString(R.string.action_stop_service)
                setTestState(getString(R.string.connection_connected))
                binding.layoutTest.isFocusable = true
            } else {
                binding.fab.setImageResource(R.drawable.ic_play_24dp)
                binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_inactive))
                binding.fab.contentDescription = getString(R.string.tasker_start_service)
                setTestState(getString(R.string.connection_not_connected))
                binding.layoutTest.isFocusable = false
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
        binding.tabGroup.isVisible = true
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

        // VseMoiOnline: Auto-provision on first launch if no servers configured
        checkAndAutoProvision()
    }

    /**
     * VseMoiOnline: Auto-provision if no servers configured
     * Retries on every resume until successful
     * Uses fallback URLs if primary provisioning endpoint is unavailable
     */
    private fun checkAndAutoProvision() {
        // Check if there are any servers configured
        val serverList = MmkvManager.decodeServerList()
        if (serverList.isEmpty()) {
            Log.i(AppConfig.TAG, "VseMoiOnline: No servers configured, attempting auto-provisioning with fallback")
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