package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.data.database.AppDatabase
import com.example.data.repository.TicketRepository
import com.example.ui.screens.LoginScreen
import com.example.ui.screens.TicketDetailScreen
import com.example.ui.screens.TicketListScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.HelpdeskViewModel
import com.example.ui.viewmodel.HelpdeskViewModelFactory

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    // 1. Initialize local Room Database
    val database = AppDatabase.getDatabase(applicationContext)
    val ticketDao = database.ticketDao()
    val articleDao = database.articleDao()
    val attachmentDao = database.attachmentDao()

    // 2. Initialize integrated repository
    val repository = TicketRepository(
      context = applicationContext,
      ticketDao = ticketDao,
      articleDao = articleDao,
      attachmentDao = attachmentDao
    )

    // 3. Initialize ViewModel via factory
    val factory = HelpdeskViewModelFactory(application, repository)
    val viewModel = ViewModelProvider(this, factory)[HelpdeskViewModel::class.java]

    setContent {
      MyApplicationTheme {
        Surface(
          modifier = Modifier.fillMaxSize(),
          color = MaterialTheme.colorScheme.background
        ) {
          val navController = rememberNavController()

          NavHost(
            navController = navController,
            startDestination = "login"
          ) {
            composable("login") {
              LoginScreen(
                viewModel = viewModel,
                onLoginSuccess = {
                  navController.navigate("ticket_list") {
                    popUpTo("login") { inclusive = true }
                  }
                }
              )
            }
            composable("ticket_list") {
              TicketListScreen(
                viewModel = viewModel,
                onNavigateToDetail = {
                  navController.navigate("ticket_detail")
                },
                onLogout = {
                  navController.navigate("login") {
                    popUpTo("ticket_list") { inclusive = true }
                  }
                }
              )
            }
            composable("ticket_detail") {
              TicketDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {
                  navController.popBackStack()
                }
              )
            }
          }
        }
      }
    }
  }
}
