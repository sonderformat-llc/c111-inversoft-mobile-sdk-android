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

import android.app.Activity
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
import io.fusionauth.mobilesdk.AuthState
import io.fusionauth.mobilesdk.AuthenticationConfiguration
import io.fusionauth.mobilesdk.AuthenticationManager
import io.fusionauth.mobilesdk.IdToken
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.lang.ref.WeakReference
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.floor

/**
 * Displays the authorized state of the user. This activity is provided with the outcome of the
 * authorization flow, which it uses to negotiate the final authorized state,
 * by performing an authorization code exchange if necessary. After this, the activity provides
 * additional post-authorization operations if available, such as fetching user info.
 */
class TokenActivity : AppCompatActivity() {
    private val mUserInfoJson = AtomicReference<JSONObject?>()
    private var mExecutor: ExecutorService? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mExecutor = Executors.newSingleThreadExecutor()

        AuthenticationManager.initialize(
            AuthenticationConfiguration(
                clientId = "21e13847-4f30-4477-a2d9-33c3a80bd15a",
                fusionAuthUrl = "http://10.168.145.33:9011",
                allowUnsecureConnection = true
            )
        )

        setContentView(R.layout.activity_token)
        displayLoading("Restoring state...")

        if (savedInstanceState != null) {
            try {
                mUserInfoJson.set(JSONObject(savedInstanceState.getString(KEY_USER_INFO)))
            } catch (ex: JSONException) {
                Log.e(TAG, "Failed to parse saved user info JSON, discarding", ex)
            }
        }
    }

    override fun onStart() {
        super.onStart()

        if (mExecutor!!.isShutdown) {
            mExecutor = Executors.newSingleThreadExecutor()
        }

        if (AuthenticationManager.isAuthenticated()) {
            fetchUserInfoAndDisplayAuthorized(/*authState.getAccessToken()*/)
            return
        }

        lifecycleScope.launch(block = {
            displayLoading("Exchanging authorization code")
            val authState: AuthState = AuthenticationManager.oAuth(this@TokenActivity)
                .handleRedirect(intent)
            fetchUserInfoAndDisplayAuthorized()
        })
    }

    override fun onSaveInstanceState(state: Bundle) {
        super.onSaveInstanceState(state)
        // user info is retained to survive activity restarts, such as when rotating the
        // device or switching apps. This isn't essential, but it helps provide a less
        // jarring UX when these events occur - data does not just disappear from the view.
        if (mUserInfoJson.get() != null) {
            state.putString(KEY_USER_INFO, mUserInfoJson.toString())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        AuthenticationManager.dispose()
        mExecutor!!.shutdownNow()
    }

    @MainThread
    private fun displayNotAuthorized(explanation: String) {
        findViewById<View>(R.id.not_authorized).visibility = View.VISIBLE
        findViewById<View>(R.id.authorized).visibility = View.GONE
        findViewById<View>(R.id.loading_container).visibility = View.GONE

        (findViewById<View>(R.id.explanation) as TextView).text = explanation
        findViewById<View>(R.id.reauth).setOnClickListener({ view: View? -> signOut() })
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

//        val state: AuthState = mStateManager.getCurrent()

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
        findViewById<View>(R.id.sign_out).setOnClickListener { endSession() }
        findViewById<View>(R.id.change_button).setOnClickListener { makeChange() }

        var name = ""
        var email = ""

        // Retrieving name and email from the /me endpoint response
        val userInfo = mUserInfoJson.get()
        if (userInfo != null) {
            try {
                if (userInfo.has("given_name")) {
                    name = userInfo.getString("given_name")
                }
                if (userInfo.has("email")) {
                    email = userInfo.getString("email")
                }
            } catch (ex: JSONException) {
                Log.e(TAG, "Failed to read userinfo JSON", ex)
            }
        }

        // Fallback for name and email
        if ((name.isEmpty()) || (email.isEmpty())) {
            val idToken: IdToken? = AuthenticationManager.getParsedIdToken()
            if (idToken != null) {
                email = idToken.email ?: ""
                if (name.isEmpty()) {
                    name = email
                }
            }
        }

        if (!name.isEmpty()) {
            val welcomeView = findViewById<View>(R.id.auth_granted) as TextView
            val welcomeTemplate: String = resources.getString(R.string.auth_granted_name)
            welcomeView.text = String.format(welcomeTemplate, name)
        }

        (findViewById<View>(R.id.auth_granted_email) as TextView).text = email
    }

    private fun fetchUserInfoAndDisplayAuthorized() {
        lifecycleScope.launch(block = {
            try {
                val userInfo = AuthenticationManager.oAuth(this@TokenActivity).getUserInfo()
                mUserInfoJson.set(userInfo)
            } catch (ioEx: IOException) {
                Log.e(TAG, "Network error when querying userinfo endpoint", ioEx)
                showSnackbar("Fetching user info failed")
            } catch (jsonEx: JSONException) {
                Log.e(TAG, "Failed to parse userinfo response")
                showSnackbar("Failed to parse user info")
            }

            runOnUiThread { this@TokenActivity.displayAuthorized() }
        })
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == END_SESSION_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            signOut()
            finish()
        } else {
            displayEndSessionCancelled()
        }
    }

    private fun displayEndSessionCancelled() {
        Snackbar.make(
            findViewById(R.id.coordinator),
            "Sign out canceled",
            Snackbar.LENGTH_SHORT
        )
            .show()
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
        AuthenticationManager.clearState()

        lifecycleScope.launch(block = {
            AuthenticationManager
                .oAuth(this@TokenActivity)
                .logout(
                    PendingIntent.getActivity(
                        this@TokenActivity,
                        END_SESSION_REQUEST_CODE,
                        Intent(this@TokenActivity, LoginActivity::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )
        })
    }

    @MainThread
    private fun makeChange() {
        val value: String = (findViewById<View>(R.id.change_text_input) as EditText)
            .text
            .toString()
            .trim { it <= ' ' }

        if (value.length == 0) {
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

        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
        }

        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
        }

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

        private const val END_SESSION_REQUEST_CODE = 911
    }
}