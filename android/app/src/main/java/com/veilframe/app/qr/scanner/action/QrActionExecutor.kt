package com.veilframe.app.qr.scanner.action

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.veilframe.app.qr.scanner.QrAction
import com.veilframe.app.qr.scanner.UrlDisposition

/**
 * Executes native Android system actions for parsed [QrAction] payloads
 * with mandatory confirmation for all external operations and robust
 * multi-version Wi-Fi network connection without process-wide network binding.
 */
object QrActionExecutor {

    fun execute(
        context: Context,
        action: QrAction,
        onShowOtpSecret: ((QrAction.OtpAuth) -> Unit)? = null
    ) {
        when (action) {
            is QrAction.Url -> executeUrl(context, action)
            is QrAction.Wifi -> executeWifi(context, action)
            is QrAction.Contact -> executeContact(context, action)
            is QrAction.UpiPayment -> executeUpi(context, action)
            is QrAction.CalendarEvent -> executeCalendar(context, action)
            is QrAction.OtpAuth -> executeOtpAuth(context, action, onShowOtpSecret)
            is QrAction.Phone -> executePhone(context, action)
            is QrAction.Sms -> executeSms(context, action)
            is QrAction.Email -> executeEmail(context, action)
            is QrAction.Geo -> executeGeo(context, action)
            is QrAction.Raw -> executeRaw(context, action)
        }
    }

