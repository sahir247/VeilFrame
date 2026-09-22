package com.veilframe.app.qr.scanner.action

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.veilframe.app.qr.scanner.QrAction
import com.veilframe.app.qr.scanner.UrlDisposition

/**
 * Executes native Android system actions for parsed [QrAction] payloads
 * with mandatory confirmation for security-sensitive operations.
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

        if (warning != null) {
            AlertDialog.Builder(context)
                .setTitle("External Link")
                .setMessage("$warning\n\nDestination: ${action.uri}\n\nDo you want to proceed?")
                .setPositiveButton("Open") { _, _ ->
                    openBrowser(context, action.uri)
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            openBrowser(context, action.uri)
        }
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    connectWifiApi29(context, action)
                } else {
                    // Open Wi-Fi Settings with instructions
                    val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    Toast.makeText(context, "Password: ${action.password}", Toast.LENGTH_LONG).show()
                }
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

    private fun connectWifiApi29(context: Context, action: QrAction.Wifi) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

        try {
            val specifier = WifiNetworkSpecifier.Builder()
                .setSsid(action.ssid)
                .apply {
                    if (action.password.isNotBlank()) {
                        setWpa2Passphrase(action.password)
                    }
                    setIsHiddenSsid(action.hidden)
                }
                .build()

            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setNetworkSpecifier(specifier)
                .build()

            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.requestNetwork(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    cm.bindProcessToNetwork(network)
                }
            })
            Toast.makeText(context, "Requesting connection to ${action.ssid}...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    private fun executeContact(context: Context, action: QrAction.Contact) {
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

    private fun executeCalendar(context: Context, action: QrAction.CalendarEvent) {
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
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${action.number}")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try { context.startActivity(intent) } catch (_: Exception) {}
    }

    private fun executeSms(context: Context, action: QrAction.Sms) {
        val uri = Uri.parse("smsto:${action.number}")
        val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
            action.message?.let { putExtra("sms_body", it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try { context.startActivity(intent) } catch (_: Exception) {}
    }

    private fun executeEmail(context: Context, action: QrAction.Email) {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${action.address}")).apply {
            action.subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
            action.body?.let { putExtra(Intent.EXTRA_TEXT, it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try { context.startActivity(intent) } catch (_: Exception) {}
    }

    private fun executeGeo(context: Context, action: QrAction.Geo) {
        val uri = Uri.parse("geo:${action.lat},${action.lon}?q=${action.label ?: "${action.lat},${action.lon}"}")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try { context.startActivity(intent) } catch (_: Exception) {}
    }

    private fun executeRaw(context: Context, action: QrAction.Raw) {
        AlertDialog.Builder(context)
            .setTitle("QR Code Content")
            .setMessage(action.text)
            .setPositiveButton("Copy") { _, _ ->
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("QR Text", action.text))
                Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }
}
