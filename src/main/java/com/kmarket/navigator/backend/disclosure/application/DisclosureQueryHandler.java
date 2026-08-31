package com.kmarket.navigator.backend.disclosure.application;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.kmarket.navigator.backend.disclosure.application.port.DisclosureRepository;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureCursor;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureDetail;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureListQuery;
import com.kmarket.navigator.backend.disclosure.domain.DisclosurePage;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureSummary;
import com.kmarket.navigator.backend.global.error.BusinessException;
import com.kmarket.navigator.backend.global.error.ErrorCode;

@Service
public class DisclosureQueryHandler {

	private final DisclosureRepository disclosureRepository;

	public DisclosureQueryHandler(DisclosureRepository disclosureRepository) {
		this.disclosureRepository = disclosureRepository;
	}

	public DisclosurePage findAll(DisclosureListQuery query) {
		List<DisclosureSummary> fetched = disclosureRepository.findAll(query, query.limit() + 1);
		boolean hasNext = fetched.size() > query.limit();
		List<DisclosureSummary> items = new ArrayList<>(
			fetched.subList(0, Math.min(fetched.size(), query.limit()))
		);
		String nextCursor = null;
		if (hasNext && !items.isEmpty()) {
			DisclosureSummary last = items.getLast();
			nextCursor = new DisclosureCursor(last.filedDate(), last.receiptNumber()).encode();
		}
		return new DisclosurePage(items, nextCursor);
	}

	public DisclosureDetail findOne(String receiptNumber) {
		return disclosureRepository.findByReceiptNumber(receiptNumber)
			.orElseThrow(() -> new BusinessException(ErrorCode.DISCLOSURE_NOT_FOUND));
	}

	public void requestIndexing(String receiptNumber) {
		if (!disclosureRepository.requestIndexing(receiptNumber)) {
			throw new BusinessException(ErrorCode.DISCLOSURE_NOT_FOUND);
		}
	}
}