    private fun executeUrl(context: Context, action: QrAction.Url) {
        val warning = when (action.disposition) {
            UrlDisposition.PUNYCODE -> "Warning: This link uses an internationalized Punycode domain (${action.host})."
            UrlDisposition.HTTP -> "Notice: This connection is unencrypted (HTTP)."
            UrlDisposition.IP_HOST -> "Notice: This link points directly to an IP address (${action.host})."
            else -> null
        }

        val message = buildString {
            if (warning != null) {
                append(warning).append("\n\n")
            }
            action.host?.let { append("Host: ").append(it).append("\n") }
            append("Destination: ").append(action.uri).append("\n\n")
            append("Do you want to open this link in your browser?")
        }

        AlertDialog.Builder(context)
            .setTitle(if (warning != null) "Security Warning: External Link" else "Open External Link")
            .setMessage(message)
            .setPositiveButton("Open") { _, _ ->
                openBrowser(context, action.uri)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openBrowser(context: Context, uriString: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uriString)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "No browser found to open link", Toast.LENGTH_SHORT).show()
        }
    }

    private fun executeWifi(context: Context, action: QrAction.Wifi) {
        AlertDialog.Builder(context)
            .setTitle("Connect to Wi-Fi")
            .setMessage("Network: ${action.ssid}\nSecurity: ${action.type}\n\nConnect to this network?")
            .setPositiveButton("Connect") { _, _ ->
                connectWifi(context, action)
            }
            .setNeutralButton("View Password") { _, _ ->
                AlertDialog.Builder(context)
                    .setTitle("Wi-Fi Credentials")
                    .setMessage("SSID: ${action.ssid}\nPassword: ${action.password}")
                    .setPositiveButton("Close", null)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun connectWifi(context: Context, action: QrAction.Wifi) {
        val isOpen = action.type.equals("nopass", ignoreCase = true) ||
                action.type.equals("OPEN", ignoreCase = true) ||
                action.password.isBlank()
        val isWpa3 = action.type.equals("WPA3", ignoreCase = true) ||
                action.type.equals("SAE", ignoreCase = true)

        // 1. Android 11+ (API 30+): Official system network addition dialog
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val suggestionBuilder = WifiNetworkSuggestion.Builder()
                    .setSsid(action.ssid)
                    .setIsHiddenSsid(action.hidden)

                if (isWpa3) {
                    if (action.password.isNotBlank()) suggestionBuilder.setWpa3Passphrase(action.password)
                } else if (!isOpen) {
                    if (action.password.isNotBlank()) suggestionBuilder.setWpa2Passphrase(action.password)
                }

                val suggestion = suggestionBuilder.build()
                val bundle = Bundle().apply {
                    putParcelableArrayList(Settings.EXTRA_WIFI_NETWORK_LIST, arrayListOf(suggestion))
                }
                val intent = Intent(Settings.ACTION_WIFI_ADD_NETWORKS).apply {
                    putExtras(bundle)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return
            } catch (_: Exception) {
                // Fallback to NetworkSuggestion / Specifier below
            }
        }

        // 2. Android 10 (API 29): Add network suggestions or specifier
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val suggestionBuilder = WifiNetworkSuggestion.Builder()
                    .setSsid(action.ssid)
                    .setIsHiddenSsid(action.hidden)

                if (isWpa3) {
                    if (action.password.isNotBlank()) suggestionBuilder.setWpa3Passphrase(action.password)
                } else if (!isOpen) {
                    if (action.password.isNotBlank()) suggestionBuilder.setWpa2Passphrase(action.password)
                }

                val status = wifiManager.addNetworkSuggestions(listOf(suggestionBuilder.build()))
                if (status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
                    Toast.makeText(context, "Network added. Connecting to ${action.ssid}...", Toast.LENGTH_SHORT).show()
                    return
                }
            } catch (_: Exception) {}

            // Specifier path on Android Q (without bindProcessToNetwork!)
            connectViaNetworkSpecifierApi29(context, action, isOpen)
            return
        }

        // 3. Android 9 and below (API <= 28): Direct WifiConfiguration
        connectWifiLegacy(context, action, isOpen)
    }

    private fun connectViaNetworkSpecifierApi29(context: Context, action: QrAction.Wifi, isOpen: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val specifierBuilder = WifiNetworkSpecifier.Builder()
                .setSsid(action.ssid)
                .apply {
                    if (!isOpen && action.password.isNotBlank()) {
                        setWpa2Passphrase(action.password)
                    }
                    setIsHiddenSsid(action.hidden)
                }

            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setNetworkSpecifier(specifierBuilder.build())
                .build()

            val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.requestNetwork(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    // Connected successfully!
                    // Do NOT call cm.bindProcessToNetwork(network) - preserves application-wide networking.
                }
            })
            Toast.makeText(context, "Requesting connection to ${action.ssid}...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            openWifiSettingsFallback(context, action)
        }
    }

    @Suppress("DEPRECATION")
    private fun connectWifiLegacy(context: Context, action: QrAction.Wifi, isOpen: Boolean) {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiConfig = android.net.wifi.WifiConfiguration().apply {
                SSID = "\"${action.ssid}\""
                if (isOpen) {
                    allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.NONE)
                } else if (action.type.equals("WEP", ignoreCase = true)) {
                    val isHex = action.password.matches(Regex("^[0-9a-fA-F]+$")) &&
                            (action.password.length == 10 || action.password.length == 26 || action.password.length == 32)
                    wepKeys[0] = if (isHex) action.password else "\"${action.password}\""
                    wepTxKeyIndex = 0
                    allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.NONE)
                    allowedAuthAlgorithms.set(android.net.wifi.WifiConfiguration.AuthAlgorithm.SHARED)
                } else {
                    preSharedKey = "\"${action.password}\""
                }
                hiddenSSID = action.hidden
            }

            val netId = wifiManager.addNetwork(wifiConfig)
            if (netId != -1) {
                wifiManager.disconnect()
                wifiManager.enableNetwork(netId, true)
                wifiManager.reconnect()
                Toast.makeText(context, "Connecting to ${action.ssid}...", Toast.LENGTH_SHORT).show()
            } else {
                openWifiSettingsFallback(context, action)
            }
        } catch (e: Exception) {
            openWifiSettingsFallback(context, action)
        }
    }

    private fun openWifiSettingsFallback(context: Context, action: QrAction.Wifi) {
        if (action.password.isNotBlank()) {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Wi-Fi Password", action.password))
            Toast.makeText(context, "Password copied to clipboard. Select ${action.ssid} in settings.", Toast.LENGTH_LONG).show()
        }
        val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {}
    }

    private fun executeContact(context: Context, action: QrAction.Contact) {
        val details = buildString {
            action.name?.let { append("Name: $it\n") }
            if (action.phones.isNotEmpty()) append("Phone: ${action.phones.joinToString(", ")}\n")
            if (action.emails.isNotEmpty()) append("Email: ${action.emails.joinToString(", ")}\n")
            action.org?.let { append("Organization: $it\n") }
            action.title?.let { append("Title: $it\n") }
            append("\nAdd this contact to your address book?")
        }
        AlertDialog.Builder(context)
            .setTitle("Add Contact")
            .setMessage(details)
            .setPositiveButton("Add") { _, _ ->
                val intent = Intent(Intent.ACTION_INSERT).apply {
                    type = ContactsContract.Contacts.CONTENT_TYPE
                    action.name?.let { putExtra(ContactsContract.Intents.Insert.NAME, it) }
                    action.phones.firstOrNull()?.let { putExtra(ContactsContract.Intents.Insert.PHONE, it) }
                    action.emails.firstOrNull()?.let { putExtra(ContactsContract.Intents.Insert.EMAIL, it) }
                    action.org?.let { putExtra(ContactsContract.Intents.Insert.COMPANY, it) }
                    action.title?.let { putExtra(ContactsContract.Intents.Insert.JOB_TITLE, it) }
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(context, "No contacts app found", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun executeCalendar(context: Context, action: QrAction.CalendarEvent) {
        val details = buildString {
            action.title?.let { append("Event: $it\n") }
            action.dtStart?.let { append("Start: $it\n") }
            action.dtEnd?.let { append("End: $it\n") }
            action.location?.let { append("Location: $it\n") }
            action.description?.let { append("Description: $it\n") }
            append("\nAdd this event to your calendar?")
        }
        AlertDialog.Builder(context)
            .setTitle("Add Calendar Event")
            .setMessage(details)
            .setPositiveButton("Add") { _, _ ->
                val intent = Intent(Intent.ACTION_INSERT).apply {
                    data = CalendarContract.Events.CONTENT_URI
                    action.title?.let { putExtra(CalendarContract.Events.TITLE, it) }
                    action.description?.let { putExtra(CalendarContract.Events.DESCRIPTION, it) }
                    action.location?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(context, "No calendar app found", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun executeUpi(context: Context, action: QrAction.UpiPayment) {
        val amt = action.amount?.let { "Amount: ${action.currency ?: "INR"} $it\n" } ?: ""
        val note = action.note?.let { "Note: $it\n" } ?: ""

        AlertDialog.Builder(context)
            .setTitle("Confirm UPI Payment")
            .setMessage("Payee: ${action.payeeName ?: action.payeeAddress}\nVPA: ${action.payeeAddress}\n$amt$note\nProceed to payment app?")
            .setPositiveButton("Pay") { _, _ ->
                val uriBuilder = Uri.Builder().scheme("upi").authority("pay")
                for ((k, v) in action.rawParameters) {
                    uriBuilder.appendQueryParameter(k, v)
                }
                val intent = Intent(Intent.ACTION_VIEW, uriBuilder.build()).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(context, "No UPI payment app found", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun executeOtpAuth(
        context: Context,
        action: QrAction.OtpAuth,
        onShowSecret: ((QrAction.OtpAuth) -> Unit)?
    ) {
        AlertDialog.Builder(context)
            .setTitle("Authenticator Account")
            .setMessage("Account: ${action.account ?: "Unknown"}\nIssuer: ${action.issuer ?: "Unknown"}\nAlgorithm: ${action.algorithm} (${action.digits} digits)\n\nNever share 2FA secret keys.")
            .setPositiveButton("Add to Authenticator") { _, _ ->
                val uri = Uri.Builder().scheme("otpauth").authority(action.type)
                    .path("/${action.account ?: ""}")
                    .apply {
                        action.secret?.let { appendQueryParameter("secret", it) }
                        action.issuer?.let { appendQueryParameter("issuer", it) }
                        appendQueryParameter("algorithm", action.algorithm)
                        appendQueryParameter("digits", action.digits.toString())
                        appendQueryParameter("period", action.period.toString())
                    }
                    .build()
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(context, "No authenticator app installed", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("View Key") { _, _ ->
                onShowSecret?.invoke(action) ?: run {
                    AlertDialog.Builder(context)
                        .setTitle("Secret Key")
                        .setMessage("Key: ${action.secret ?: "None"}")
                        .setPositiveButton("Close", null)
                        .show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun executePhone(context: Context, action: QrAction.Phone) {
        AlertDialog.Builder(context)
            .setTitle("Confirm Phone Call")
            .setMessage("Do you want to open the dialer for:\n\n${action.number}")
            .setPositiveButton("Dial") { _, _ ->
                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${action.number}")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try { context.startActivity(intent) } catch (_: Exception) {
                    Toast.makeText(context, "No dialer app found", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun executeSms(context: Context, action: QrAction.Sms) {
        val msgPreview = action.message?.let { "\nMessage: $it" } ?: ""
        AlertDialog.Builder(context)
            .setTitle("Send SMS")
            .setMessage("Recipient: ${action.number}$msgPreview\n\nOpen messaging app?")
            .setPositiveButton("Open") { _, _ ->
                val uri = Uri.parse("smsto:${action.number}")
                val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
                    action.message?.let { putExtra("sms_body", it) }
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try { context.startActivity(intent) } catch (_: Exception) {
                    Toast.makeText(context, "No messaging app found", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun executeEmail(context: Context, action: QrAction.Email) {
        val details = buildString {
            append("To: ").append(action.address).append("\n")
            action.subject?.let { append("Subject: ").append(it).append("\n") }
            action.body?.let { append("Body: ").append(it).append("\n") }
            append("\nOpen email client?")
        }
        AlertDialog.Builder(context)
            .setTitle("Send Email")
            .setMessage(details)
            .setPositiveButton("Open") { _, _ ->
                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${action.address}")).apply {
                    action.subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
                    action.body?.let { putExtra(Intent.EXTRA_TEXT, it) }
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try { context.startActivity(intent) } catch (_: Exception) {
                    Toast.makeText(context, "No email client found", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun executeGeo(context: Context, action: QrAction.Geo) {
        val details = buildString {
            append("Coordinates: ${action.lat}, ${action.lon}\n")
            action.label?.let { append("Location: $it\n") }
            append("\nOpen in map application?")
        }
        AlertDialog.Builder(context)
            .setTitle("View Location")
            .setMessage(details)
            .setPositiveButton("Open Map") { _, _ ->
                val uri = Uri.parse("geo:${action.lat},${action.lon}?q=${action.label ?: "${action.lat},${action.lon}"}")
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try { context.startActivity(intent) } catch (_: Exception) {
                    Toast.makeText(context, "No map application found", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun executeRaw(context: Context, action: QrAction.Raw) {
        AlertDialog.Builder(context)
            .setTitle("QR Code Content")
            .setMessage(action.text)
            .setPositiveButton("Copy") { _, _ ->
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("QR Text", action.text))
                Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }
}
