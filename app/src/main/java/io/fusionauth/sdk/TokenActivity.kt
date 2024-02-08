/*
 * Copyright 2015 The AppAuth for Android Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.fusionauth.sdk

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.annotation.MainThread
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import io.fusionauth.mobilesdk.AuthenticationConfiguration
import io.fusionauth.mobilesdk.AuthenticationManager
import io.fusionauth.mobilesdk.FusionAuthState
import io.fusionauth.mobilesdk.IdToken
import io.fusionauth.mobilesdk.UserInfo
import io.fusionauth.mobilesdk.exceptions.AuthenticationException
import io.fusionauth.mobilesdk.storage.SharedPreferencesStorage
import kotlinx.coroutines.launch
import org.json.JSONException
import java.io.IOException
import java.lang.ref.WeakReference
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Logger
import kotlin.math.floor

/**
 * Displays the authorized state of the user. This activity is provided with the outcome of the
 * authorization flow, which it uses to negotiate the final authorized state,
 * by performing an authorization code exchange if necessary. After this, the activity provides
 * additional post-authorization operations if available, such as fetching user info.
 */
@Suppress("TooManyFunctions")
class TokenActivity : AppCompatActivity() {
    private val mUserInfo = AtomicReference<UserInfo?>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AuthenticationManager.initStorage(SharedPreferencesStorage(this))
        AuthenticationManager.initialize(
            AuthenticationConfiguration(
                clientId = "21e13847-4f30-4477-a2d9-33c3a80bd15a",
                fusionAuthUrl = "http://10.168.145.33:9011",
                allowUnsecureConnection = true
            ),
            SharedPreferencesStorage(this)
        )

        setContentView(R.layout.activity_token)
        displayLoading("Restoring state...")

