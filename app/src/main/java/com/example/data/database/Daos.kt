package com.example.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TicketDao {
    @Query("SELECT * FROM tickets ORDER BY id DESC")
    fun getAllTickets(): Flow<List<TicketEntity>>

    @Query("SELECT * FROM tickets WHERE id = :id")
    fun getTicketById(id: Int): Flow<TicketEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTickets(tickets: List<TicketEntity>)

    @Query("DELETE FROM tickets")
    suspend fun clearTickets()

    @Query("DELETE FROM tickets WHERE id = :id")
    suspend fun deleteTicketById(id: Int)
}

@Dao
interface ArticleDao {
    @Query("SELECT * FROM articles WHERE ticketId = :ticketId ORDER BY id ASC")
    fun getArticlesForTicket(ticketId: Int): Flow<List<ArticleEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertArticles(articles: List<ArticleEntity>)

    @Query("DELETE FROM articles WHERE ticketId = :ticketId")
    suspend fun clearArticlesForTicket(ticketId: Int)
}

@Dao
interface AttachmentDao {
    @Query("SELECT * FROM attachments WHERE articleId = :articleId")
    fun getAttachmentsForArticle(articleId: Int): Flow<List<AttachmentEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAttachments(attachments: List<AttachmentEntity>)
}
