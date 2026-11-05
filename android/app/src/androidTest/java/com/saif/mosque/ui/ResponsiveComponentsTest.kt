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
import com.saif.mosque.ui.components.PendingLedger
import com.saif.mosque.data.ledger.PrototypeLedgerDraft
import com.saif.mosque.data.ledger.PrototypeQueuedLedger
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

    @Test fun blockedFinancialDraftShowsReasonAndRetainsDetails() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var retries = 0
        val draft = PrototypeLedgerDraft(id="expense", uid="u", mosqueId="m", kind="expenses", amountPoisha=1234,
            sourceFundId="f", title="Rent", date="2026-11-05", note="Local note", createdAt=1)
        compose.setContent {
            MosqueTheme { PendingLedger(listOf(PrototypeQueuedLedger(draft, "BLOCKED", "INSUFFICIENT")),
                online=true, onRetry={ retries++ }, onDiscard={}) }
        }
        compose.onNodeWithText(context.getString(R.string.pending_count, 1)).performClick()
        compose.onNodeWithText("Rent").assertIsDisplayed()
        compose.onNodeWithText("Local note").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.insufficient_balance)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.retry)).performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test fun failedPendingItemShowsReasonAndCanBeRetried() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var retries = 0
        val draft = DirectoryDraft("id", "u", "m", "donors", "Pending donor", "{\"phone\":\"01234567890\",\"address\":\"Old road\"}", 1L, "BLOCKED", "CONFLICT", action = "update")
        compose.setContent {
            MosqueTheme { PendingDirectory(listOf(draft), online = true, onRetry = { retries++ }) }
        }
        compose.onNodeWithText(context.getString(R.string.pending_count, 1)).performClick()
        compose.onNodeWithText("Pending donor").assertIsDisplayed()
        compose.onNodeWithText("01234567890\nOld road").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.sync_conflict_help)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.sync_unconfirmed)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.retry)).performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }
    @Test fun syncDenialShowsNonSensitiveDiagnosticCode() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val draft = DirectoryDraft("id", "u", "m", "funds", "Fund", "{}", 1L, "BLOCKED", "WRITE_DENIED")
        compose.setContent { MosqueTheme { PendingDirectory(listOf(draft), online = true, onRetry = {}) } }
        compose.onNodeWithText(context.getString(R.string.pending_count, 1)).performClick()
        compose.onNodeWithText(context.getString(R.string.sync_write_denied)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.sync_diagnostic_code, "WRITE_DENIED")).assertIsDisplayed()
    }

    @Test fun waitingForLedgerMigrationExplainsAutomaticRetry() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val draft = PrototypeLedgerDraft(id="c", uid="u", mosqueId="m", kind="contributions", amountPoisha=1000,
            sourceFundId="f", donorName="Donor", date="2026-11-05", createdAt=1)
        compose.setContent { MosqueTheme { PendingLedger(listOf(PrototypeQueuedLedger(draft, "BLOCKED", "WAIT_MIGRATION")),
            online=true, onRetry={}, onDiscard={}) } }
        compose.onNodeWithText(context.getString(R.string.pending_count, 1)).performClick()
        compose.onNodeWithText(context.getString(R.string.ledger_wait_migration)).assertExists()
    }

}
