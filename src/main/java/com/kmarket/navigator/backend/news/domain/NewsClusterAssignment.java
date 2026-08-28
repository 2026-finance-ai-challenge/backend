package com.kmarket.navigator.backend.news.domain;

import java.util.UUID;

public record NewsClusterAssignment(UUID articleId, UUID clusterId) {
}
