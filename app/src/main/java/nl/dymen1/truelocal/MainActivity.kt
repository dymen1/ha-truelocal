package nl.dymen1.truelocal

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.ParcelUuid
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.tabs.TabLayout
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private val httpClient = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var bleProxyClient: BleProxyClient? = null
    private var matterClient: MatterWsClient? = null

    private lateinit var homeAssistantUrlInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var pairingCodeInput: EditText
    private lateinit var connectButton: Button
    private lateinit var commissionButton: Button
    private lateinit var scanQrButton: Button
    private lateinit var languageButton: Button
    private lateinit var aboutView: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var deviceListView: TextView
    private lateinit var nodesListView: TextView
    private lateinit var tabLayout: TabLayout
    private lateinit var matterContainer: LinearLayout
    private lateinit var zigbeeContainer: LinearLayout
    private lateinit var zigbeeRefreshButton: Button
    private lateinit var permitJoinButton: Button
    private lateinit var zigbeeDevicesView: TextView
    private lateinit var zigbee2Container: LinearLayout
    private lateinit var zigbee2RefreshButton: Button
    private lateinit var zigbee2PermitJoinButton: Button
    private lateinit var zigbee2DevicesView: TextView
    private var haWebSocketClient: HaWebSocketClient? = null
    private lateinit var matterTestButton: Button
    private lateinit var matterStatusView: TextView
    private lateinit var zigbeeTestButton: Button
    private lateinit var zigbeeStatusView: TextView
    private lateinit var zigbee2TestButton: Button
    private lateinit var zigbee2StatusView: TextView
    private lateinit var matterDevicesHeader: TextView
    private lateinit var matterDevicesSection: LinearLayout
    private lateinit var matterEnvHeader: TextView
    private lateinit var matterEnvSection: LinearLayout
    private lateinit var zigbeeDevicesHeader: TextView
    private lateinit var zigbeeDevicesSection: LinearLayout
    private lateinit var zigbeeEnvHeader: TextView
    private lateinit var zigbeeEnvSection: LinearLayout
    private lateinit var zigbee2DevicesHeader: TextView
    private lateinit var zigbee2DevicesSection: LinearLayout
    private lateinit var zigbee2EnvHeader: TextView
    private lateinit var zigbee2EnvSection: LinearLayout

    private val discoveredDevices = mutableMapOf<String, DiscoveredDevice>()
    private var deviceScanCallback: ScanCallback? = null

    private data class DiscoveredDevice(val lastSeenMillis: Long, val rssi: Int, val label: String)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants.values.any { !it }) {
                appendLog(getString(R.string.msg_permissions_denied))
            } else {
                startDeviceListScan()
            }
        }

    private val qrLauncher = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { contents ->
            pairingCodeInput.setText(contents)
            appendLog(getString(R.string.msg_qr_scanned, contents))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        homeAssistantUrlInput = findViewById(R.id.homeAssistantUrlInput)
        tokenInput = findViewById(R.id.tokenInput)
        pairingCodeInput = findViewById(R.id.pairingCodeInput)
        connectButton = findViewById(R.id.connectButton)
        commissionButton = findViewById(R.id.commissionButton)
        scanQrButton = findViewById(R.id.scanQrButton)
        languageButton = findViewById(R.id.languageButton)
        aboutView = findViewById(R.id.aboutView)
        logView = findViewById(R.id.logView)
        logScroll = findViewById(R.id.logScroll)
        deviceListView = findViewById(R.id.deviceListView)
        nodesListView = findViewById(R.id.nodesListView)
        tabLayout = findViewById(R.id.tabLayout)
        matterContainer = findViewById(R.id.matterContainer)
        zigbeeContainer = findViewById(R.id.zigbeeContainer)
        zigbeeRefreshButton = findViewById(R.id.zigbeeRefreshButton)
        permitJoinButton = findViewById(R.id.permitJoinButton)
        zigbeeDevicesView = findViewById(R.id.zigbeeDevicesView)
        zigbee2Container = findViewById(R.id.zigbee2Container)
        zigbee2RefreshButton = findViewById(R.id.zigbee2RefreshButton)
        zigbee2PermitJoinButton = findViewById(R.id.zigbee2PermitJoinButton)
        zigbee2DevicesView = findViewById(R.id.zigbee2DevicesView)
        matterTestButton = findViewById(R.id.matterTestButton)
        matterStatusView = findViewById(R.id.matterStatusView)
        zigbeeTestButton = findViewById(R.id.zigbeeTestButton)
        zigbeeStatusView = findViewById(R.id.zigbeeStatusView)
        zigbee2TestButton = findViewById(R.id.zigbee2TestButton)
        zigbee2StatusView = findViewById(R.id.zigbee2StatusView)
        matterDevicesHeader = findViewById(R.id.matterDevicesHeader)
        matterDevicesSection = findViewById(R.id.matterDevicesSection)
        matterEnvHeader = findViewById(R.id.matterEnvHeader)
        matterEnvSection = findViewById(R.id.matterEnvSection)
        zigbeeDevicesHeader = findViewById(R.id.zigbeeDevicesHeader)
        zigbeeDevicesSection = findViewById(R.id.zigbeeDevicesSection)
        zigbeeEnvHeader = findViewById(R.id.zigbeeEnvHeader)
        zigbeeEnvSection = findViewById(R.id.zigbeeEnvSection)
        zigbee2DevicesHeader = findViewById(R.id.zigbee2DevicesHeader)
        zigbee2DevicesSection = findViewById(R.id.zigbee2DevicesSection)
        zigbee2EnvHeader = findViewById(R.id.zigbee2EnvHeader)
        zigbee2EnvSection = findViewById(R.id.zigbee2EnvSection)

        restoreInputs()

        permissionLauncher.launch(
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        )

        connectButton.setOnClickListener {
            saveInputs()
            connectButton.isEnabled = false
            lifecycleScope.launch { connectAll() }
        }
        commissionButton.setOnClickListener {
            saveInputs()
            commissionButton.isEnabled = false
            lifecycleScope.launch { commission() }
        }
        scanQrButton.setOnClickListener {
            qrLauncher.launch(
                ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt(getString(R.string.qr_prompt))
                    .setBeepEnabled(false)
                    .setCaptureActivity(PortraitCaptureActivity::class.java)
            )
        }
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                matterContainer.visibility = if (tab.position == 0) View.VISIBLE else View.GONE
                zigbee2Container.visibility = if (tab.position == 1) View.VISIBLE else View.GONE
                zigbeeContainer.visibility = if (tab.position == 2) View.VISIBLE else View.GONE
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}

            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        zigbeeRefreshButton.setOnClickListener {
            saveInputs()
            zigbeeRefreshButton.isEnabled = false
            lifecycleScope.launch { refreshZigbeeDevices() }
        }
        permitJoinButton.setOnClickListener {
            saveInputs()
            permitJoinButton.isEnabled = false
            lifecycleScope.launch { permitZigbeeJoin() }
        }
        zigbee2RefreshButton.setOnClickListener {
            saveInputs()
            zigbee2RefreshButton.isEnabled = false
            lifecycleScope.launch { refreshZigbee2Devices() }
        }
        zigbee2PermitJoinButton.setOnClickListener {
            saveInputs()
            zigbee2PermitJoinButton.isEnabled = false
            lifecycleScope.launch { permitZigbee2Join() }
        }
        languageButton.setOnClickListener {
            val currentLanguage = resources.configuration.locales[0].language
            val targetLanguage = if (currentLanguage == "nl") "en" else "nl"
            AppCompatDelegate.setApplicationLocales(
                LocaleListCompat.forLanguageTags(targetLanguage)
            )
        }
        aboutView.setOnClickListener {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL)))
            }
        }
        matterTestButton.setOnClickListener { startMatterEnvironmentTest() }
        zigbeeTestButton.setOnClickListener { startZigbeeEnvironmentTest() }
        zigbee2TestButton.setOnClickListener { startZigbee2EnvironmentTest() }
        setupAccordion(
            matterDevicesHeader,
            matterDevicesSection,
            getString(R.string.section_add_devices),
            true
        )
        setupAccordion(
            matterEnvHeader,
            matterEnvSection,
            getString(R.string.section_env_check),
            false
        ) {
            startMatterEnvironmentTest()
        }
        setupAccordion(
            zigbeeDevicesHeader,
            zigbeeDevicesSection,
            getString(R.string.section_add_devices),
            true
        )
        setupAccordion(
            zigbeeEnvHeader,
            zigbeeEnvSection,
            getString(R.string.section_env_check),
            false
        ) {
            startZigbeeEnvironmentTest()
        }
        setupAccordion(
            zigbee2DevicesHeader,
            zigbee2DevicesSection,
            getString(R.string.section_add_devices),
            true
        )
        setupAccordion(
            zigbee2EnvHeader,
            zigbee2EnvSection,
            getString(R.string.section_env_check),
            false
        ) {
            startZigbee2EnvironmentTest()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopDeviceListScan()
        bleProxyClient?.close()
        matterClient?.close()
        haWebSocketClient?.close()
    }

    @SuppressLint("MissingPermission")
    private fun startDeviceListScan() {
        if (deviceScanCallback != null) return
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            appendLog(getString(R.string.msg_bluetooth_off))
            return
        }
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BleCentral.normalizeUuid(MATTER_SERVICE_UUID)))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .build()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                registerDiscoveredDevice(result)
            }
        }
        adapter.bluetoothLeScanner.startScan(listOf(filter), settings, callback)
        deviceScanCallback = callback
    }

    @SuppressLint("MissingPermission")
    private fun stopDeviceListScan() {
        deviceScanCallback?.let { callback ->
            (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager)
                .adapter?.bluetoothLeScanner?.stopScan(callback)
        }
        deviceScanCallback = null
    }

    private fun registerDiscoveredDevice(result: ScanResult) {
        val serviceData = result.scanRecord
            ?.getServiceData(ParcelUuid(BleCentral.normalizeUuid(MATTER_SERVICE_UUID)))
        val details = if (serviceData != null && serviceData.size >= 7) {
            val discriminator = ((serviceData[2].toInt() and 0x0f) shl 8) or
                (serviceData[1].toInt() and 0xff)
            val vendorId = ((serviceData[4].toInt() and 0xff) shl 8) or
                (serviceData[3].toInt() and 0xff)
            val productId = ((serviceData[6].toInt() and 0xff) shl 8) or
                (serviceData[5].toInt() and 0xff)
            "disc %04d vid 0x%04X pid 0x%04X".format(discriminator, vendorId, productId)
        } else {
            getString(R.string.no_matter_advertisement)
        }
        val name = result.scanRecord?.deviceName ?: getString(R.string.unnamed_device)
        val label = "%s  %s  %s  %d dBm".format(result.device.address, name, details, result.rssi)
        synchronized(discoveredDevices) {
            discoveredDevices[result.device.address] =
                DiscoveredDevice(System.currentTimeMillis(), result.rssi, label)
        }
        renderDeviceList()
    }

    private suspend fun refreshNodeList(matter: MatterWsClient) {
        try {
            val nodes = matter.listNodes()
            val lines = mutableListOf<String>()
            for (index in 0 until nodes.length()) {
                val node = nodes.getJSONObject(index)
                val attributes = node.optJSONObject("attributes")
                val vendorName = attributes?.optString("0/40/1").orEmpty()
                val productName = attributes?.optString("0/40/3").orEmpty()
                val nodeLabel = attributes?.optString("0/40/5").orEmpty()
                val name = listOf(vendorName, productName)
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
                    .ifBlank { getString(R.string.unknown_device) }
                val labelSuffix = if (nodeLabel.isBlank()) "" else " ($nodeLabel)"
                val availability = if (node.optBoolean("available")) {
                    getString(R.string.status_online)
                } else {
                    getString(R.string.status_offline)
                }
                lines.add(
                    "Node %-4d %s  %s%s"
                        .format(node.optLong("node_id"), availability, name, labelSuffix)
                )
            }
            runOnUiThread {
                nodesListView.text =
                    if (lines.isEmpty()) getString(R.string.msg_no_nodes)
                    else lines.joinToString(System.lineSeparator())
            }
            appendLog(getString(R.string.msg_node_count, lines.size))
        } catch (error: Exception) {
            appendLog(getString(R.string.msg_nodes_failed, error.message))
        }
    }

    private suspend fun resolveAccess(
        homeAssistantUrl: String,
        token: String
    ): HaIngress.IngressAccess {
        if (token.isEmpty()) {
            return HaIngress.IngressAccess(
                homeAssistantUrl.trimEnd('/').replaceFirst("http", "ws"),
                emptyMap()
            )
        }
        val haWebSocket = ensureHaWebSocket()
            ?: throw IllegalStateException("HA websocket connection required for ingress")
        val addonInfo = haWebSocket.supervisorApi("/addons/core_matter_server/info")
            ?: throw IllegalStateException("Supervisor API returned no Matter Server add-on info")
        val ingressPath = addonInfo.getString("ingress_url").trimEnd('/')
        val session = haWebSocket.supervisorApi("/ingress/session", "post")
            ?.optString("session")
        if (session.isNullOrBlank()) {
            throw IllegalStateException("Supervisor API refused to create an ingress session")
        }
        return HaIngress.buildAccess(homeAssistantUrl, ingressPath, session, token)
    }

    private suspend fun ensureHaWebSocket(): HaWebSocketClient? {
        haWebSocketClient?.let { return it }
        val token = tokenInput.text.toString().trim()
        if (token.isEmpty()) {
            appendLog(getString(R.string.msg_token_required))
            return null
        }
        return try {
            val client = HaWebSocketClient(
                homeAssistantUrlInput.text.toString().trim(),
                token,
                ::appendLog
            )
            withTimeout(15_000) { client.connect(httpClient).await() }
            haWebSocketClient = client
            client
        } catch (error: Exception) {
            appendLog(getString(R.string.msg_ha_ws_failed, error.message))
            null
        }
    }

    private suspend fun refreshZigbeeDevices() {
        try {
            val haWebSocket = ensureHaWebSocket() ?: return
            val devices = haWebSocket.listZhaDevices()
            val lines = mutableListOf<String>()
            for (index in 0 until devices.length()) {
                val device = devices.getJSONObject(index)
                val availability = if (device.optBoolean("available")) {
                    getString(R.string.status_online)
                } else {
                    getString(R.string.status_offline)
                }
                val name = device.optString("user_given_name")
                    .ifBlank { device.optString("name") }
                    .ifBlank { device.optString("model") }
                lines.add("%s  %s  %s".format(availability, name, device.optString("ieee")))
            }
            runOnUiThread {
                zigbeeDevicesView.text =
                    if (lines.isEmpty()) getString(R.string.msg_no_zigbee_devices)
                    else lines.joinToString(System.lineSeparator())
            }
            appendLog(getString(R.string.msg_zigbee_count, lines.size))
        } catch (error: Exception) {
            appendLog(getString(R.string.msg_zigbee_failed, error.message))
        } finally {
            runOnUiThread { zigbeeRefreshButton.isEnabled = true }
        }
    }

    private suspend fun permitZigbeeJoin() {
        try {
            val haWebSocket = ensureHaWebSocket() ?: return
            haWebSocket.permitZhaJoin(60)
            appendLog(getString(R.string.msg_permit_join_open))
        } catch (error: Exception) {
            appendLog(getString(R.string.msg_permit_join_failed, error.message))
        } finally {
            runOnUiThread { permitJoinButton.isEnabled = true }
        }
    }

    private suspend fun refreshZigbee2Devices() {
        try {
            val haWebSocket = ensureHaWebSocket() ?: return
            val devices = haWebSocket.listDeviceRegistry()
            val lines = mutableListOf<String>()
            for (index in 0 until devices.length()) {
                val device = devices.getJSONObject(index)
                val identifier = zigbee2MqttIdentifier(device) ?: continue
                if (identifier.contains("bridge")) continue
                val name = device.optString("name_by_user").ifBlank { device.optString("name") }
                val model = device.optString("model")
                lines.add("%s  %s  %s".format(name, model, identifier.substringAfterLast("_")))
            }
            runOnUiThread {
                zigbee2DevicesView.text =
                    if (lines.isEmpty()) getString(R.string.msg_no_zigbee2_devices)
                    else lines.joinToString(System.lineSeparator())
            }
            appendLog(getString(R.string.msg_zigbee2_count, lines.size))
        } catch (error: Exception) {
            appendLog(getString(R.string.msg_zigbee2_failed, error.message))
        } finally {
            runOnUiThread { zigbee2RefreshButton.isEnabled = true }
        }
    }

    private fun zigbee2MqttIdentifier(device: JSONObject): String? {
        val identifiers = device.optJSONArray("identifiers") ?: return null
        for (index in 0 until identifiers.length()) {
            val pair = identifiers.optJSONArray(index) ?: continue
            val joined = (0 until pair.length()).joinToString("_") { pair.optString(it) }
            if (joined.contains("zigbee2mqtt")) return joined
        }
        return null
    }

    private suspend fun permitZigbee2Join() {
        try {
            val haWebSocket = ensureHaWebSocket() ?: return
            haWebSocket.publishMqtt(
                "zigbee2mqtt/bridge/request/permit_join",
                JSONObject().put("time", 60).toString()
            )
            appendLog(getString(R.string.msg_zigbee2_permit_open))
        } catch (error: Exception) {
            appendLog(getString(R.string.msg_zigbee2_permit_failed, error.message))
        } finally {
            runOnUiThread { zigbee2PermitJoinButton.isEnabled = true }
        }
    }

    private fun setupAccordion(
        header: TextView,
        section: View,
        title: String,
        expandedByDefault: Boolean,
        onExpand: (() -> Unit)? = null
    ) {
        fun render(expanded: Boolean) {
            header.text = (if (expanded) "▾ " else "▸ ") + title
            section.visibility = if (expanded) View.VISIBLE else View.GONE
        }
        render(expandedByDefault)
        header.setOnClickListener {
            val expand = section.visibility != View.VISIBLE
            render(expand)
            if (expand) onExpand?.invoke()
        }
    }

    private fun startMatterEnvironmentTest() {
        if (!matterTestButton.isEnabled) return
        saveInputs()
        matterTestButton.isEnabled = false
        matterStatusView.text = getString(R.string.status_testing)
        lifecycleScope.launch { testMatterEnvironment() }
    }

    private fun startZigbeeEnvironmentTest() {
        if (!zigbeeTestButton.isEnabled) return
        saveInputs()
        zigbeeTestButton.isEnabled = false
        zigbeeStatusView.text = getString(R.string.status_testing)
        lifecycleScope.launch { testZigbeeEnvironment() }
    }

    private fun startZigbee2EnvironmentTest() {
        if (!zigbee2TestButton.isEnabled) return
        saveInputs()
        zigbee2TestButton.isEnabled = false
        zigbee2StatusView.text = getString(R.string.status_testing)
        lifecycleScope.launch { testZigbee2Environment() }
    }

    private fun checkLine(passed: Boolean, label: String): String =
        (if (passed) getString(R.string.check_ok) else getString(R.string.check_fail)) + label

    private fun versionSuffix(addon: JSONObject?): String {
        val version = addon?.optString("version").orEmpty()
        return if (version.isBlank()) "" else " (v$version)"
    }

    private suspend fun loadedConfigEntryDomains(haWebSocket: HaWebSocketClient): Set<String> {
        val entries = haWebSocket.listConfigEntries()
        val domains = mutableSetOf<String>()
        for (index in 0 until entries.length()) {
            val entry = entries.getJSONObject(index)
            if (entry.optString("state") == "loaded") domains.add(entry.optString("domain"))
        }
        return domains
    }

    private suspend fun testMatterEnvironment() {
        val lines = mutableListOf<String>()
        try {
            val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
            lines.add(
                checkLine(
                    adapter != null && adapter.isEnabled,
                    getString(R.string.check_phone_bluetooth)
                )
            )
            val homeAssistantUrl = homeAssistantUrlInput.text.toString().trim()
            val token = tokenInput.text.toString().trim()
            if (token.isEmpty()) {
                lines.add(checkLine(true, getString(R.string.check_direct_mode_skipped)))
            } else {
                val haWebSocket = ensureHaWebSocket()
                if (haWebSocket != null) {
                    val matterAddon = haWebSocket.supervisorApi("/addons/core_matter_server/info")
                    lines.add(
                        checkLine(
                            matterAddon != null,
                            getString(
                                R.string.check_matter_addon_installed,
                                versionSuffix(matterAddon)
                            )
                        )
                    )
                    lines.add(
                        checkLine(
                            matterAddon?.optString("state") == "started",
                            getString(R.string.check_matter_addon_started)
                        )
                    )
                    lines.add(
                        checkLine(
                            matterAddon?.optBoolean("ingress") == true,
                            getString(R.string.check_matter_addon_ingress)
                        )
                    )
                    val otbrAddon =
                        haWebSocket.supervisorApi("/addons/core_openthread_border_router/info")
                    lines.add(
                        checkLine(
                            otbrAddon?.optString("state") == "started",
                            getString(R.string.check_otbr_started, versionSuffix(otbrAddon))
                        )
                    )
                    val loadedDomains = loadedConfigEntryDomains(haWebSocket)
                    lines.add(
                        checkLine(
                            "matter" in loadedDomains,
                            getString(R.string.check_matter_integration)
                        )
                    )
                    lines.add(
                        checkLine(
                            "thread" in loadedDomains,
                            getString(R.string.check_thread_integration)
                        )
                    )
                    try {
                        val datasets = haWebSocket.listThreadDatasets()
                        var preferredNetwork: String? = null
                        for (index in 0 until datasets.length()) {
                            val dataset = datasets.getJSONObject(index)
                            if (dataset.optBoolean("preferred")) {
                                preferredNetwork = dataset.optString("network_name") + " " +
                                    getString(R.string.thread_channel, dataset.optInt("channel"))
                                break
                            }
                        }
                        lines.add(
                            checkLine(
                                preferredNetwork != null,
                                if (preferredNetwork != null) {
                                    getString(R.string.check_preferred_thread, preferredNetwork)
                                } else {
                                    getString(R.string.check_no_preferred_thread)
                                }
                            )
                        )
                    } catch (error: Exception) {
                        lines.add(
                            checkLine(
                                false,
                                getString(R.string.check_thread_datasets_error, error.message)
                            )
                        )
                    }
                } else {
                    lines.add(checkLine(false, getString(R.string.check_ha_ws_unreachable)))
                }
            }
            val access = resolveAccess(homeAssistantUrl, token)
            lines.add(
                checkLine(
                    true,
                    if (token.isEmpty()) getString(R.string.check_direct_connection)
                    else getString(R.string.check_ingress_session)
                )
            )
            val matter = MatterWsClient("${access.webSocketBase}/ws", access.headers) {}
            try {
                withTimeout(10_000) { matter.connect(httpClient).await() }
                val serverInfo = matter.serverInfo
                lines.add(
                    checkLine(serverInfo != null, getString(R.string.check_matter_ws_reachable))
                )
                lines.add(
                    checkLine(
                        serverInfo?.optBoolean("bluetooth_enabled") == true,
                        getString(R.string.check_ble_proxy_active)
                    )
                )
                lines.add(
                    checkLine(
                        serverInfo?.optBoolean("thread_credentials_set") == true,
                        getString(R.string.check_thread_dataset_present)
                    )
                )
            } catch (error: Exception) {
                lines.add(
                    checkLine(false, getString(R.string.check_matter_ws_error, error.message))
                )
            } finally {
                matter.close()
            }
            lines.add(
                checkLine(
                    testBleEndpoint("${access.webSocketBase}/ble", access.headers),
                    getString(R.string.check_ble_endpoint)
                )
            )
        } catch (error: Exception) {
            lines.add(checkLine(false, getString(R.string.check_test_aborted, error.message)))
        }
        runOnUiThread {
            matterStatusView.text = lines.joinToString(System.lineSeparator())
            matterTestButton.isEnabled = true
        }
    }

    private suspend fun testBleEndpoint(url: String, headers: Map<String, String>): Boolean {
        val handshake = CompletableDeferred<Boolean>()
        val requestBuilder = Request.Builder().url(url)
        headers.forEach { (name, value) -> requestBuilder.addHeader(name, value) }
        val socket = httpClient.newWebSocket(
            requestBuilder.build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(JSONObject().put("type", "hello").put("version", 1).toString())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val json = JSONObject(text)
                    if (json.optString("type") == "hello_response") {
                        handshake.complete(!json.has("error"))
                    }
                }

                override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
                    if (!handshake.isCompleted) handshake.complete(false)
                }
            }
        )
        return try {
            withTimeout(10_000) { handshake.await() }
        } catch (timeout: Exception) {
            false
        } finally {
            socket.close(1000, null)
        }
    }

    private suspend fun testZigbeeEnvironment() {
        val lines = mutableListOf<String>()
        val haWebSocket = ensureHaWebSocket()
        lines.add(checkLine(haWebSocket != null, getString(R.string.check_ha_ws_token)))
        if (haWebSocket != null) {
            try {
                lines.add(
                    checkLine(
                        "zha" in loadedConfigEntryDomains(haWebSocket),
                        getString(R.string.check_zha_loaded)
                    )
                )
            } catch (error: Exception) {
                lines.add(
                    checkLine(false, getString(R.string.check_config_entries_error, error.message))
                )
            }
            try {
                val devices = haWebSocket.listZhaDevices()
                lines.add(
                    checkLine(true, getString(R.string.check_zha_responds, devices.length()))
                )
            } catch (error: Exception) {
                lines.add(checkLine(false, getString(R.string.check_zha_error, error.message)))
            }
        }
        runOnUiThread {
            zigbeeStatusView.text = lines.joinToString(System.lineSeparator())
            zigbeeTestButton.isEnabled = true
        }
    }

    private suspend fun testZigbee2Environment() {
        val lines = mutableListOf<String>()
        val haWebSocket = ensureHaWebSocket()
        lines.add(checkLine(haWebSocket != null, getString(R.string.check_ha_ws_token)))
        if (haWebSocket != null) {
            val mosquittoAddon = haWebSocket.supervisorApi("/addons/core_mosquitto/info")
            lines.add(
                checkLine(
                    mosquittoAddon?.optString("state") == "started",
                    getString(R.string.check_mosquitto_started, versionSuffix(mosquittoAddon))
                )
            )
            val zigbee2MqttAddon = haWebSocket.supervisorApi("/addons/45df7312_zigbee2mqtt/info")
            lines.add(
                checkLine(
                    zigbee2MqttAddon?.optString("state") == "started",
                    getString(R.string.check_zigbee2_addon_started, versionSuffix(zigbee2MqttAddon))
                )
            )
            try {
                lines.add(
                    checkLine(
                        "mqtt" in loadedConfigEntryDomains(haWebSocket),
                        getString(R.string.check_mqtt_loaded)
                    )
                )
            } catch (error: Exception) {
                lines.add(
                    checkLine(false, getString(R.string.check_config_entries_error, error.message))
                )
            }
            try {
                val devices = haWebSocket.listDeviceRegistry()
                var bridgeFound = false
                var deviceCount = 0
                for (index in 0 until devices.length()) {
                    val identifier = zigbee2MqttIdentifier(devices.getJSONObject(index)) ?: continue
                    if (identifier.contains("bridge")) bridgeFound = true else deviceCount++
                }
                lines.add(checkLine(bridgeFound, getString(R.string.check_zigbee2_bridge)))
                lines.add(
                    checkLine(true, getString(R.string.check_zigbee2_count, deviceCount))
                )
            } catch (error: Exception) {
                lines.add(
                    checkLine(false, getString(R.string.check_device_registry_error, error.message))
                )
            }
        }
        runOnUiThread {
            zigbee2StatusView.text = lines.joinToString(System.lineSeparator())
            zigbee2TestButton.isEnabled = true
        }
    }

    private fun renderDeviceList() {
        val cutoff = System.currentTimeMillis() - DEVICE_EXPIRY_MILLIS
        val lines = synchronized(discoveredDevices) {
            discoveredDevices.entries.removeAll { it.value.lastSeenMillis < cutoff }
            discoveredDevices.values.sortedByDescending { it.rssi }.map { it.label }
        }
        runOnUiThread {
            deviceListView.text =
                if (lines.isEmpty()) getString(R.string.placeholder_discovered)
                else lines.joinToString(System.lineSeparator())
        }
    }

    private suspend fun connectAll() {
        val homeAssistantUrl = homeAssistantUrlInput.text.toString().trim()
        val token = tokenInput.text.toString().trim()
        try {
            val access = resolveAccess(homeAssistantUrl, token)
            appendLog(
                if (token.isEmpty()) getString(R.string.msg_direct_mode, access.webSocketBase)
                else getString(R.string.msg_ingress_opened)
            )
            bleProxyClient?.close()
            matterClient?.close()

            val proxy = BleProxyClient(
                applicationContext,
                "${access.webSocketBase}/ble",
                access.headers,
                ::appendLog
            )
            bleProxyClient = proxy
            withTimeout(15_000) { proxy.connect(httpClient).await() }

            val matter = MatterWsClient("${access.webSocketBase}/ws", access.headers, ::appendLog)
            matterClient = matter
            withTimeout(15_000) { matter.connect(httpClient).await() }

            refreshNodeList(matter)
            appendLog(getString(R.string.msg_ready_to_commission))
            commissionButton.isEnabled = true
        } catch (error: Exception) {
            appendLog(getString(R.string.msg_connect_failed, error.message))
        } finally {
            connectButton.isEnabled = true
        }
    }

    private suspend fun commission() {
        val matter = matterClient
        if (matter == null) {
            appendLog(getString(R.string.msg_connect_first))
            commissionButton.isEnabled = true
            return
        }
        val rawCode = pairingCodeInput.text.toString().trim()
        if (rawCode.isEmpty()) {
            appendLog(getString(R.string.msg_enter_code))
            commissionButton.isEnabled = true
            return
        }
        val code = if (rawCode.startsWith("MT:")) rawCode else rawCode.replace("-", "")
        appendLog(getString(R.string.msg_commissioning_started))
        try {
            val response = withTimeout(180_000) { matter.commission(code) }
            if (response.has("error_code")) {
                appendLog(
                    getString(R.string.msg_commissioning_failed, response.optString("details"))
                )
            } else {
                val nodeId = response.optJSONObject("result")?.optLong("node_id") ?: -1L
                appendLog(getString(R.string.msg_commissioning_success, nodeId))
                refreshNodeList(matter)
            }
        } catch (error: Exception) {
            appendLog(getString(R.string.msg_commissioning_error, error.message))
        } finally {
            commissionButton.isEnabled = true
        }
    }

    private fun restoreInputs() {
        val preferences = getSharedPreferences("settings", MODE_PRIVATE)
        preferences.getString("home_assistant_url", null)?.let { homeAssistantUrlInput.setText(it) }
        preferences.getString("token", null)?.let { tokenInput.setText(it) }
        preferences.getString("pairing_code", null)?.let { pairingCodeInput.setText(it) }
    }

    private fun saveInputs() {
        getSharedPreferences("settings", MODE_PRIVATE).edit()
            .putString("home_assistant_url", homeAssistantUrlInput.text.toString().trim())
            .putString("token", tokenInput.text.toString().trim())
            .putString("pairing_code", pairingCodeInput.text.toString().trim())
            .apply()
    }

    private fun appendLog(message: String) {
        Log.i("TrueLocal", message)
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT)
            .format(java.util.Date())
        runOnUiThread {
            logView.append("$timestamp  $message\n")
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    companion object {
        private const val SOURCE_URL = "https://github.com/dymen1/ha-truelocal"
        private const val MATTER_SERVICE_UUID = "fff6"
        private const val DEVICE_EXPIRY_MILLIS = 20_000L
    }
}
