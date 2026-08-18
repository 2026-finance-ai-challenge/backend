package com.kmarket.navigator.backend.disclosure.domain;

import java.util.UUID;

public record DisclosureSection(
	UUID id,
	int ordinal,
	SectionKind kind,
	String heading,
	String text,
	String tableData
) {
}
