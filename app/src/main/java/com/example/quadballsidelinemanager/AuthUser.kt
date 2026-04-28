package com.example.quadballsidelinemanager

import android.content.Intent
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResultRegistry
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.firebase.ui.auth.AuthUI
import com.firebase.ui.auth.FirebaseAuthUIActivityResultContract

data class User (
    private val nullableName: String?,
    private val nullableEmail: String?,
    val uid: String
) {
    val name: String
        get() = if (nullableName.isNullOrBlank()) "User logged out" else nullableName

    val email: String
        get() = nullableEmail ?: "No Email"
}

const val invalidUserUid = "-1"

fun User.isInvalid(): Boolean {
    Log.d("AUTH_CHECK", "User Id: $uid, Id is Empty: ${name.isEmpty()}")
    return uid == invalidUserUid || name.isEmpty()
}

val invalidUser = User(null, null, invalidUserUid)

class AuthUser(private val registry: ActivityResultRegistry) :
    DefaultLifecycleObserver,
    FirebaseAuth.AuthStateListener {

    private lateinit var signInLauncher: ActivityResultLauncher<Intent>
    private val _liveUser = MutableLiveData(invalidUser)
    val liveUser: LiveData<User> = _liveUser
    var pendingLogin = false

    init {
        Firebase.auth.addAuthStateListener(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        // Registers the login launcher with the activity's registry
        signInLauncher = registry.register("key", owner,
            FirebaseAuthUIActivityResultContract()) { result ->
            pendingLogin = false
            Log.d("AUTH", "Sign in result: ${result.resultCode}")
        }
    }

    override fun onAuthStateChanged(auth: FirebaseAuth) {
        val firebaseUser = auth.currentUser
        if (firebaseUser == null) {
            _liveUser.postValue(invalidUser)
            login() // Auto-trigger login if no user is found
        } else {
            val user = User(firebaseUser.displayName, firebaseUser.email, firebaseUser.uid)
            _liveUser.postValue(user)
        }
    }

    private fun login() {
        if (Firebase.auth.currentUser == null && !pendingLogin) {
            pendingLogin = true
            val providers = arrayListOf(AuthUI.IdpConfig.EmailBuilder().setRequireName(true).build())

            val signInIntent = AuthUI.getInstance()
                .createSignInIntentBuilder()
                .setAvailableProviders(providers)
                .setTheme(R.style.Theme_QuadballSidelineManager) // Use your app theme
                .setCredentialManagerEnabled(false)
                .build()

            signInLauncher.launch(signInIntent)
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        Firebase.auth.removeAuthStateListener(this)
    }
}