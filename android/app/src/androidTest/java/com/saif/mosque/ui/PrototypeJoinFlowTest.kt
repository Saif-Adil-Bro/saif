package com.saif.mosque.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.saif.mosque.R
import com.saif.mosque.data.invitations.*
import com.saif.mosque.ui.invitations.PrototypeJoinFlow
import com.saif.mosque.ui.theme.MosqueTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrototypeJoinFlowTest {
    @get:Rule val compose = createComposeRule()
    private class Store : PrototypeJoinReferenceStore {
        var value: String? = null
        override suspend fun read() = value
        override suspend fun write(code: String?) { value = code }
    }
    private class Gateway : PrototypeJoinGateway {
        var user: PrototypeJoinIdentity? = null
        var verified = false
        override fun identity() = user
        override suspend fun signUp(email: String, password: String): PrototypeSignupResult {
            user = PrototypeJoinIdentity("uid", email)
            return PrototypeSignupResult.VERIFICATION_REQUESTED
        }
        override suspend fun signIn(email: String, password: String) { user = PrototypeJoinIdentity("uid", email) }
        override suspend fun resendVerification() = PrototypeVerificationResult.REQUESTED
        override suspend fun redeem(address: InvitationAddress) { if (!verified) throw InvitationNeedsVerification() }
        override fun signOut() { user = null }
    }
    @Test fun codeAccountVerificationAndConfirmedCompletionAreConnected() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = Store(); val gateway = Gateway(); val coordinator = PrototypeJoinCoordinator(gateway, store)
        val code = InvitationCode.encode(InvitationAddress("mosque", "invitation"))
        compose.setContent { MosqueTheme { PrototypeJoinFlow(coordinator, {}) } }
        compose.onNodeWithText(context.getString(R.string.invitation_code)).performTextInput(code)
        compose.onNodeWithText(context.getString(R.string.invitation_continue)).performClick()
        compose.waitUntil(5_000) { coordinator.state.value.phase == PrototypeJoinPhase.ACCOUNT }
        compose.onNodeWithText(context.getString(R.string.email)).performTextInput("member@example.test")
        compose.onNodeWithText(context.getString(R.string.password)).performTextInput("password123")
        compose.onNodeWithText(context.getString(R.string.join_create_account)).performScrollTo().performClick()
        compose.waitUntil(5_000) { coordinator.state.value.phase == PrototypeJoinPhase.VERIFY }
        compose.onNodeWithText(context.getString(R.string.invitation_continue)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.join_verify_email)).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { gateway.verified = true }
        compose.onNodeWithText(context.getString(R.string.invitation_continue)).performScrollTo().performClick()
        compose.waitUntil(5_000) { coordinator.state.value.phase == PrototypeJoinPhase.COMPLETE }
        compose.onNodeWithText(context.getString(R.string.join_complete)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(code, store.value) }
    }
}
