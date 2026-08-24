package com.kmarket.navigator.backend.disclosure.application.port;

import java.util.Optional;

public interface DisclosureTitleTranslationCatalog {

	Optional<String> translate(String normalizedTitle);

	String reviewKey(String normalizedTitle);
}
