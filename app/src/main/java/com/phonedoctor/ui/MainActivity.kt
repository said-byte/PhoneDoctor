package com.phonedoctor.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.phonedoctor.core.AppItem
import com.phonedoctor.core.Shell
import com.phonedoctor.core.Tri
import com.phonedoctor.fix.ColorOsAccessibilityService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PhoneDoctorTheme {
                val vm: MainViewModel = viewModel()
                Root(vm)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Root(vm: MainViewModel) {
    var tab by remember { mutableIntStateOf(0) }
    val context = LocalContext.current

    // Первый запуск: просим доступ Shizuku (диалог), включаем свою службу
    // доступности и сканируем — ничего вручную нажимать не нужно.
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(1500)
        if (!Shell.isGranted()) {
            vm.requestShizuku()
            // ждём подтверждение диалога до 30 с
            for (i in 0 until 30) {
                kotlinx.coroutines.delay(1000)
                if (Shell.isGranted()) break
            }
        }
        if (ColorOsAccessibilityService.isRunning()) {
            vm.refresh()
        } else {
            vm.prepareSelf()
            if (Shell.isGranted()) {
                // службе нужно несколько секунд, чтобы забиндиться после включения
                kotlinx.coroutines.delay(3000)
                if (ColorOsAccessibilityService.isRunning()) vm.refresh()
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Phone Doctor", fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "уведомления, которые доходят",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.HealthAndSafety, null) },
                    label = { Text("Телефон") },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Default.Apps, null) },
                    label = { Text("Приложения") },
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Default.Settings, null) },
                    label = { Text("Ещё") },
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                0 -> DeviceScreen(vm)
                1 -> AppsScreen(vm)
                else -> MoreScreen(vm)
            }
        }

        if (vm.working != null) {
            Box(Modifier.fillMaxSize().background(Color(0x33000000))) {
                Surface(
                    modifier = Modifier.align(Alignment.Center),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Row(
                        Modifier.padding(24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(16.dp))
                        Text("Работаю: ${vm.working}")
                    }
                }
            }
        }
    }
}

// ---------------- экран «Телефон» ----------------

@Composable
private fun DeviceScreen(vm: MainViewModel) {
    val context = LocalContext.current

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "Что мешает уведомлениям",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        item { StatusRow("Shizuku (права shell)", vm.triShizuku()) }

        item {
            StatusRow(
                "Служба спец. возможностей",
                if (ColorOsAccessibilityService.isRunning()) Tri.YES else Tri.NO,
            )
        }

        item {
            StatusRow(
                "Google-сервисы (FCM)",
                when {
                    vm.device == null -> Tri.UNKNOWN
                    !vm.device!!.gmsEnabled -> Tri.NO
                    else -> Tri.YES
                },
            )
        }

        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }

        item {
            Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Быстрая настройка", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Один раз разрешает Android включать службу доступности у APK не из магазина " +
                            "и запрещает ColorOS выключать её сама.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { vm.prepareSelf() }, enabled = vm.shizukuReady()) {
                        Text("Подготовить приложение")
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.refresh() }, enabled = !vm.scanning && vm.shizukuReady()) {
                    Text(if (vm.scanning) "Сканирую…" else "Обновить")
                }
                OutlinedButton(onClick = { vm.requestShizuku() }) {
                    Text("Доступ Shizuku")
                }
            }
        }

        item {
            Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Как включить службу", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Настройки → Специальные возможности → Phone Doctor → включить. " +
                            "Если система пишет про ограничения — сначала нажми «Подготовить приложение».",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }) {
                        Text("Открыть спец. возможности")
                    }
                }
            }
        }

        item {
            Text(
                "Устройство: ${vm.device?.romName ?: "—"} · Android ${vm.device?.androidVersion ?: "—"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusRow(title: String, state: Tri) {
    Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface)) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, modifier = Modifier.weight(1f))
            StatusChip(state)
        }
    }
}

// ---------------- экран «Приложения» ----------------

@Composable
private fun AppsScreen(vm: MainViewModel) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(16.dp)) {
            OutlinedTextField(
                value = vm.query,
                onValueChange = { vm.query = it },
                label = { Text("Поиск") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.fixAll() }, enabled = vm.shizukuReady() && !vm.scanning) {
                    Text("Починить все")
                }
                OutlinedButton(onClick = { vm.refresh() }, enabled = !vm.scanning && vm.shizukuReady()) {
                    Text("Обновить")
                }
            }
        }

        if (vm.visibleApps.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (!vm.shizukuReady()) "Нужен Shizuku: без него сканер не работает"
                    else "Список пуст — нажми «Обновить»",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(vm.visibleApps, key = { it.pkg }) { item ->
                    AppRow(item, vm)
                }
            }
        }
    }
}

@Composable
private fun AppRow(item: AppItem, vm: MainViewModel) {
    Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(item.label, fontWeight = FontWeight.Medium)
            Text(
                item.pkg,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusChip(item.notifications, "увед.")
                StatusChip(item.battery, "батарея")
                StatusChip(item.background, "фон")
                StatusChip(item.autostart, "автозап.")
            }
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                OutlinedButton(
                    onClick = { vm.fixApp(item) },
                    enabled = vm.shizukuReady(),
                ) {
                    Text("Починить")
                }
                OutlinedButton(
                    onClick = { vm.fixBackground(item) },
                    enabled = ColorOsAccessibilityService.isRunning() && vm.shizukuReady(),
                ) {
                    Text("Фон")
                }
                OutlinedButton(
                    onClick = { vm.fixAutostart(item) },
                    enabled = ColorOsAccessibilityService.isRunning() && vm.shizukuReady(),
                ) {
                    Text("Автозапуск")
                }
            }
        }
    }
}

// ---------------- экран «Ещё» ----------------

@Composable
private fun MoreScreen(vm: MainViewModel) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Снять дамп экрана", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Служба сохранит дерево текущего экрана в Android/data/com.phonedoctor/files/ui. " +
                            "По нему настраиваются селекторы для автозапуска.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { ColorOsAccessibilityService.dumpNow() },
                        enabled = ColorOsAccessibilityService.isRunning(),
                    ) {
                        Text("Сохранить дамп")
                    }
                }
            }
        }

        item {
            Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Журнал", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    if (vm.log.isEmpty()) {
                        Text(
                            "Пока пусто",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        vm.log.takeLast(10).reversed().forEach { line ->
                            Text(
                                "• $line",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                }
            }
        }
    }
}

// ---------------- мелочи ----------------

@Composable
private fun StatusChip(state: Tri, label: String? = null) {
    val (bg, fg, mark) = when (state) {
        Tri.YES -> Triple(Color(0xFFE1EFE3), Color(0xFF2F6B45), "вкл")
        Tri.NO -> Triple(Color(0xFFF8E2DC), Color(0xFFA33A26), "нет")
        Tri.UNKNOWN -> Triple(Color(0xFFEDE8DF), Color(0xFF6F665C), "?")
    }
    val text = if (label != null) "$label: $mark" else mark
    Box(
        Modifier
            .background(bg, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = fg)
    }
}
