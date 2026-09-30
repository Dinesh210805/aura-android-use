package com.aura.aura_ui.remote

import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.aura.aura_ui.R
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.tasks.await

/**
 * Google sign-in via Credential Manager, attached to the install's existing Firebase account.
 *
 * - Contract: upgrades the current **anonymous** Firebase user with `linkWithCredential`, which
 *   keeps the same uid, so the `devices/{uid}` row ([DeviceRegistry]) survives sign-in. If that
 *   Google account already belongs to another Firebase user (a second phone, or a reinstall), it
 *   signs in as that user instead and the anonymous account is abandoned (see [strategyFor]).
 * - Needs: `R.string.default_web_client_id`, generated from `google-services.json`. Without it,
 *   [signIn] returns [SignInOutcome.Misconfigured].
 * - Callers: `OnboardingScreen` and `MonoSettingsScreen`.
 */
class AuraSignIn(private val context: Context) {

    /** What to do after a link attempt. */
    internal enum class Strategy { LINKED, SIGN_IN_INSTEAD, FAIL }

    /**
     * Maps the result of `linkWithCredential` to the next step.
     *
     * - `null` (link succeeded) → [Strategy.LINKED]
     * - [FirebaseAuthUserCollisionException] (the Google account already exists) → sign in as it
     * - anything else → [Strategy.FAIL]. Never retried as a sign-in, so an outage isn't mistaken
     *   for a new login.
     */
    internal fun strategyFor(error: Throwable?): Strategy = when (error) {
        null -> Strategy.LINKED
        is FirebaseAuthUserCollisionException -> Strategy.SIGN_IN_INSTEAD
        else -> Strategy.FAIL
    }

    /**
     * Shows the Google account picker and completes Firebase auth.
     *
     * - Contract: [activityContext] must be an Activity; Credential Manager shows system UI.
     *   Never throws: every failure is returned as a [SignInOutcome].
     */
    suspend fun signIn(activityContext: Context): SignInOutcome {
        val serverClientId = runCatching { context.getString(R.string.default_web_client_id) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return SignInOutcome.Misconfigured(
                "Google sign-in is not configured for this build. " +
                    "Enable Google in Firebase Authentication and re-download google-services.json.",
            )

        val request = GetCredentialRequest.Builder()
            // Not GetGoogleIdOption(filterByAuthorizedAccounts = true): that only lists accounts
            // that have signed in to AURA before, which is none on a first run.
            .addCredentialOption(GetSignInWithGoogleOption.Builder(serverClientId).build())
            .build()

        val idToken = try {
            val response = CredentialManager.create(activityContext).getCredential(activityContext, request)
            val credential = response.credential
            if (credential !is CustomCredential ||
                credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                return SignInOutcome.Failed("Unexpected credential type: ${credential.type}")
            }
            GoogleIdTokenCredential.createFrom(credential.data).idToken
        } catch (e: GetCredentialCancellationException) {
            return SignInOutcome.Cancelled
        } catch (e: NoCredentialException) {
            // No Google account on the device. Separate from Cancelled because the fix is adding
            // an account in Android Settings, not retrying.
            return SignInOutcome.NoGoogleAccount
        } catch (e: Exception) {
            Log.w(TAG, "credential request failed: ${e.message}", e)
            return SignInOutcome.Failed(e.message ?: "Could not reach Google sign-in.")
        }

        val credential = GoogleAuthProvider.getCredential(idToken, null)
        val auth = FirebaseAuth.getInstance()

        return try {
            val anonymous = auth.currentUser?.takeIf { it.isAnonymous }
            val linkError = if (anonymous == null) {
                // No anonymous user to upgrade (never registered, or already signed in): sign in
                // directly.
                Throwable("no anonymous session")
            } else {
                runCatching { anonymous.linkWithCredential(credential).await() }.exceptionOrNull()
            }

            val user = when {
                anonymous != null && strategyFor(linkError) == Strategy.LINKED -> auth.currentUser
                anonymous != null && strategyFor(linkError) == Strategy.FAIL -> {
                    Log.w(TAG, "link failed: ${linkError?.message}", linkError)
                    return SignInOutcome.Failed(linkError?.message ?: "Sign-in failed.")
                }
                else -> auth.signInWithCredential(credential).await().user
            } ?: return SignInOutcome.Failed("Signed in, but Firebase returned no user.")

            SignInOutcome.Success(
                uid = user.uid,
                email = user.email,
                displayName = user.displayName,
            )
        } catch (e: Exception) {
            Log.w(TAG, "firebase auth failed: ${e.message}", e)
            SignInOutcome.Failed(e.message ?: "Sign-in failed.")
        }
    }

    private companion object {
        const val TAG = "AuraSignIn"
    }
}

/**
 * The result of [AuraSignIn.signIn].
 *
 * - Why five cases: each needs a different message. `Cancelled` isn't an error,
 *   `NoGoogleAccount` is fixed in Android Settings, and `Misconfigured` (no Google provider in the
 *   Firebase project, or no `default_web_client_id`) would otherwise look like a network error.
 */
sealed interface SignInOutcome {
    data class Success(val uid: String, val email: String?, val displayName: String?) : SignInOutcome
    data object Cancelled : SignInOutcome
    data object NoGoogleAccount : SignInOutcome
    data class Misconfigured(val message: String) : SignInOutcome
    data class Failed(val message: String) : SignInOutcome
}

/** Who is signed in right now, read from Firebase Auth's persisted session. */
object AuthState {

    /** True once a Google (non-anonymous) account is attached. Onboarding requires this. */
    fun isSignedIn(): Boolean =
        FirebaseAuth.getInstance().currentUser?.let { !it.isAnonymous } ?: false

    fun email(): String? = FirebaseAuth.getInstance().currentUser?.email

    fun uid(): String? = FirebaseAuth.getInstance().currentUser?.uid

    fun signOut() = FirebaseAuth.getInstance().signOut()
}
