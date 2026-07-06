package com.example.personeltracking2026kodau.core.network

sealed class NetworkState {
    object Connected : NetworkState()
    object Connecting : NetworkState()
} 