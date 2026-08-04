package com.tunnelguard.app.update

import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.tunnelguard.app.R
import io.noties.markwon.Markwon
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun UpdateDialogScreen(state: UpdateUiState, onUpdateNow: () -> Unit, onExitApp: () -> Unit) {
    val focusRequester = androidx.compose.runtime.remember { FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { focusRequester.requestFocus() }
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(painterResource(R.drawable.ic_launcher), contentDescription = "TunnelGuard app icon", modifier = Modifier.size(96.dp))
                Spacer(Modifier.height(16.dp))
                Text("🚀 New Version Available", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(state.releaseName, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    VersionBadge("Current Version", state.currentVersion, Modifier.weight(1f))
                    VersionBadge("Latest Version", state.latestVersion, Modifier.weight(1f))
                }
                if (state.publishedAt.isNotBlank()) {
                    val formattedDate = try {
                        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                        val date = isoFormat.parse(state.publishedAt)
                        val outputFormat = SimpleDateFormat.getDateInstance(SimpleDateFormat.MEDIUM, Locale.getDefault())
                        date?.let { outputFormat.format(it) } ?: state.publishedAt
                    } catch (e: Exception) {
                        state.publishedAt
                    }
                    Text("Release Date: $formattedDate", modifier = Modifier.padding(top = 12.dp))
                }
                Spacer(Modifier.height(16.dp))
                ElevatedCard(Modifier.weight(1f).fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
                    Column(Modifier.fillMaxSize().padding(16.dp)) {
                        Text("Release Notes", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        MarkdownReleaseNotes(state.releaseNotes, Modifier.fillMaxSize().verticalScroll(rememberScrollState()))
                    }
                }
                AnimatedVisibility(state.isDownloading) {
                    Column(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth())
                        Text("Downloading… ${state.progress}%", modifier = Modifier.padding(top = 8.dp))
                    }
                }
                state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp)) }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onUpdateNow, enabled = !state.isDownloading, modifier = Modifier.weight(1f).height(56.dp).focusRequester(focusRequester)) { Text("Update Now") }
                    OutlinedButton(onClick = onExitApp, enabled = !state.isDownloading, modifier = Modifier.weight(1f).height(56.dp)) { Text("Exit App") }
                }
            }
        }
    }
}

@Composable
private fun VersionBadge(label: String, value: String, modifier: Modifier = Modifier) {
    ElevatedCard(modifier) { Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text("v$value", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    } }
}

@Composable
private fun MarkdownReleaseNotes(markdown: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val markwon = remember(context) { Markwon.create(context) }
    AndroidView(
        modifier = modifier,
        factory = {
            TextView(it).apply {
                setTextColor(android.graphics.Color.WHITE)
                textSize = 16f
                linksClickable = true
                movementMethod = LinkMovementMethod.getInstance()
                setLineSpacing(0f, 1.15f)
            }
        },
        update = { markwon.setMarkdown(it, markdown) }
    )
}
