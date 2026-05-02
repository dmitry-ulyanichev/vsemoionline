package com.v2ray.ang.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.RadioButton
import androidx.activity.result.contract.ActivityResultContracts
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityPaymentBinding
import com.v2ray.ang.extension.toast
import com.v2ray.ang.util.Utils
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class PaymentActivity : BaseActivity() {
    private val binding by lazy { ActivityPaymentBinding.inflate(layoutInflater) }

    private class ApiException(
        val apiCode: String,
        message: String,
    ) : IllegalStateException(message)

    private data class Plan(
        val code: String,
        val label: String,
        val priceLabel: String,
        val currency: String,
        val billingMode: String,
        val available: Boolean,
        val availableMethods: List<String>,
    )

    private data class PaymentMethod(
        val code: String,
        val label: String,
        val description: String,
    )

    private enum class StatusMode {
        INFO,
        ERROR,
        SUCCESS
    }

    companion object {
        private const val PREFS_NAME = "vsemoionline_prefs"
        private const val PREF_BACKEND_BASE_URL = "backend_base_url"
        private const val PREF_FAMILY_ROLE = "family_role"
        private const val PRIMARY_PROVISION_URL = "https://vmonl.store/provision"
    }

    private var plans: List<Plan> = emptyList()
    private var methods: List<PaymentMethod> = emptyList()
    private var selectedPlanCode: String = ""
    private var selectedMethodCode: String = ""
    private var currentPaymentId: String? = null
    private var statusJob: Job? = null
    private val restoreSubscriptionActivity = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) {
            setResult(Activity.RESULT_OK)
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        configureVseMoiSystemBars()

        title = getString(R.string.vsm_toolbar_title)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        binding.toolbar.navigationContentDescription = getString(R.string.title_pref_back)

        binding.btnPaymentCreate.setOnClickListener { createPayment() }
        binding.btnPaymentOpenCheckout.setOnClickListener { openCheckoutAgain() }
        binding.btnPaymentRestore.setOnClickListener { openRestoreSubscription() }
        binding.btnPaymentDone.setOnClickListener {
            setResult(Activity.RESULT_OK)
            finish()
        }
        binding.tvPaymentMemberWarning.visibility = if (isRegularFamilyMember()) View.VISIBLE else View.GONE

        loadPaymentConfig()
    }

    override fun onDestroy() {
        statusJob?.cancel()
        super.onDestroy()
    }

    private fun backendBaseUrl(): String {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(PREF_BACKEND_BASE_URL, null)
            ?: PRIMARY_PROVISION_URL.removeSuffix("/provision").trimEnd('/')
    }

    private fun isRegularFamilyMember(): Boolean {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(PREF_FAMILY_ROLE, null) == "member"
    }

    private fun getOrCreateDeviceId(): String {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        var deviceId = prefs.getString("device_id", null)
        if (deviceId == null) {
            deviceId = java.util.UUID.randomUUID().toString()
            prefs.edit().putString("device_id", deviceId).apply()
        }
        return deviceId
    }

    private fun getAndroidId(): String? {
        return try {
            android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID)
        } catch (e: Exception) {
            null
        }
    }

    private fun setBusy(isBusy: Boolean) {
        binding.btnPaymentCreate.isEnabled = !isBusy && selectedPlanCode.isNotBlank() && selectedMethodCode.isNotBlank()
        binding.etPaymentEmail.isEnabled = !isBusy
        binding.rgPaymentPlans.isEnabled = !isBusy
        binding.rgPaymentMethods.isEnabled = !isBusy
        binding.btnPaymentCreate.alpha = if (isBusy) 0.7f else 1f
    }

    private fun showStatus(message: String, mode: StatusMode = StatusMode.INFO) {
        binding.tvPaymentStatus.visibility = View.VISIBLE
        binding.tvPaymentStatus.text = message
        when (mode) {
            StatusMode.INFO -> {
                binding.tvPaymentStatus.setBackgroundResource(R.drawable.restore_status_info_bg)
                binding.tvPaymentStatus.setTextColor(getColor(R.color.vsm_link))
            }
            StatusMode.ERROR -> {
                binding.tvPaymentStatus.setBackgroundResource(R.drawable.restore_status_error_bg)
                binding.tvPaymentStatus.setTextColor(getColor(R.color.vsm_sub_urgent))
            }
            StatusMode.SUCCESS -> {
                binding.tvPaymentStatus.setBackgroundResource(R.drawable.restore_status_success_bg)
                binding.tvPaymentStatus.setTextColor(getColor(R.color.vsm_sub_ok))
            }
        }
    }

    private fun updateHeader(titleRes: Int, introRes: Int) {
        title = getString(R.string.vsm_toolbar_title)
        binding.tvPaymentTitle.setText(titleRes)
        binding.tvPaymentIntro.setText(introRes)
    }

    private fun loadPaymentConfig() {
        setBusy(true)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val json = getJson("${backendBaseUrl()}/api/public/payment-config?currency=RUB")
                val selection = json.optJSONObject("selection")
                val planItems = mutableListOf<Plan>()
                val methodItems = mutableListOf<PaymentMethod>()

                val plansJson = json.optJSONArray("plans")
                if (plansJson != null) {
                    for (index in 0 until plansJson.length()) {
                        val item = plansJson.optJSONObject(index) ?: continue
                        planItems += Plan(
                            code = item.optString("code"),
                            label = item.optString("label", item.optString("code")),
                            priceLabel = item.optString("price_label")
                                .ifBlank { formatAmount(item.optString("amount"), item.optString("currency", "RUB")) },
                            currency = item.optString("currency", selection?.optString("currency", "RUB") ?: "RUB"),
                            billingMode = item.optString("billing_mode", selection?.optString("billing_mode", "one_time") ?: "one_time"),
                            available = item.optBoolean("available", true),
                            availableMethods = readStringArray(item.optJSONArray("available_methods")),
                        )
                    }
                }

                val methodsJson = json.optJSONArray("methods")
                if (methodsJson != null) {
                    for (index in 0 until methodsJson.length()) {
                        val item = methodsJson.optJSONObject(index) ?: continue
                        methodItems += PaymentMethod(
                            code = item.optString("code"),
                            label = item.optString("label", item.optString("code")),
                            description = item.optString("description"),
                        )
                    }
                }

                withContext(Dispatchers.Main) {
                    plans = planItems.filter { it.code.isNotBlank() && it.available }
                    methods = methodItems.filter { it.code.isNotBlank() }
                    selectedPlanCode = plans.firstOrNull { it.code == "3m" }?.code
                        ?: plans.firstOrNull()?.code.orEmpty()
                    selectedMethodCode = selection?.optString("payment_method")
                        ?.takeIf { value -> methods.any { it.code == value } }
                        ?: methods.firstOrNull()?.code.orEmpty()
                    renderOptions()
                    setBusy(false)
                    if (plans.isEmpty() || methods.isEmpty()) {
                        showStatus(getString(R.string.vsm_payment_unavailable), StatusMode.ERROR)
                    }
                }
            } catch (e: Exception) {
                Log.w(AppConfig.TAG, "VseMoiOnline: payment config failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    setBusy(false)
                    showStatus(e.message ?: getString(R.string.vsm_payment_generic_error), StatusMode.ERROR)
                }
            }
        }
    }

    private fun renderOptions() {
        binding.rgPaymentPlans.setOnCheckedChangeListener(null)
        binding.rgPaymentPlans.removeAllViews()
        plans.forEach { plan ->
            binding.rgPaymentPlans.addView(buildRadioButton(
                id = View.generateViewId(),
                text = "${plan.label}  •  ${plan.priceLabel}",
                checked = plan.code == selectedPlanCode,
                tag = plan.code,
            ))
        }
        binding.rgPaymentPlans.setOnCheckedChangeListener { group, checkedId ->
            selectedPlanCode = group.findViewById<RadioButton>(checkedId)?.tag as? String ?: selectedPlanCode
            val plan = plans.firstOrNull { it.code == selectedPlanCode }
            if (plan != null && plan.availableMethods.isNotEmpty() && selectedMethodCode !in plan.availableMethods) {
                selectedMethodCode = methods.firstOrNull { it.code in plan.availableMethods }?.code.orEmpty()
                renderOptions()
            }
            setBusy(false)
        }

        binding.rgPaymentMethods.setOnCheckedChangeListener(null)
        binding.rgPaymentMethods.removeAllViews()
        methods.forEach { method ->
            val subtitle = method.description.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
            binding.rgPaymentMethods.addView(buildRadioButton(
                id = View.generateViewId(),
                text = "${method.label}$subtitle",
                checked = method.code == selectedMethodCode,
                tag = method.code,
            ))
        }
        binding.rgPaymentMethods.setOnCheckedChangeListener { group, checkedId ->
            selectedMethodCode = group.findViewById<RadioButton>(checkedId)?.tag as? String ?: selectedMethodCode
            setBusy(false)
        }
    }

    private fun buildRadioButton(id: Int, text: String, checked: Boolean, tag: String): RadioButton {
        return RadioButton(this).apply {
            this.id = id
            this.text = text
            this.tag = tag
            this.isChecked = checked
            textSize = 14f
            setTextColor(getColor(R.color.vsm_restore_card_title))
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
    }

    private fun createPayment() {
        val email = binding.etPaymentEmail.text?.toString()?.trim()?.lowercase().orEmpty()
        if (email.isBlank()) {
            toast(R.string.vsm_restore_email_required)
            return
        }
        val plan = plans.firstOrNull { it.code == selectedPlanCode }
        if (plan == null || selectedMethodCode.isBlank()) {
            showStatus(getString(R.string.vsm_payment_unavailable), StatusMode.ERROR)
            return
        }
        if (plan.availableMethods.isNotEmpty() && selectedMethodCode !in plan.availableMethods) {
            showStatus(getString(R.string.vsm_payment_unavailable), StatusMode.ERROR)
            return
        }

        setBusy(true)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val body = JSONObject()
                    .put("plan_code", plan.code)
                    .put("currency", plan.currency)
                    .put("billing_mode", plan.billingMode)
                    .put("payment_method", selectedMethodCode)
                    .put("email", email)
                    .put("device_fingerprint", getOrCreateDeviceId())
                    .put("android_id", getAndroidId())
                val payment = postJson("${backendBaseUrl()}/payment/create", body)
                currentPaymentId = payment.optString("payment_id").takeIf { it.isNotBlank() }
                val redirectUrl = payment.optString("redirect_url").takeIf { it.isNotBlank() }

                withContext(Dispatchers.Main) {
                    setBusy(false)
                    showProcessingState(payment.optString("payment_id"), redirectUrl)
                    if (redirectUrl != null) {
                        Utils.openUri(this@PaymentActivity, redirectUrl)
                    }
                    startStatusPolling()
                }
            } catch (e: Exception) {
                Log.w(AppConfig.TAG, "VseMoiOnline: payment create failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    setBusy(false)
                    if (e is ApiException && e.apiCode == "email_belongs_to_existing_account") {
                        showExistingEmailRestorePrompt(e.message ?: getString(R.string.vsm_payment_restore_existing_email))
                    } else {
                        binding.btnPaymentRestore.visibility = View.GONE
                        showStatus(e.message ?: getString(R.string.vsm_payment_generic_error), StatusMode.ERROR)
                    }
                }
            }
        }
    }

    private fun showExistingEmailRestorePrompt(message: String) {
        updateHeader(R.string.vsm_restore_title, R.string.vsm_restore_intro)
        binding.layoutPaymentProcessing.visibility = View.GONE
        binding.layoutPaymentCard.visibility = View.VISIBLE
        showStatus(message, StatusMode.INFO)
        binding.btnPaymentRestore.visibility = View.VISIBLE
        setOpenCheckoutVisibility(false)
        binding.btnPaymentDone.visibility = View.GONE
    }

    private fun showProcessingState(paymentId: String, redirectUrl: String?) {
        updateHeader(R.string.vsm_payment_processing_title, R.string.vsm_payment_processing_intro)
        binding.layoutPaymentCard.visibility = View.GONE
        binding.layoutPaymentProcessing.visibility = View.VISIBLE
        binding.tvPaymentStatus.visibility = View.GONE
        binding.btnPaymentRestore.visibility = View.GONE
        setOpenCheckoutVisibility(redirectUrl != null)
        binding.btnPaymentOpenCheckout.tag = redirectUrl
        binding.btnPaymentDone.visibility = View.GONE
        binding.pbPaymentPolling.visibility = View.VISIBLE
        binding.tvPaymentProcessingTitle.text = getString(R.string.vsm_payment_processing_card_title)
        binding.tvPaymentProcessingBody.text = getString(R.string.vsm_payment_processing_body)
        binding.tvPaymentId.visibility = if (paymentId.isNotBlank()) View.VISIBLE else View.GONE
        binding.tvPaymentId.text = if (paymentId.isNotBlank()) getString(R.string.vsm_payment_id_label, paymentId) else ""
    }

    private fun showConfirmedState() {
        updateHeader(R.string.vsm_payment_success_title, R.string.vsm_payment_success_intro)
        binding.layoutPaymentCard.visibility = View.GONE
        binding.layoutPaymentProcessing.visibility = View.VISIBLE
        binding.tvPaymentStatus.visibility = View.GONE
        binding.pbPaymentPolling.visibility = View.GONE
        setOpenCheckoutVisibility(false)
        binding.btnPaymentDone.visibility = View.VISIBLE
        binding.tvPaymentProcessingTitle.text = getString(R.string.vsm_payment_success_card_title)
        binding.tvPaymentProcessingBody.text = getString(R.string.vsm_payment_success_card_body)
        binding.tvPaymentId.visibility = View.GONE
    }

    private fun showFailedState() {
        updateHeader(R.string.vsm_payment_failed_title, R.string.vsm_payment_failed_body)
        binding.layoutPaymentCard.visibility = View.GONE
        binding.layoutPaymentProcessing.visibility = View.VISIBLE
        binding.pbPaymentPolling.visibility = View.GONE
        setOpenCheckoutVisibility(false)
        binding.btnPaymentDone.visibility = View.GONE
        binding.tvPaymentProcessingTitle.text = getString(R.string.vsm_payment_failed_title)
        binding.tvPaymentProcessingBody.text = getString(R.string.vsm_payment_failed_body)
        showStatus(getString(R.string.vsm_payment_failed), StatusMode.ERROR)
    }

    private fun openRestoreSubscription() {
        val email = binding.etPaymentEmail.text?.toString()?.trim()?.lowercase().orEmpty()
        val intent = Intent(this, RestoreSubscriptionActivity::class.java).apply {
            if (email.isNotBlank()) {
                putExtra(RestoreSubscriptionActivity.EXTRA_PREFILL_EMAIL, email)
            }
        }
        restoreSubscriptionActivity.launch(intent)
    }

    private fun setOpenCheckoutVisibility(isVisible: Boolean) {
        val visibility = if (isVisible) View.VISIBLE else View.GONE
        binding.tvPaymentOpenCheckoutHint.visibility = visibility
        binding.btnPaymentOpenCheckout.visibility = visibility
    }

    private fun openCheckoutAgain() {
        val url = binding.btnPaymentOpenCheckout.tag as? String ?: return
        Utils.openUri(this, url)
    }

    private fun startStatusPolling() {
        val paymentId = currentPaymentId ?: return
        statusJob?.cancel()
        statusJob = lifecycleScope.launch(Dispatchers.IO) {
            repeat(60) {
                delay(4_000)
                try {
                    val payment = getJson("${backendBaseUrl()}/payment/$paymentId")
                    val status = payment.optString("status")
                    withContext(Dispatchers.Main) {
                        when (status) {
                            "confirmed" -> {
                                statusJob?.cancel()
                                showConfirmedState()
                            }
                            "failed" -> {
                                statusJob?.cancel()
                                showFailedState()
                            }
                            else -> Unit
                        }
                    }
                    if (status == "confirmed" || status == "failed") return@launch
                } catch (e: Exception) {
                    Log.w(AppConfig.TAG, "VseMoiOnline: payment status poll failed: ${e.message}")
                }
            }
        }
    }

    private fun getJson(urlString: String): JSONObject {
        val connection = java.net.URL(urlString).openConnection() as java.net.HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("Accept", "application/json")
        return readJsonResponse(connection)
    }

    private fun postJson(urlString: String, body: JSONObject): JSONObject {
        val connection = java.net.URL(urlString).openConnection() as java.net.HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "application/json")
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        return readJsonResponse(connection)
    }

    private fun readJsonResponse(connection: java.net.HttpURLConnection): JSONObject {
        val responseCode = connection.responseCode
        val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.readText().orEmpty()
        connection.disconnect()

        if (responseCode !in 200..299) {
            val errorJson = runCatching { JSONObject(text) }.getOrNull()
            val message = errorJson?.optString("error")
            val code = errorJson?.optString("code")
            throw ApiException(
                apiCode = code?.takeIf { it.isNotBlank() } ?: "request_failed",
                message = message?.takeIf { it.isNotBlank() } ?: getString(R.string.vsm_payment_generic_error),
            )
        }

        return JSONObject(text)
    }

    private fun formatAmount(amount: String, currency: String): String {
        if (amount.isBlank()) return ""
        val suffix = when (currency.uppercase()) {
            "RUB" -> "₽"
            "USD" -> "$"
            "EUR" -> "€"
            else -> currency.uppercase()
        }
        return "$amount $suffix"
    }

    private fun readStringArray(array: org.json.JSONArray?): List<String> {
        if (array == null) return emptyList()
        val values = mutableListOf<String>()
        for (index in 0 until array.length()) {
            val value = array.optString(index)
            if (value.isNotBlank()) values += value
        }
        return values
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
