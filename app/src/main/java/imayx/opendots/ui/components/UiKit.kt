package imayx.opendots.ui.components

import android.text.format.DateUtils
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import imayx.opendots.ui.theme.OdSpacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// ---------------------------------------------------------------------------
// Rounded header — one consistent header container for every screen.
// ---------------------------------------------------------------------------

/**
 * Rounded tonal header used by every screen: clean title, optional
 * subtitle, optional back button, and right-aligned actions.
 */
@Composable
fun OdHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(
                start = OdSpacing.s,
                end = OdSpacing.s,
                top = 10.dp,
                bottom = 10.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                }
            } else {
                Spacer(Modifier.width(OdSpacing.s))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                content = actions,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Floating rounded bottom navigation.
// ---------------------------------------------------------------------------

data class OdNavDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

/**
 * Floating rounded navigation bar with an animated pill behind the selected
 * item. The caller wraps it with navigationBarsPadding() so it respects
 * system bars and gesture navigation.
 */
@Composable
fun OdBottomNav(
    destinations: List<OdNavDestination>,
    currentRoute: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = OdSpacing.s,
                vertical = OdSpacing.s,
            ),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            destinations.forEach { dest ->
                val selected = currentRoute == dest.route
                val pillColor by animateColorAsState(
                    targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer
                    else Color.Transparent,
                    label = "navPill",
                )
                val contentColor =
                    if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(20.dp))
                        .background(pillColor)
                        .clickable { onSelect(dest.route) }
                        .padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Icon(
                        dest.icon,
                        contentDescription = dest.label,
                        tint = contentColor,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        dest.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Section card — rounded tonal container with a title row.
// ---------------------------------------------------------------------------

@Composable
fun OdSectionCard(
    title: String,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            Modifier.padding(OdSpacing.l),
            verticalArrangement = Arrangement.spacedBy(OdSpacing.s),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                action?.invoke()
            }
            content()
        }
    }
}

// ---------------------------------------------------------------------------
// Loading / empty / error states.
// ---------------------------------------------------------------------------

@Composable
fun OdLoading(
    modifier: Modifier = Modifier,
    text: String = "Loading…",
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(OdSpacing.m))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun OdEmpty(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Filled.Inbox,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.padding(OdSpacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(OdSpacing.m))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(OdSpacing.s))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(OdSpacing.l))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/**
 * Concise error card that never overflows: a short message plus an
 * expandable "Details" section for the technical text, and an optional
 * retry action.
 */
@Composable
fun OdErrorCard(
    message: String,
    modifier: Modifier = Modifier,
    details: String? = null,
    onRetry: (() -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(
            Modifier.padding(OdSpacing.l),
            verticalArrangement = Arrangement.spacedBy(OdSpacing.s),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.width(OdSpacing.s))
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(OdSpacing.s)) {
                if (details != null) {
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) "Hide details" else "Details")
                    }
                }
                if (onRetry != null) {
                    Button(onClick = onRetry) { Text("Retry") }
                }
            }
            if (expanded && details != null) {
                SelectionContainer {
                    Text(
                        details,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 160.dp)
                            .verticalScroll(rememberScrollState())
                            .background(
                                MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.08f),
                                RoundedCornerShape(12.dp),
                            )
                            .padding(OdSpacing.s),
                    )
                }
            }
        }
    }
}

/**
 * Inline expandable error text for message lists: shows a concise snippet
 * with a Show more/less toggle instead of overflowing the card.
 */
@Composable
fun ExpandableErrorText(
    text: String,
    modifier: Modifier = Modifier,
    collapsedMaxLength: Int = 140,
) {
    var expanded by remember(text) { mutableStateOf(false) }
    Column(modifier) {
        Text(
            if (expanded || text.length <= collapsedMaxLength) text
            else text.take(collapsedMaxLength).trimEnd() + "…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        if (text.length > collapsedMaxLength) {
            TextButton(
                onClick = { expanded = !expanded },
                contentPadding = PaddingValues(0.dp),
            ) {
                Text(
                    if (expanded) "Show less" else "Show more",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Defensive timestamp formatting.
// ---------------------------------------------------------------------------

private val instantTimeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
private val instantDateFmt = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())

private fun parseIsoToMillis(s: String): Long? = try {
    // Best-effort ISO-8601 without java.time (no desugaring guarantee).
    val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
    f.timeZone = TimeZone.getTimeZone("UTC")
    f.parse(s.take(19))?.time
} catch (_: Exception) {
    null
}

/**
 * Defensive timestamp rendering. Accepts epoch-millis [Long]/[Int]/[Double],
 * numeric or ISO-8601 strings, or null — never throws, never assumes a type.
 * Shows time-only for today, date + time otherwise.
 */
fun formatInstant(value: Any?): String {
    if (value == null) return ""
    val millis: Long? = when (value) {
        is Long -> value
        is Int -> value.toLong()
        is Double -> value.toLong()
        is Number -> value.toLong()
        is String -> {
            val t = value.trim()
            t.toLongOrNull() ?: parseIsoToMillis(t)
        }
        else -> null
    }
    if (millis == null || millis <= 0L) {
        return (value as? String)?.take(64) ?: ""
    }
    return try {
        val date = Date(millis)
        if (DateUtils.isToday(millis)) instantTimeFmt.format(date)
        else instantDateFmt.format(date)
    } catch (_: Exception) {
        ""
    }
}
