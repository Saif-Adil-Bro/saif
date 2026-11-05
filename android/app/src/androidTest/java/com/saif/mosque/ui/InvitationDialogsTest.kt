package com.saif.mosque.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saif.mosque.R
import com.saif.mosque.data.invitations.InvitationAddress
import com.saif.mosque.data.invitations.InvitationCode
import com.saif.mosque.ui.invitations.PrototypeInvitationCodeDialog
import com.saif.mosque.ui.invitations.PrototypeInvitationShareDialog
import com.saif.mosque.ui.theme.MosqueTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InvitationDialogsTest {
    @get:Rule val compose = createComposeRule()
    @Test fun invalidCodeDoesNotContinueAndBusyStateRetainsInput() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val address = InvitationAddress("mosque-test", "invite-123")
        var selected: InvitationAddress? = null
        var busy by mutableStateOf(false)
        compose.setContent { MosqueTheme { PrototypeInvitationCodeDialog(busy, null, {}, { selected = it }) } }
        compose.onNodeWithText(context.getString(R.string.invitation_code)).performTextInput("bad")
        compose.onNodeWithText(context.getString(R.string.invitation_continue)).performClick()
        compose.onNodeWithText(context.getString(R.string.invitation_code_invalid)).assertIsDisplayed()
        compose.runOnIdle { assertNull(selected); busy = true }
        compose.onNodeWithText(context.getString(R.string.saving)).assertIsNotEnabled()
        compose.onNodeWithText("bad").assertIsNotEnabled()
        compose.runOnIdle { busy = false }
        compose.onNodeWithText("bad").performTextClearance()
        compose.onNodeWithText(context.getString(R.string.invitation_code)).performTextInput(InvitationCode.encode(address))
        compose.onNodeWithText(context.getString(R.string.invitation_continue)).performClick()
        compose.runOnIdle { assertEquals(address, selected) }
    }
    @Test fun shareDialogCopiesExactReference() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val address = InvitationAddress("মসজিদ", "invite")
        var copied = ""
        compose.setContent { MosqueTheme { PrototypeInvitationShareDialog(address, { copied = it }, {}) } }
        compose.onNodeWithText(context.getString(R.string.invitation_share_help)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.invitation_copy)).performClick()
        compose.runOnIdle { assertEquals(address, InvitationCode.decode(copied)) }
    }
}
