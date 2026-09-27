package com.tunnelguard.app

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.DateFormat
import java.util.Date

class ProtectionTimelineActivity : AppCompatActivity() {
    private lateinit var repository: ProtectionTimelineRepository
    private lateinit var adapter: TimelineAdapter
    private var filter = TimelineFilter.ALL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_protection_timeline)
        repository = ProtectionTimelineRepository(this)
        adapter = TimelineAdapter(::showDetails)
        findViewById<RecyclerView>(R.id.timeline_list).apply {
            layoutManager = LinearLayoutManager(this@ProtectionTimelineActivity)
            adapter = this@ProtectionTimelineActivity.adapter
        }
        val filters = listOf(
            R.id.filter_all to TimelineFilter.ALL, R.id.filter_vpn to TimelineFilter.VPN,
            R.id.filter_protection to TimelineFilter.PROTECTION, R.id.filter_profiles to TimelineFilter.PROFILES,
            R.id.filter_overrides to TimelineFilter.OVERRIDES, R.id.filter_warnings to TimelineFilter.WARNINGS_ERRORS)
        filters.forEach { (id, value) -> findViewById<Button>(id).setOnClickListener { filter = value; refresh(); it.requestFocus() } }
        findViewById<Button>(R.id.timeline_export).setOnClickListener {
            repository.exportToFile(this)?.let { file -> Toast.makeText(this, "Timeline exported to:\n${file.absolutePath}", Toast.LENGTH_LONG).show() }
                ?: Toast.makeText(this, "Timeline export failed", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.timeline_copy).setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("TunnelGuard Protection Timeline", repository.exportJson()))
            Toast.makeText(this, "Timeline JSON copied to clipboard", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.timeline_clear).setOnClickListener {
            AlertDialog.Builder(this).setTitle("Clear Protection Timeline?")
                .setMessage("This deletes timeline history only. Protection settings and profiles are not changed.")
                .setNegativeButton("Cancel", null).setPositiveButton("Clear") { _, _ -> repository.clear(); refresh() }.show()
        }
        findViewById<Button>(R.id.timeline_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.filter_all).requestFocus()
        refresh()
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        val events = repository.getEvents(filter)
        adapter.submit(events)
        findViewById<TextView>(R.id.timeline_empty).visibility = if (events.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showDetails(event: ProtectionEvent) {
        val appLabel = event.packageName?.let { name -> runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(name, 0)).toString()
        }.getOrDefault(name) }
        val fields = linkedMapOf<String, String>()
        fields["Time"] = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(Date(event.timestamp))
        fields["Category"] = event.type.category.name.lowercase().replaceFirstChar(Char::uppercase)
        fields["Severity"] = event.severity.name.lowercase().replaceFirstChar(Char::uppercase)
        appLabel?.let { fields["App"] = if (it == event.packageName) it else "$it (${event.packageName})" }
        event.profileName?.let { fields["Profile"] = it }
        event.profileId?.takeIf { event.profileName == null }?.let { fields["Profile"] = it }
        event.vpnProvider?.let { fields["VPN provider"] = it }
        event.country?.let { fields["Country"] = it }
        event.previousState?.let { fields["Previous state"] = it }
        event.newState?.let { fields["New state"] = it }
        event.correlationId?.let { fields["Correlation"] = it }
        event.metadata.forEach { (key, value) -> fields[key.replaceFirstChar(Char::uppercase)] = value }
        val details = event.message + "\n\n" + fields.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        AlertDialog.Builder(this).setTitle(event.title).setMessage(details).setPositiveButton("Close", null).show()
    }

    private class TimelineAdapter(private val selected: (ProtectionEvent) -> Unit) : RecyclerView.Adapter<TimelineAdapter.Holder>() {
        private var events = emptyList<ProtectionEvent>()
        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val time: TextView = view.findViewById(R.id.timeline_item_time)
            val status: TextView = view.findViewById(R.id.timeline_item_status)
            val title: TextView = view.findViewById(R.id.timeline_item_title)
            val message: TextView = view.findViewById(R.id.timeline_item_message)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(LayoutInflater.from(parent.context)
            .inflate(R.layout.item_protection_event, parent, false))
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val event = events[position]
            holder.time.text = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(event.timestamp))
            holder.status.text = "${event.type.category.name} • ${event.severity.name}"
            holder.title.text = event.title
            holder.message.text = event.message
            holder.itemView.setOnClickListener { selected(event) }
        }
        override fun getItemCount() = events.size
        fun submit(value: List<ProtectionEvent>) { events = value; notifyDataSetChanged() }
    }
}
