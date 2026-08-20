package com.kmarket.navigator.backend.disclosure.domain;

public record ListedCommonStock(
	String stockCode,
	String nameKo,
	Market market
) {
}
