package com.kmarket.navigator.backend.identity.application;

public record ClientContext(String ipHash, String userAgentHash, String requestId) {
}
