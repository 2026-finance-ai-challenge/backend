package com.kmarket.navigator.backend.disclosure.domain;

public record ListedCommonStock(
	String stockCode,
	String nameKo,
	Market market,
	String isinCode
) {
	public ListedCommonStock(String stockCode, String nameKo, Market market) {
		this(stockCode, nameKo, market, null);
	}
}
