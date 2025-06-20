package com.example.test2

import java.util.*

data class Message(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val senderId: String
) 