package com.namanbansal.forge.tools.util

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.namanbansal.forge.ForgeApp
import com.namanbansal.forge.core.device.ToolPrefs
import com.namanbansal.forge.core.util.CurrencyException
import com.namanbansal.forge.core.util.CurrencyInfo
import com.namanbansal.forge.core.util.CurrencyRates
import com.namanbansal.forge.core.util.CurrencyRepository
import com.namanbansal.forge.core.util.NumberText
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.ui.components.ForgeButton
import com.namanbansal.forge.ui.components.ForgeCard
import com.namanbansal.forge.ui.components.InfoNote
import com.namanbansal.forge.ui.icons.ForgeIcons
import com.namanbansal.forge.ui.screens.RoundIconButton
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType
import com.namanbansal.forge.ui.toolkit.ToolFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException

sealed interface RatesUi {
    data object Loading : RatesUi
    data class Ready(val rates: CurrencyRates, val offline: Boolean, val now: Long) : RatesUi
    data class Failed(val message: String) : RatesUi
}

/** Loads ECB rates (cache first, network when stale) and survives rotation. */
class CurrencyViewModel(app: Application, repo: CurrencyRepository? = null) : AndroidViewModel(app) {
    private val repository = repo ?: CurrencyRepository(File(app.filesDir, "currency_rates.json"), { url ->
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url).build()
            ForgeApp.from(app).http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw IOException("The rates service answered ${r.code}")
                r.body?.string() ?: throw IOException("Empty answer from the rates service")
            }
        }
    })
    private val _state = MutableStateFlow<RatesUi>(RatesUi.Loading)
    val state: StateFlow<RatesUi> = _state.asStateFlow()

    init { refresh(false) }

    fun refresh(force: Boolean) {
        _state.value = (_state.value as? RatesUi.Ready)?.let { it } ?: RatesUi.Loading
        viewModelScope.launch {
            _state.value = try {
                val r = repository.load(force)
                RatesUi.Ready(r.rates, r.offline, System.currentTimeMillis())
            } catch (e: CurrencyException) { RatesUi.Failed(e.message ?: "Could not load rates") }
            catch (e: Exception) { RatesUi.Failed("Could not load rates: ${e.message}") }
        }
    }
}

/** U2: currency converter on live ECB rates with an offline cache. */
@Composable
fun CurrencyScreen(host: ToolHost, vm: CurrencyViewModel = viewModel()) {
    val c = ForgeTheme.colors
    val prefs = com.namanbansal.forge.core.device.rememberToolPrefs()
    val ui by vm.state.collectAsStateWithLifecycle()
    var amount by rememberSaveable { mutableStateOf("100") }
    var from by rememberSaveable { mutableStateOf(prefs.string("fx.from", "USD")) }
    var to by rememberSaveable { mutableStateOf(prefs.string("fx.to", "EUR")) }
    var picking by rememberSaveable { mutableStateOf(0) }

    ToolFrame(host, actions = {
        RoundIconButton(ForgeIcons.Refresh, "Refresh rates", { vm.refresh(true) })
        androidx.compose.foundation.layout.Spacer(Modifier.padding(end = 8.dp))
    }) {
        when (val s = ui) {
            RatesUi.Loading -> Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(color = c.accent) }
            is RatesUi.Failed -> {
                InfoNote(s.message, icon = ForgeIcons.Info)
                ForgeButton("Try again", onClick = { vm.refresh(true) }, icon = ForgeIcons.Refresh, modifier = Modifier.fillMaxWidth())
            }
            is RatesUi.Ready -> {
                val rates = s.rates
                if (from !in rates.rates) from = rates.codes.first()
                if (to !in rates.rates) to = rates.codes.first()
                val value = NumberText.parse(amount)
                val out = value?.let { rates.convert(it, from, to) }
                ForgeCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ChoiceButton("From", "$from · ${CurrencyInfo.name(from)}", { picking = 1 }, Modifier.fillMaxWidth())
                        NumberField("Amount", amount, { amount = it }, error = if (amount.isNotBlank() && value == null) "Enter a number" else null)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    RoundIconButton(ForgeIcons.Swap, "Swap currencies", { val t = from; from = to; to = t; prefs.putString("fx.from", from); prefs.putString("fx.to", to) })
                }
                ForgeCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ChoiceButton("To", "$to · ${CurrencyInfo.name(to)}", { picking = 2 }, Modifier.fillMaxWidth())
                        Text(out?.let { NumberText.format(it, sig = 12).let { t -> if (kotlin.math.abs(it) >= 1) twoDecimals(it) else t } } ?: "—", style = ForgeType.Display, color = c.accent, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
                        rates.convert(1.0, from, to)?.let { Text("1 $from = ${NumberText.format(it, sig = 6)} $to", style = ForgeType.Small, color = c.text2) }
                    }
                }
                Text(CurrencyRepository.asOf(rates, s.now), style = ForgeType.Small, color = c.text2)
                if (s.offline) InfoNote("You're offline, so these are the last rates saved on this phone. Reconnect and tap refresh for today's.")
                InfoNote("Reference rates from the European Central Bank, published on working days. Banks and card networks add their own margin.")
            }
        }
    }
    val r = (ui as? RatesUi.Ready)?.rates
    if (picking != 0 && r != null) {
        PickerDialog(
            if (picking == 1) "Convert from" else "Convert to", r.codes, { it to CurrencyInfo.name(it) },
            onPick = { code -> if (picking == 1) { from = code; prefs.putString("fx.from", code) } else { to = code; prefs.putString("fx.to", code) }; picking = 0 },
            onDismiss = { picking = 0 }, selected = if (picking == 1) from else to,
        )
    }
}

private fun twoDecimals(v: Double): String = NumberText.format(Math.round(v * 100) / 100.0, sig = 15).let { s -> if (s.contains('.')) s.padEnd(s.indexOf('.') + 3, '0').take(s.indexOf('.') + 3) else "$s.00" }
