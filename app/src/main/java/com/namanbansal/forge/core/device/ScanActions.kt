package com.namanbansal.forge.core.device

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiNetworkSuggestion
import android.provider.ContactsContract
import android.provider.Settings

/** Intents for acting on a scanned code. Builders are pure (testable); [launch] never throws. */
object ScanActions {
    fun view(uri: String): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))

    fun dial(number: String): Intent = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))

    fun email(e: ScanContent.Email): Intent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", e.address, null)).apply {
        if (e.subject.isNotEmpty()) putExtra(Intent.EXTRA_SUBJECT, e.subject)
        if (e.body.isNotEmpty()) putExtra(Intent.EXTRA_TEXT, e.body)
    }

    fun sms(s: ScanContent.Sms): Intent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", s.number, null)).apply {
        if (s.body.isNotEmpty()) putExtra("sms_body", s.body)
    }

    fun map(g: ScanContent.Geo): Intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:${g.lat},${g.lon}?q=${g.lat},${g.lon}"))

    fun addContact(c: ScanContent.Contact): Intent = Intent(ContactsContract.Intents.Insert.ACTION).apply {
        type = ContactsContract.Contacts.CONTENT_TYPE
        putExtra(ContactsContract.Intents.Insert.NAME, c.name)
        c.phones.getOrNull(0)?.let { putExtra(ContactsContract.Intents.Insert.PHONE, it) }
        c.phones.getOrNull(1)?.let { putExtra(ContactsContract.Intents.Insert.SECONDARY_PHONE, it) }
        c.emails.getOrNull(0)?.let { putExtra(ContactsContract.Intents.Insert.EMAIL, it) }
        c.emails.getOrNull(1)?.let { putExtra(ContactsContract.Intents.Insert.SECONDARY_EMAIL, it) }
        if (c.org.isNotEmpty()) putExtra(ContactsContract.Intents.Insert.COMPANY, c.org)
        if (c.title.isNotEmpty()) putExtra(ContactsContract.Intents.Insert.JOB_TITLE, c.title)
        if (c.address.isNotEmpty()) putExtra(ContactsContract.Intents.Insert.POSTAL, c.address)
    }

    fun webSearch(query: String): Intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query)))

    fun share(text: String): Intent = Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }, "Share")

    /** True if the network can be offered to Android as a suggestion (WEP cannot). */
    fun canSuggest(w: ScanContent.Wifi): Boolean = !w.security.equals("WEP", ignoreCase = true)

    /** System sheet "Save this network?" built from a [WifiNetworkSuggestion] (Android 11+), so the user stays in control. */
    fun wifiSuggestion(w: ScanContent.Wifi): Intent {
        val b = WifiNetworkSuggestion.Builder().setSsid(w.ssid).setIsHiddenSsid(w.hidden)
        val sec = w.security.uppercase()
        if (w.password.isNotEmpty()) {
            if (sec == "SAE" || sec == "WPA3") b.setWpa3Passphrase(w.password) else b.setWpa2Passphrase(w.password)
        }
        return Intent(Settings.ACTION_WIFI_ADD_NETWORKS).putParcelableArrayListExtra(Settings.EXTRA_WIFI_NETWORK_LIST, arrayListOf(b.build()))
    }

    fun launch(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (_: ActivityNotFoundException) { false } catch (_: SecurityException) { false }

    fun copy(context: Context, label: String, text: String) {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText(label, text))
    }
}
