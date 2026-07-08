package com.example.whisperserver.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.whisperserver.service.TranscriptionRecord
import com.example.whisperserver.ui.components.CompactCard
import com.example.whisperserver.ui.components.RowDivider
import com.example.whisperserver.ui.components.ScreenHeader
import com.example.whisperserver.ui.theme.appColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val PAGE_SIZE = 20

enum class DateRange(val label: String, val windowMillis: Long) {
    Today("Today", 0),
    Last7("7 days", 7L * 86_400_000),
    Last30("30 days", 30L * 86_400_000),
    All("All time", Long.MAX_VALUE),
}

enum class StatusFilter(val label: String) { All("All"), Success("Success"), Failed("Failed") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JournalScreen(
    records: List<TranscriptionRecord>,
    onOpenRecord: (TranscriptionRecord) -> Unit,
    onRefresh: () -> Unit = {},
) {
    val c = appColors
    var dateRange by remember { mutableStateOf(DateRange.Last7) }
    var status by remember { mutableStateOf(StatusFilter.All) }
    var host by remember { mutableStateOf<String?>(null) } // null = all hosts
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    val hostOptions = remember(records) { records.map { it.remoteAddress }.distinct().sorted() }
    val filtered = remember(records, dateRange, status, host, query) {
        applyFilters(records, dateRange, status, host, query)
    }
    // Reset the reveal window whenever the filter set changes.
    var visibleCount by remember(dateRange, status, host, query) { mutableStateOf(PAGE_SIZE) }
    val visible = filtered.take(visibleCount)
    val hasMore = visibleCount < filtered.size
    val dayCounts = remember(filtered) { filtered.groupingBy { dayLabel(it.timestampMillis) }.eachCount() }
    val groups = remember(visible) { groupByDay(visible) }

    val listState = rememberLazyListState()
    // Infinite load: reveal another page once the last item (the loading footer)
    // scrolls into view. Reads live layout info + state so it never acts on a
    // stale snapshot of the visible window.
    LaunchedEffect(listState, filtered) {
        snapshotFlow {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) to info.totalItemsCount
        }.collect { (lastIndex, total) ->
            if (visibleCount < filtered.size && total > 0 && lastIndex >= total - 1) {
                delay(350)
                visibleCount += PAGE_SIZE
            }
        }
    }

    val scope = rememberCoroutineScope()
    var isRefreshing by remember { mutableStateOf(false) }
    // Records are held live in memory, so a pull-to-refresh has nothing to fetch;
    // it re-runs the host/model recompute, resets the reveal window, scrolls to the
    // top, and shows the spinner briefly for tactile feedback.
    val doRefresh: () -> Unit = {
        scope.launch {
            isRefreshing = true
            onRefresh()
            visibleCount = PAGE_SIZE
            runCatching { listState.scrollToItem(0) }
            delay(500)
            isRefreshing = false
        }
        Unit
    }

    Column(Modifier.fillMaxSize().background(c.screen)) {
        ScreenHeader("Journal") {
            Icon(
                Icons.Filled.Search,
                contentDescription = "Search",
                tint = c.textSecondary,
                modifier = Modifier.size(22.dp).clickable {
                    searchOpen = !searchOpen
                    if (!searchOpen) query = ""
                },
            )
        }

        if (searchOpen) {
            androidx.compose.material3.OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search text or host") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            )
        }

        // Filter chips
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            FilterChip(
                text = dateRange.label,
                leadingIcon = Icons.Filled.Event,
                active = dateRange != DateRange.Last7,
                options = DateRange.entries.map { it.label },
                onSelect = { i -> dateRange = DateRange.entries[i] },
            )
            FilterChip(
                text = status.label,
                active = status != StatusFilter.All,
                options = StatusFilter.entries.map { it.label },
                onSelect = { i -> status = StatusFilter.entries[i] },
            )
            FilterChip(
                text = host ?: "Host",
                active = host != null,
                options = listOf("All hosts") + hostOptions,
                onSelect = { i -> host = if (i == 0) null else hostOptions[i - 1] },
            )
        }

        if (filtered.isEmpty()) {
            EmptyJournal(records.isEmpty())
            return@Column
        }

        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = doRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            JournalList(listState, groups, dayCounts, hasMore, onOpenRecord)
        }
    }
}

