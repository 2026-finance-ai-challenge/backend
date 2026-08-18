package com.kmarket.navigator.backend.disclosure.presentation;

import java.util.List;
import java.util.UUID;

import com.kmarket.navigator.backend.disclosure.domain.DisclosureAnswer;

record DisclosureAnswerResponse(
	String answer,
	boolean refused,
	String refusalReason,
	List<Citation> citations,
	String model,
	String promptVersion
) {
	static DisclosureAnswerResponse from(DisclosureAnswer answer) {
		return new DisclosureAnswerResponse(
			answer.answer(),
			answer.refused(),
			answer.refusalReason(),
			answer.citations().stream().map(Citation::from).toList(),
			answer.model(),
			answer.promptVersion()
		);
	}

	record Citation(
		String id,
		UUID chunkId,
		UUID documentId,
		int documentVersion,
		List<UUID> sectionIds,
		int firstOrdinal,
		int lastOrdinal,
		String heading,
		String excerpt
	) {
		private static Citation from(DisclosureAnswer.Citation citation) {
			return new Citation(
				citation.id(),
				citation.chunkId(),
				citation.documentId(),
				citation.documentVersion(),
				citation.sectionIds(),
				citation.firstOrdinal(),
				citation.lastOrdinal(),
				citation.heading(),
				citation.excerpt()
			);
		}
	}
}
