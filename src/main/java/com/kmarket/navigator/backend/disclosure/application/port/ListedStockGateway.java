package com.kmarket.navigator.backend.disclosure.application.port;

import java.util.List;

import com.kmarket.navigator.backend.disclosure.domain.ListedCommonStock;

public interface ListedStockGateway {

	List<ListedCommonStock> fetchCommonStocks();
}
