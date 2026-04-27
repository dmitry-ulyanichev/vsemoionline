package com.v2ray.ang.ui

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.View
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityRestoreSubscriptionBinding
import com.v2ray.ang.extension.toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class RestoreSubscriptionActivity : BaseActivity() {
    private val binding by lazy { ActivityRestoreSubscriptionBinding.inflate(layoutInflater) }

    private enum class StatusMode {
        INFO,
        ERROR,
        SUCCESS
    }

    companion object {
        private const val PREFS_NAME = "vsemoionline_prefs"
        private const val PREF_BACKEND_BASE_URL = "backend_base_url"
        private const val PRIMARY_PROVISION_URL = "https://vmonl.store/provision"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        title = getString(R.string.vsm_toolbar_title)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        binding.toolbar.navigationContentDescription = getString(R.string.title_pref_back)

        binding.btnRestoreSendCode.setOnClickListener { sendCode() }
        binding.btnRestoreVerify.setOnClickListener { verifyCode() }
        binding.btnRestoreResendCode.setOnClickListener { sendCode(showVerificationState = false) }
        binding.btnRestoreDone.setOnClickListener {
            setResult(Activity.RESULT_OK)
            finish()
        }
    }

    private fun backendBaseUrl(): String {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(PREF_BACKEND_BASE_URL, null)
            ?: PRIMARY_PROVISION_URL.removeSuffix("/provision").trimEnd('/')
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

    private fun setBusy(isBusy: Boolean) {
        binding.btnRestoreSendCode.isEnabled = !isBusy
        binding.btnRestoreVerify.isEnabled = !isBusy
        binding.btnRestoreResendCode.isEnabled = !isBusy
        binding.btnRestoreDone.isEnabled = !isBusy
        binding.etRestoreEmail.isEnabled = !isBusy
        binding.etRestoreCode.isEnabled = !isBusy
        binding.btnRestoreSendCode.alpha = if (isBusy) 0.7f else 1f
        binding.btnRestoreVerify.alpha = if (isBusy) 0.7f else 1f
        binding.btnRestoreResendCode.alpha = if (isBusy) 0.7f else 1f
        binding.btnRestoreDone.alpha = if (isBusy) 0.7f else 1f
    }

    private fun showStatus(message: String, mode: StatusMode = StatusMode.INFO) {
        binding.tvRestoreStatus.visibility = View.VISIBLE
        binding.tvRestoreStatus.text = message
        when (mode) {
            StatusMode.INFO -> {
                binding.tvRestoreStatus.setBackgroundResource(R.drawable.restore_status_info_bg)
                binding.tvRestoreStatus.setTextColor(getColor(R.color.vsm_link))
            }
            StatusMode.ERROR -> {
                binding.tvRestoreStatus.setBackgroundResource(R.drawable.restore_status_error_bg)
                binding.tvRestoreStatus.setTextColor(getColor(R.color.vsm_sub_urgent))
            }
            StatusMode.SUCCESS -> {
                binding.tvRestoreStatus.setBackgroundResource(R.drawable.restore_status_success_bg)
                binding.tvRestoreStatus.setTextColor(getColor(R.color.vsm_sub_ok))
            }
        }
    }

    private fun sendCode(showVerificationState: Boolean = true) {
        val email = binding.etRestoreEmail.text?.toString()?.trim()?.lowercase().orEmpty()
        if (email.isBlank()) {
            toast(R.string.vsm_restore_email_required)
            return
        }

        setBusy(true)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val payload = JSONObject().put("email", email)
                val response = postJson("${backendBaseUrl()}/restore/send-code", payload)
                val message = response.optString("message", getString(R.string.vsm_restore_code_sent))
                withContext(Dispatchers.Main) {
                    setBusy(false)
                    if (showVerificationState) {
                        showVerificationState()
                    }
                    showStatus(message, StatusMode.INFO)
                    binding.etRestoreCode.requestFocus()
                }
            } catch (e: Exception) {
                Log.w(AppConfig.TAG, "VseMoiOnline: restore send-code failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    setBusy(false)
                    showStatus(e.message ?: getString(R.string.vsm_restore_generic_error), StatusMode.ERROR)
                }
            }
        }
    }

    private fun showVerificationState() {
        binding.tvRestoreIntro.text = getString(R.string.vsm_restore_intro_step2)
        binding.layoutRestoreStep1.visibility = View.GONE
        binding.layoutRestoreVerify.visibility = View.VISIBLE
    }

    private fun verifyCode() {
        val email = binding.etRestoreEmail.text?.toString()?.trim()?.lowercase().orEmpty()
        val code = binding.etRestoreCode.text?.toString()?.trim().orEmpty()
        if (email.isBlank()) {
            toast(R.string.vsm_restore_email_required)
            return
        }
        if (code.length != 4) {
            toast(R.string.vsm_restore_code_required)
            return
        }

        setBusy(true)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val payload = JSONObject()
                    .put("email", email)
                    .put("code", code)
                    .put("device_fingerprint", getOrCreateDeviceId())
                val response = postJson("${backendBaseUrl()}/restore/verify", payload)
                val message = response.optString("message", getString(R.string.vsm_restore_success))
                withContext(Dispatchers.Main) {
                    setBusy(false)
                    showStatus(message, StatusMode.SUCCESS)
                    showSuccessState()
                }
            } catch (e: Exception) {
                Log.w(AppConfig.TAG, "VseMoiOnline: restore verify failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    setBusy(false)
                    showStatus(e.message ?: getString(R.string.vsm_restore_generic_error), StatusMode.ERROR)
                }
            }
        }
    }

    private fun showSuccessState() {
        binding.tvRestoreTitle.text = getString(R.string.vsm_restore_success)
        binding.tvRestoreIntro.text = getString(R.string.vsm_restore_success_intro)
        binding.layoutRestoreStep1.visibility = View.VISIBLE
        binding.tvRestoreStep1.text = getString(R.string.vsm_restore_success_step)
        binding.tvRestoreStep1Body.text = getString(R.string.vsm_restore_success_body)
        binding.tvRestoreEmailLabel.visibility = View.GONE
        binding.etRestoreEmail.visibility = View.GONE
        binding.btnRestoreSendCode.visibility = View.GONE
        binding.layoutRestoreVerify.visibility = View.GONE
        binding.tvRestoreFooter.visibility = View.GONE
        binding.btnRestoreDone.visibility = View.VISIBLE
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

        val responseCode = connection.responseCode
        val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.readText().orEmpty()
        connection.disconnect()

        if (responseCode !in 200..299) {
            val message = runCatching { JSONObject(text).optString("error") }.getOrNull()
            throw IllegalStateException(message?.takeIf { it.isNotBlank() } ?: getString(R.string.vsm_restore_generic_error))
        }

        return JSONObject(text)
    }
}
