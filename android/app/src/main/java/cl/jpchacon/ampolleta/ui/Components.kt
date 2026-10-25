@file:OptIn(ExperimentalMaterial3Api::class)

package cl.jpchacon.ampolleta.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// Piezas comunes a las pantallas de ampolletas y aires.

@Composable
internal fun BackBar(
    title: String,
    onBack: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    actions: @Composable () -> Unit = {},
) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver") }
        },
        actions = { actions() },
        scrollBehavior = scrollBehavior,
    )
}

/** Círculo con el ícono del equipo: del color de la luz (o del modo del aire) cuando está encendido. */
@Composable
internal fun IconBubble(icon: ImageVector, color: Color?, size: Int = 48) {
    val bg by animateColorAsState(color ?: MaterialTheme.colorScheme.surfaceVariant, label = "burbuja")
    Box(
        Modifier.size(size.dp).background(bg, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (color != null) Color.Black.copy(alpha = 0.72f) else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size((size * 0.5).dp),
        )
    }
}

/** Tarjeta de un equipo en el inicio: toca para abrirlo; el interruptor lo prende y apaga ahí mismo. */
@Composable
internal fun DeviceTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    on: Boolean,
    available: Boolean,
    problem: Boolean,
    onOpen: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val lit = on && available
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val container by animateColorAsState(if (lit) lerp(base, accent, 0.22f) else base, label = "tarjeta")
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBubble(icon, if (lit) accent else null)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = on, enabled = available, onCheckedChange = onToggle)
        }
    }
}

@Composable
internal fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp),
    )
}

/** Grupo de controles con título, sobre una tarjeta suave. */
@Composable
internal fun SectionCard(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            title?.let { Text(it, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
            content()
        }
    }
}

@Composable
internal fun ToggleRow(label: String, checked: Boolean, supporting: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Switch) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            supporting?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
internal fun LoadingRow(text: String) {
    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Aviso destacado: de error, de espera (con rueda) o informativo. */
@Composable
internal fun Banner(text: String, kind: BannerKind = BannerKind.INFO) {
    val colors = MaterialTheme.colorScheme
    val (bg, fg) = when (kind) {
        BannerKind.ERROR -> colors.errorContainer to colors.onErrorContainer
        BannerKind.WAIT, BannerKind.INFO -> colors.secondaryContainer to colors.onSecondaryContainer
    }
    Surface(color = bg, contentColor = fg, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            when (kind) {
                BannerKind.WAIT -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = fg)
                BannerKind.ERROR -> Icon(Icons.Default.Warning, contentDescription = null)
                BannerKind.INFO -> Icon(Icons.Default.Info, contentDescription = null)
            }
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

internal enum class BannerKind { INFO, WAIT, ERROR }
