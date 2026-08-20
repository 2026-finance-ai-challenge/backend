package com.kmarket.navigator.backend.disclosure.application;

import org.springframework.stereotype.Service;

import com.kmarket.navigator.backend.disclosure.application.port.DisclosureRagGateway;
import com.kmarket.navigator.backend.disclosure.application.port.DisclosureRepository;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureAnswer;
import com.kmarket.navigator.backend.disclosure.domain.DisclosureQuestion;
import com.kmarket.navigator.backend.disclosure.domain.IndexStatus;
import com.kmarket.navigator.backend.global.error.BusinessException;
import com.kmarket.navigator.backend.global.error.ErrorCode;

@Service
public class DisclosureQuestionHandler {

	private final DisclosureRepository disclosureRepository;
	private final DisclosureRagGateway disclosureRagGateway;

	public DisclosureQuestionHandler(
		DisclosureRepository disclosureRepository,
		DisclosureRagGateway disclosureRagGateway
	) {
		this.disclosureRepository = disclosureRepository;
		this.disclosureRagGateway = disclosureRagGateway;
	}

	public DisclosureAnswer ask(String receiptNumber, DisclosureQuestion question) {
		var indexStatus = disclosureRepository.findIndexStatus(receiptNumber)
			.orElseThrow(() -> new BusinessException(ErrorCode.DISCLOSURE_NOT_FOUND));
		if (indexStatus != IndexStatus.READY) {
			disclosureRepository.requestIndexing(receiptNumber);
			throw new BusinessException(ErrorCode.DISCLOSURE_INDEX_NOT_READY);
		}
		return disclosureRagGateway.ask(receiptNumber, question);
	}
}
