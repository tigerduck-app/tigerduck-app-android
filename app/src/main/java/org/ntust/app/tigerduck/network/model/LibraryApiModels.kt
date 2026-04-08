package org.ntust.app.tigerduck.network.model

data class LibraryLoginRequest(
    val username: String,
    val password: String,
    val language: String = "zh"
)

data class LibraryLoginResponse(
    val data: LibraryLoginData?,
    val error: LibraryApiError? = null
)

data class LibraryLoginData(
    val username: String,
    val token: String,
    val expirationTimeStamp: Long
)

data class LibraryQRRequest(
    val token: String,
    val language: String = "zh"
)

data class LibraryQRResponse(
    val data: String?,
    val error: LibraryApiError? = null
)

data class LibraryApiError(
    val code: Int,
    val message: String
)
