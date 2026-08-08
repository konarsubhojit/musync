package com.musync.data.model

enum class ConnectionState {
    CONNECTED,
    CONNECTING,
    DISCONNECTED,

    /**
     * Repeated handshake failures without ever connecting, i.e. the configured
     * server URL is wrong, the server is down, or the network blocks it.
     */
    UNREACHABLE,
}
