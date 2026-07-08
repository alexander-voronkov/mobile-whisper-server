package com.example.whisperserver.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/** The four top-level destinations in the redesigned bottom navigation. */
enum class NavDest(val label: String, val icon: ImageVector) {
    Dashboard("Dashboard", Icons.Filled.Dashboard),
    Journal("Journal", Icons.Filled.ReceiptLong),
    Models("Models", Icons.Filled.Download),
    Settings("Settings", Icons.Filled.Settings),
}
