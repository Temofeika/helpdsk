package com.example.data.api

import retrofit2.http.*

interface OtoboApiService {
    @POST("Session")
    suspend fun createSession(
        @Body request: SessionRequest
    ): SessionResponse

    @POST("Ticket")
    suspend fun searchTickets(
        @Body request: TicketSearchRequest
    ): TicketSearchResponse

    @POST("Ticket/{ticketId}")
    suspend fun getTicket(
        @Path("ticketId") ticketId: Int,
        @Body request: TicketGetRequest
    ): TicketGetResponse

    @POST("TicketUpdate/{ticketId}")
    suspend fun updateTicket(
        @Path("ticketId") ticketId: Int,
        @Body request: TicketUpdateRequest
    ): TicketUpdateResponse
}
