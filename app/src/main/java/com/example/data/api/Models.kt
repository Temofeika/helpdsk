package com.example.data.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class SessionRequest(
    @Json(name = "UserLogin") val userLogin: String? = null,
    @Json(name = "CustomerUserLogin") val customerUserLogin: String? = null,
    @Json(name = "Password") val password: String
)

@JsonClass(generateAdapter = true)
data class SessionResponse(
    @Json(name = "SessionID") val sessionId: String?,
    @Json(name = "UserLogin") val userLogin: String?,
    @Json(name = "CustomerUserLogin") val customerUserLogin: String?,
    @Json(name = "Error") val error: ApiError? = null
)

@JsonClass(generateAdapter = true)
data class ApiError(
    @Json(name = "ErrorCode") val errorCode: String?,
    @Json(name = "ErrorMessage") val errorMessage: String?
)

@JsonClass(generateAdapter = true)
data class TicketSearchRequest(
    @Json(name = "SessionID") val sessionId: String,
    @Json(name = "States") val states: List<String>? = null,
    @Json(name = "Queues") val queues: List<String>? = null
)

@JsonClass(generateAdapter = true)
data class TicketSearchResponse(
    @Json(name = "TicketID") val ticketIds: List<Int>? = null,
    @Json(name = "Error") val error: ApiError? = null
)

@JsonClass(generateAdapter = true)
data class TicketGetRequest(
    @Json(name = "SessionID") val sessionId: String,
    @Json(name = "AllArticles") val allArticles: Int = 1,
    @Json(name = "Attachments") val attachments: Int = 1
)

@JsonClass(generateAdapter = true)
data class TicketGetResponse(
    @Json(name = "Ticket") val tickets: List<ApiTicket>? = null,
    @Json(name = "Error") val error: ApiError? = null
)

@JsonClass(generateAdapter = true)
data class ApiTicket(
    @Json(name = "TicketID") val ticketId: Int,
    @Json(name = "TicketNumber") val ticketNumber: String,
    @Json(name = "Title") val title: String,
    @Json(name = "State") val state: String,
    @Json(name = "Priority") val priority: String,
    @Json(name = "Queue") val queue: String,
    @Json(name = "Created") val created: String,
    @Json(name = "CustomerID") val customerId: String?,
    @Json(name = "CustomerUserID") val customerUserId: String?,
    @Json(name = "Age") val age: String?,
    @Json(name = "Article") val articles: List<ApiArticle>? = null
)

@JsonClass(generateAdapter = true)
data class ApiArticle(
    @Json(name = "ArticleID") val articleId: Int,
    @Json(name = "SenderType") val senderType: String?,
    @Json(name = "From") val from: String?,
    @Json(name = "Subject") val subject: String?,
    @Json(name = "Body") val body: String?,
    @Json(name = "Created") val created: String?,
    @Json(name = "Attachment") val attachments: List<ApiAttachment>? = null
)

@JsonClass(generateAdapter = true)
data class ApiAttachment(
    @Json(name = "FileID") val fileId: String?,
    @Json(name = "Filename") val filename: String?,
    @Json(name = "ContentType") val contentType: String?,
    @Json(name = "Filesize") val filesize: String?,
    @Json(name = "Content") val content: String? // Base64 content
)

@JsonClass(generateAdapter = true)
data class TicketUpdateRequest(
    @Json(name = "SessionID") val sessionId: String,
    @Json(name = "Ticket") val ticket: TicketUpdateFields? = null,
    @Json(name = "Article") val article: ArticleUpdateFields
)

@JsonClass(generateAdapter = true)
data class TicketUpdateFields(
    @Json(name = "State") val state: String? = null,
    @Json(name = "Priority") val priority: String? = null
)

@JsonClass(generateAdapter = true)
data class ArticleUpdateFields(
    @Json(name = "Subject") val subject: String,
    @Json(name = "Body") val body: String,
    @Json(name = "ContentType") val contentType: String = "text/plain; charset=utf8",
    @Json(name = "SenderType") val senderType: String = "agent",
    @Json(name = "HistoryType") val historyType: String = "OwnerUpdate",
    @Json(name = "HistoryComment") val historyComment: String = "Reply added from Mobile App"
)

@JsonClass(generateAdapter = true)
data class TicketUpdateResponse(
    @Json(name = "TicketID") val ticketId: Int?,
    @Json(name = "ArticleID") val articleId: Int?,
    @Json(name = "Error") val error: ApiError? = null
)
