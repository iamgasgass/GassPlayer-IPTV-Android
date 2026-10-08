package com.gassplayer.android.ui.ios

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gassplayer.android.data.ParentalState
import com.gassplayer.android.ui.MainViewModel
import kotlinx.coroutines.launch

@Composable
fun IosParentalLockView(vm: MainViewModel, state: ParentalState) {
    var pin by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        IosSectionHeader("Controllo genitori", "Proteggi canali, film e serie con un PIN")
        IosGlassCard {
            IosGlassRow(Icons.Default.Lock, if (state.enabled) "Protezione attiva" else "Protezione disattivata", if (state.enabled) "Il contenuto bloccato richiederà il PIN." else "Imposta un PIN per attivare la protezione.", IosOrange, showChevron = false)
        }
        OutlinedTextField(pin, { if (it.length <= 8 && it.all(Char::isDigit)) pin = it }, label = { Text("PIN") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IosGlassPrimaryButton("Imposta PIN", Icons.Default.Lock, enabled = pin.length >= 4, modifier = Modifier.weight(1f)) { scope.launch { vm.app.parental.setPin(pin); pin = ""; message = "PIN impostato" } }
            IosGlassPrimaryButton("Disattiva", Icons.Default.LockOpen, enabled = state.enabled && pin.isNotBlank(), modifier = Modifier.weight(1f)) { scope.launch { if (vm.app.parental.disable(pin)) { pin = ""; message = "Protezione disattivata" } else message = "PIN non valido" } }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.66f)) }
    }
}
