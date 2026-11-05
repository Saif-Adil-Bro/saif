package com.saif.mosque.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saif.mosque.R
import com.saif.mosque.data.model.Expense
import com.saif.mosque.data.model.Fund
import com.saif.mosque.ui.screens.ExpenseFormDialog
import com.saif.mosque.ui.theme.MosqueTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FinancialFormTest {
    @get:Rule val compose = createComposeRule()
    @Test fun failedSaveKeepsValuesAndBusySaveDisablesControls() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var saving by mutableStateOf(false)
        var failure by mutableStateOf<String?>(null)
        compose.setContent {
            MosqueTheme {
                ExpenseFormDialog(initial = Expense(title="Original expense", accountId="f", amount=5.0, date="2026-11-05"),
                    funds = listOf(Fund(id="f",name="Fund",code="F")), isNew=true,
                    saving=saving, saveError=failure, onDismiss={}, onSave={})
            }
        }
        compose.onNodeWithText("Original expense").assertExists()
        compose.runOnIdle { saving = true }
        compose.onNodeWithText(context.getString(R.string.saving)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.cancel)).assertIsNotEnabled()
        compose.onNodeWithText("Original expense").assertIsNotEnabled()
        compose.runOnIdle { saving = false; failure = "Request failed" }
        compose.onNodeWithText("Original expense").assertExists()
        compose.onNodeWithText("Request failed").assertExists()
        compose.onNodeWithText(context.getString(R.string.sync_check_record)).assertExists()
    }
}
