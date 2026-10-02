package com.example.youtubeapp.data.model

data class ProxyConfig(
    val type: ProxyType = ProxyType.NONE,
    val host: String = "",
    val port: Int = 0,
    val username: String = "",
    val password: String = "",
    val isEnabled: Boolean = false
) {
    fun isValid(): Boolean {
        return when (type) {
            ProxyType.NONE -> true
            ProxyType.HTTP, ProxyType.HTTPS -> host.isNotBlank() && port in 1..65535
            ProxyType.SOCKS5 -> host.isNotBlank() && port in 1..65535
        }
    }
}

enum class ProxyType {
    NONE,
    HTTP,
    HTTPS,
    SOCKS5
}
