package com.kmarket.navigator.backend.chat.domain;

public record ChatSubmission(ChatMessage userMessage, ChatGeneration generation) {
}
