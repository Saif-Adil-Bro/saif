package com.saif.mosque.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saif.mosque.R
import com.saif.mosque.data.invitations.PrototypeInvitation
import com.saif.mosque.data.model.Role
import com.saif.mosque.ui.invitations.PrototypeInviteMemberDialog
import com.saif.mosque.ui.theme.MosqueTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrototypeInviteMemberDialogTest {
    @get:Rule val compose = createComposeRule()
    @Test fun formPreservesStableDraftAndNeverAsksForMemberPassword() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var saving by mutableStateOf(false)
        var error by mutableStateOf<String?>(null)
        var first: PrototypeInvitation? = null
        var second: PrototypeInvitation? = null
        compose.setContent {
            MosqueTheme { PrototypeInviteMemberDialog("issuer", "mosque", Role.ADMIN, saving, error, {}, {
                if (first == null) first = it else second = it
            }) }
        }
        compose.onNodeWithText(context.getString(R.string.password)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.name)).performTextInput("Example Name")
        compose.onNodeWithText(context.getString(R.string.email)).performTextInput("member@example.test")
        compose.onNodeWithText(context.getString(R.string.save)).performClick()
        compose.runOnIdle { assertNotNull(first); saving = true }
        compose.onNodeWithText(context.getString(R.string.saving)).assertIsNotEnabled()
        compose.onNodeWithText("Example Name").assertIsNotEnabled()
        compose.runOnIdle { saving = false; error = "Not confirmed" }
        compose.onNodeWithText("Example Name").assertExists()
        compose.onNodeWithText("Not confirmed").assertExists()
        compose.onNodeWithText(context.getString(R.string.save)).performClick()
        compose.runOnIdle { assertEquals(first, second) }
    }
}
