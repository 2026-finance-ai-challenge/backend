package com.kmarket.navigator.backend.disclosure.domain;

import java.util.List;
import java.util.UUID;

public record DisclosureAnswer(
	String answer,
	boolean refused,
	String refusalReason,
	List<Citation> citations,
	String model,
	String promptVersion
) {
	public DisclosureAnswer {
		citations = List.copyOf(citations);
	}

	public record Citation(
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
		public Citation {
			sectionIds = List.copyOf(sectionIds);
		}
	}
}
