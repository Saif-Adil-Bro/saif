package com.saif.mosque.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.saif.mosque.R
import com.saif.mosque.data.outbox.DirectoryDraft
import com.saif.mosque.ui.components.PendingDirectory
import org.junit.Assert.assertEquals
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saif.mosque.ui.components.RecordCard
import com.saif.mosque.ui.components.StatItem
import com.saif.mosque.ui.components.StatsGrid
import com.saif.mosque.ui.theme.MosqueTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResponsiveComponentsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun narrowRecordAndLargeTextRemainVisible() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                MosqueTheme {
                    Column(Modifier.width(280.dp)) {
                        RecordCard(title = "দীর্ঘ নামের একটি মসজিদ তহবিল", supporting = "ঢাকা · বাংলাদেশ",
                            amount = "৳১২,৩৪৫.০০", onEdit = {}, onDelete = {})
                    }
                }
            }
        }
        compose.onNodeWithText("দীর্ঘ নামের একটি মসজিদ তহবিল").assertIsDisplayed()
        compose.onNodeWithText("৳১২,৩৪৫.০০").assertIsDisplayed()
    }

    @Test fun narrowStatsStackWithoutLosingValues() {
        compose.setContent {
            MosqueTheme {
                StatsGrid(listOf(StatItem("আয়", "৳১২৩.০০"), StatItem("ব্যয়", "৳৪৫.০০")),
                    Modifier.width(280.dp))
            }
        }
        compose.onNodeWithText("৳১২৩.০০").assertIsDisplayed()
        compose.onNodeWithText("৳৪৫.০০").assertIsDisplayed()
    }

    @Test fun failedPendingItemShowsReasonAndCanBeRetried() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var retries = 0
        val draft = DirectoryDraft("id", "u", "m", "funds", "Pending fund", "{}", 1L, "BLOCKED", "PERMISSION")
        compose.setContent {
            MosqueTheme { PendingDirectory(listOf(draft), online = true, onRetry = { retries++ }) }
        }
        compose.onNodeWithText(context.getString(R.string.pending_count, 1)).performClick()
        compose.onNodeWithText("Pending fund").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.sync_unconfirmed)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.retry)).performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }
}
