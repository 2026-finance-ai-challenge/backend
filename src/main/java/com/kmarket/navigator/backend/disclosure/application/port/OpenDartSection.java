package com.kmarket.navigator.backend.disclosure.application.port;

import com.kmarket.navigator.backend.disclosure.domain.SectionKind;

public record OpenDartSection(
	int ordinal,
	SectionKind kind,
	String heading,
	String text,
	String tableData
) {
}
