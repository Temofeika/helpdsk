package com.example.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tickets")
data class TicketEntity(
    @PrimaryKey val id: Int,
    val number: String,
    val title: String,
    val state: String,
    val priority: String,
    val queue: String,
    val created: String,
    val customerID: String,
    val customerUser: String,
    val age: String
)

@Entity(tableName = "articles")
data class ArticleEntity(
    @PrimaryKey val id: Int, // Negative IDs for local pending replies
    val ticketId: Int,
    val senderType: String,
    val from: String,
    val subject: String,
    val body: String,
    val created: String,
    val isPending: Boolean = false
)

@Entity(tableName = "attachments")
data class AttachmentEntity(
    @PrimaryKey val fileId: String, // Combined id: "articleId_filename"
    val articleId: Int,
    val filename: String,
    val contentType: String,
    val size: String,
    val content: String? // Base64 contents
)