        if (savedInstanceState != null) {
            try {
//                mUserInfoJson.set(JSONObject(savedInstanceState.getString(KEY_USER_INFO)))
            } catch (ex: JSONException) {
                Log.e(TAG, "Failed to parse saved user info JSON, discarding", ex)
            }
        }
    }

    override fun onStart() {
        super.onStart()

        Logger.getLogger(TAG).info("Checking for authorization response")
        if (AuthenticationManager.isAuthenticated()) {
            fetchUserInfoAndDisplayAuthorized(/*authState.getAccessToken()*/)
            return
        }

        lifecycleScope.launch {
            displayLoading("Exchanging authorization code")
            try {
                val authState: FusionAuthState = AuthenticationManager.oAuth(this@TokenActivity)
                    .handleRedirect(intent)
                Log.i(TAG, authState.toString())
                fetchUserInfoAndDisplayAuthorized()
            } catch (ex: AuthenticationException) {
                Log.e(TAG, "Failed to exchange authorization code", ex)
                displayNotAuthorized("Authorization failed")
            }
        }
    }

    override fun onSaveInstanceState(state: Bundle) {
        super.onSaveInstanceState(state)
        // user info is retained to survive activity restarts, such as when rotating the
        // device or switching apps. This isn't essential, but it helps provide a less
        // jarring UX when these events occur - data does not just disappear from the view.
        if (mUserInfo.get() != null) {
            state.putString(KEY_USER_INFO, mUserInfo.toString())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        AuthenticationManager.dispose()
    }

    @MainThread
    private fun displayNotAuthorized(explanation: String) {
        findViewById<View>(R.id.not_authorized).visibility = View.VISIBLE
        findViewById<View>(R.id.authorized).visibility = View.GONE
        findViewById<View>(R.id.loading_container).visibility = View.GONE

        (findViewById<View>(R.id.explanation) as TextView).text = explanation
        findViewById<View>(R.id.reauth).setOnClickListener { signOut() }
    }

    @MainThread
    private fun displayLoading(message: String) {
        findViewById<View>(R.id.loading_container).visibility = View.VISIBLE
        findViewById<View>(R.id.authorized).visibility = View.GONE
        findViewById<View>(R.id.not_authorized).visibility = View.GONE

        (findViewById<View>(R.id.loading_description) as TextView).text = message
    }

    @MainThread
    private fun displayAuthorized() {
        findViewById<View>(R.id.authorized).visibility = View.VISIBLE
        findViewById<View>(R.id.not_authorized).visibility = View.GONE
        findViewById<View>(R.id.loading_container).visibility = View.GONE

        val noAccessTokenReturnedView = findViewById<View>(R.id.no_access_token_returned) as TextView
        if (AuthenticationManager.getAccessToken() == null) {
            noAccessTokenReturnedView.visibility = View.VISIBLE
        } else {
            // Logging out if token is expired
            if (AuthenticationManager.isAccessTokenExpired()) {
                signOut()
                return
            }
        }

        val changeTextInput: EditText = findViewById(R.id.change_text_input)
        changeTextInput.addTextChangedListener(MoneyChangedHandler(changeTextInput))
        findViewById<View>(R.id.sign_out).setOnClickListener {
            endSession()
        }
        findViewById<View>(R.id.change_button).setOnClickListener { makeChange() }

        var name = ""
        var email = ""

        // Retrieving name and email from the /me endpoint response
        val userInfo = mUserInfo.get()
        if (userInfo != null) {
            try {
                name = userInfo.given_name.orEmpty()
                email = userInfo.email.orEmpty()
            } catch (ex: JSONException) {
                Log.e(TAG, "Failed to read userinfo JSON", ex)
            }
        }

        // Fallback for name and email
        if ((name.isEmpty()) || (email.isEmpty())) {
            val idToken: IdToken? = AuthenticationManager.getParsedIdToken()
            if (idToken != null) {
                email = idToken.email.orEmpty()
                if (name.isEmpty()) {
                    name = email
                }
            }
        }

        if (name.isNotEmpty()) {
            val welcomeView = findViewById<View>(R.id.auth_granted) as TextView
            val welcomeTemplate: String = resources.getString(R.string.auth_granted_name)
            welcomeView.text = String.format(welcomeTemplate, name)
        }

        (findViewById<View>(R.id.auth_granted_email) as TextView).text = email
    }

    private fun fetchUserInfoAndDisplayAuthorized() {
        lifecycleScope.launch {
            try {
                val userInfo = AuthenticationManager.oAuth(this@TokenActivity).getUserInfo()
                mUserInfo.set(userInfo)
            } catch (ioEx: IOException) {
                Log.e(TAG, "Network error when querying userinfo endpoint", ioEx)
                showSnackbar("Fetching user info failed")
            } catch (jsonEx: JSONException) {
                Log.e(TAG, "Failed to parse userinfo response", jsonEx)
                showSnackbar("Failed to parse user info")
            }

            runOnUiThread { this@TokenActivity.displayAuthorized() }
        }
    }

    @MainThread
    private fun showSnackbar(message: String) {
        Snackbar.make(
            findViewById(R.id.coordinator),
            message,
            Snackbar.LENGTH_SHORT
        )
            .show()
    }

    @MainThread
    private fun endSession() {
        lifecycleScope.launch {
            AuthenticationManager
                .oAuth(this@TokenActivity)
                .logout(
                    PendingIntent.getActivity(
                        this@TokenActivity,
                        0,
                        Intent(this@TokenActivity, LoginActivity::class.java)
                            .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
                        PendingIntent.FLAG_MUTABLE
                    )
                )
        }
    }

    @Suppress("MagicNumber")
    @MainThread
    private fun makeChange() {
        val value: String = (findViewById<View>(R.id.change_text_input) as EditText)
            .text
            .toString()
            .trim { it <= ' ' }

        if (value.isEmpty()) {
            return
        }

        val floatValue = value.toFloat()
        if (floatValue < 0) {
            return
        }
        val cents = floatValue * 100
        val nickels = floor((cents / 5).toDouble()).toInt()
        val pennies = (cents % 5).toInt()
        val textView: TextView = findViewById(R.id.change_result_text_view)
        val changeTemplate: String = resources.getString(R.string.change_result_text_view)
        textView.text = String.format(
            changeTemplate,
            NumberFormat.getCurrencyInstance().format(floatValue.toDouble()),
            nickels,
            pennies
        )
        textView.visibility = View.VISIBLE
    }

    @MainThread
    private fun signOut() {
        AuthenticationManager.clearState()

        val mainIntent = Intent(this, LoginActivity::class.java)
        mainIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(mainIntent)
        finish()
    }

    /**
     * @see [StackOverflow answer](https://stackoverflow.com/a/24621325)
     */
    private class MoneyChangedHandler(editText: EditText) : TextWatcher {
        private val editTextWeakReference: WeakReference<EditText> = WeakReference<EditText>(editText)

        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) = Unit

        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) = Unit

        override fun afterTextChanged(editable: Editable) {
            val editText: EditText = editTextWeakReference.get() ?: return
            val s: String = editable.toString()
            if (s.isEmpty()) {
                return
            }

            editText.removeTextChangedListener(this)

            val cleanString = s.replace("[,.]".toRegex(), "")
            val parsed = BigDecimal(cleanString)
                .setScale(2, RoundingMode.FLOOR)
                .divide(BigDecimal(100), RoundingMode.FLOOR)
                .toString()
            editText.setText(parsed)
            editText.setSelection(parsed.length)

            editText.addTextChangedListener(this)
        }
    }

    companion object {
        private const val TAG = "TokenActivity"

        private const val KEY_USER_INFO = "userInfo"
    }
}
