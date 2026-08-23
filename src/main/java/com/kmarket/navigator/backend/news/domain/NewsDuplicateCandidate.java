package com.kmarket.navigator.backend.news.domain;

import java.util.UUID;

public record NewsDuplicateCandidate(UUID clusterId, String comparableText) {
}
