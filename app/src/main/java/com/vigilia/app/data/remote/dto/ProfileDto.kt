package com.vigilia.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ProfileDto(
    @SerialName("id") val id: String,
    @SerialName("full_name") val fullname: String,
    @SerialName("tos_version") val tosVersion: String? = null,
    @SerialName("tos_accepted_at") val tosAcceptedAt: String? = null,
    @SerialName("privacy_version") val privacyVersion: String? = null,
    @SerialName("privacy_accepted_at") val privacyAcceptedAt: String? = null,
)
