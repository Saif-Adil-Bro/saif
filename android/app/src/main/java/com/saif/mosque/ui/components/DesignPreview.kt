package com.saif.mosque.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.saif.mosque.ui.theme.MosqueTheme

/** Preview the shared cards with narrow widths and larger accessibility text. */
@Preview(name = "Compact", widthDp = 320, heightDp = 720, showBackground = true)
@Preview(name = "Large text", widthDp = 320, heightDp = 720, fontScale = 1.5f, showBackground = true)
@Preview(name = "Tablet", widthDp = 720, heightDp = 720, showBackground = true)
@Composable
private fun LedgerComponentsPreview() {
    MosqueTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionTitle("সারসংক্ষেপ")
                StatsGrid(listOf(StatItem("মাসের আয়", "৳১২,৪৫০.০০"), StatItem("মাসের ব্যয়", "৳৪,৮০০.০০")))
                SectionTitle("সাম্প্রতিক লেনদেন")
                RecordCard(title = "মসজিদ উন্নয়ন তহবিল", supporting = "সাধারণ · ০৪/১১/২০২৬",
                    amount = "৳১২,৪৫০.০০", onEdit = {}, onDelete = {})
            }
        }
    }
}
