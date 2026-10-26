package com.efisuby.abl.data.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class AuthState {
    SIGNED_OUT,
    SIGNED_IN,

    /** The server rejected the token; local books stay available. */
    REAUTH_REQUIRED,
}

class AuthRepository(private val store: TokenStore) {

    private val _state = MutableStateFlow(if (store.read() != null) AuthState.SIGNED_IN else AuthState.SIGNED_OUT)
    val state: StateFlow<AuthState> = _state

    /** Null unless signed in. */
    fun token(): String? = if (_state.value == AuthState.SIGNED_IN) store.read() else null

    fun signIn(token: String) {
        store.write(token)
        _state.value = AuthState.SIGNED_IN
    }

    fun signOut() {
        store.clear()
        _state.value = AuthState.SIGNED_OUT
    }

    /** Called by the API client on 401: the token is useless, so it is dropped. */
    fun onUnauthorized() {
        store.clear()
        _state.value = AuthState.REAUTH_REQUIRED
    }
}