@Composable
private fun JournalList(
    listState: androidx.compose.foundation.lazy.LazyListState,
    groups: List<DayGroup>,
    dayCounts: Map<String, Int>,
    hasMore: Boolean,
    onOpenRecord: (TranscriptionRecord) -> Unit,
) {
    val c = appColors
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(groups.size) { gi ->
            val group = groups[gi]
            Column {
                Text(
                    "${group.label} · ${dayCounts[group.label] ?: group.rows.size}",
                    color = c.textSecondary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.4.sp,
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 6.dp),
                )
                CompactCard(Modifier.fillMaxWidth(), padding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Column {
                        group.rows.forEachIndexed { i, r ->
                            if (i > 0) RowDivider()
                            JournalRow(r) { onOpenRecord(r) }
                        }
                    }
                }
            }
        }
        if (hasMore) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = c.textSecondary,
                    )
                    Text(
                        "  Loading $PAGE_SIZE more…",
                        color = c.textSecondary,
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun JournalRow(record: TranscriptionRecord, onClick: () -> Unit) {
    val c = appColors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            if (record.success) Icons.Filled.CheckCircle else Icons.Filled.Error,
            contentDescription = null,
            tint = if (record.success) c.success else c.error,
            modifier = Modifier.size(19.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                if (record.success) "\"${record.summary}\"" else record.summary,
                color = c.textPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildMeta(record),
                color = c.textSecondary,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = c.textMuted,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun FilterChip(
    text: String,
    active: Boolean,
    options: List<String>,
    onSelect: (Int) -> Unit,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    val c = appColors
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .then(
                    if (active) Modifier.background(c.runningChip)
                    else Modifier.background(c.card).border(1.dp, c.chipOutline, RoundedCornerShape(8.dp)),
                )
                .clickable { expanded = true }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            val fg = if (active) c.runningChipText else c.textSecondary
            if (leadingIcon != null) {
                Icon(leadingIcon, contentDescription = null, tint = fg, modifier = Modifier.size(15.dp))
            }
            Text(text, color = fg, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { i, opt ->
                DropdownMenuItem(text = { Text(opt) }, onClick = {
                    onSelect(i)
                    expanded = false
                })
            }
        }
    }
}

@Composable
private fun EmptyJournal(noRecordsAtAll: Boolean) {
    val c = appColors
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            if (noRecordsAtAll) "No transcriptions yet.\nRequests will appear here." else "No matches for these filters.",
            color = c.textMuted,
            fontSize = 13.sp,
            modifier = Modifier.padding(24.dp),
        )
    }
}

// ---- filtering / grouping ---------------------------------------------------

private class DayGroup(val label: String, val rows: List<TranscriptionRecord>)

private fun applyFilters(
    records: List<TranscriptionRecord>,
    range: DateRange,
    status: StatusFilter,
    host: String?,
    query: String,
): List<TranscriptionRecord> {
    val now = System.currentTimeMillis()
    val q = query.trim().lowercase()
    return records.filter { r ->
        val inRange = when (range) {
            DateRange.Today -> formatDay(r.timestampMillis) == formatDay(now)
            DateRange.All -> true
            else -> now - r.timestampMillis <= range.windowMillis
        }
        val statusOk = when (status) {
            StatusFilter.All -> true
            StatusFilter.Success -> r.success
            StatusFilter.Failed -> !r.success
        }
        val hostOk = host == null || r.remoteAddress == host
        val queryOk = q.isEmpty() ||
            r.text.lowercase().contains(q) ||
            r.remoteAddress.lowercase().contains(q) ||
            (r.errorMessage?.lowercase()?.contains(q) == true)
        inRange && statusOk && hostOk && queryOk
    }
}

private fun groupByDay(records: List<TranscriptionRecord>): List<DayGroup> {
    val out = mutableListOf<DayGroup>()
    var currentLabel: String? = null
    var bucket = mutableListOf<TranscriptionRecord>()
    for (r in records) {
        val label = dayLabel(r.timestampMillis)
        if (label != currentLabel) {
            if (bucket.isNotEmpty()) out.add(DayGroup(currentLabel!!, bucket))
            bucket = mutableListOf()
            currentLabel = label
        }
        bucket.add(r)
    }
    if (bucket.isNotEmpty() && currentLabel != null) out.add(DayGroup(currentLabel, bucket))
    return out
}

private fun buildMeta(r: TranscriptionRecord): String {
    val audio = if (r.audioDurationMillis > 0) formatDuration(r.audioDurationMillis) else "—"
    val proc = if (r.success) formatDuration(r.processingMillis) else "—"
    return "${r.remoteAddress} · $audio · $proc · ${formatClock(r.timestampMillis)}"
}
