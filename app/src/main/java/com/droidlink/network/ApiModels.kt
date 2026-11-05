package com.droidlink.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RegisterRequest(
    val name: String,
    val model: String,
    @SerialName("registration_token") val registrationToken: String? = null,
)

@Serializable
data class RegisterResponse(
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_secret") val deviceSecret: String,
)

@Serializable
data class TokenRequest(
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_secret") val deviceSecret: String,
)

@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresIn: Int,
)

@Serializable
data class CodeResponse(
    val code: String,
    @SerialName("expires_in") val expiresIn: Int,
    @SerialName("qr_payload") val qrPayload: String,
)

@Serializable
data class ClaimRequest(val code: String)

@Serializable
data class ScopesBody(val scopes: List<String>? = null)

@Serializable
data class SessionRequestBody(
    @SerialName("pairing_id") val pairingId: String,
    val scopes: List<String>,
)

@Serializable
data class PeerDto(val id: String? = null, val name: String? = null, val model: String? = null)

@Serializable
data class PairingDto(
    val id: String,
    val role: String,
    val status: String,
    val scopes: List<String> = emptyList(),
    val peer: PeerDto = PeerDto(),
    @SerialName("created_at") val createdAt: Double = 0.0,
    @SerialName("updated_at") val updatedAt: Double = 0.0,
)

@Serializable
data class SessionDto(
    val id: String,
    @SerialName("pairing_id") val pairingId: String,
    val role: String,
    val status: String,
    val scopes: List<String> = emptyList(),
    val peer: PeerDto = PeerDto(),
    @SerialName("created_at") val createdAt: Double = 0.0,
    @SerialName("expires_at") val expiresAt: Double = 0.0,
    @SerialName("ended_at") val endedAt: Double? = null,
    @SerialName("end_reason") val endReason: String? = null,
    @SerialName("server_time") val serverTime: Double = 0.0,
)

/** Server -> device events pushed over the presence WebSocket. */
@Serializable
data class ServerEvent(
    val event: String,
    val pairing: PairingDto? = null,
    val session: SessionDto? = null,
)

@Serializable
data class ErrorBody(val error: String? = null, val message: String? = null)
