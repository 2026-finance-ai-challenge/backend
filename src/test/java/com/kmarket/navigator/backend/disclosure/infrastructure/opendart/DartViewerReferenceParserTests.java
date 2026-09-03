package com.kmarket.navigator.backend.disclosure.infrastructure.opendart;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DartViewerReferenceParserTests {
	static final String RECEIPT = "20240416000014";
	static final String INITIAL = "viewDoc(\"20240416000014\", \"9901648\", \"1\", \"100\", \"200\", \"dart3.xsd\", \"\")";

	static String node(String id, String offset, String length) {
		return "node1['rcpNo'] = \"20240416000014\";\nnode1['dcmNo'] = \"9901648\";\n"
			+ "node1['eleId'] = \"" + id + "\";\nnode1['offset'] = \"" + offset + "\";\n"
			+ "node1['length'] = \"" + length + "\";\nnode1['dtd'] = \"dart3.xsd\";\n";
	}

	@Test
	void collectsEveryTopLevelRangeWithoutDuplicatingNestedSections() {
		var result = DartViewerReferenceParser.parse(RECEIPT, INITIAL + node("1", "100", "200")
			+ node("2", "120", "40") + node("3", "301", "100") + node("4", "401", "200"));
		assertThat(result).extracting(DartViewerReferenceParser.Reference::elementId).containsExactly("1", "3", "4");
	}

	@Test
	void rejectsPartialOverlapAndUnrecognizedTocInsteadOfReturningOnlyCover() {
		assertThatThrownBy(() -> DartViewerReferenceParser.parse(RECEIPT, INITIAL + node("2", "250", "100")))
			.isInstanceOfSatisfying(OpenDartException.class, error -> assertThat(error.errorCode()).isEqualTo("DART_VIEWER_TOC_OVERLAP"));
		assertThatThrownBy(() -> DartViewerReferenceParser.parse(RECEIPT, INITIAL + node("2", "301", "100").replace("['length']", "['changed']")))
			.isInstanceOfSatisfying(OpenDartException.class, error -> assertThat(error.errorCode()).isEqualTo("DART_VIEWER_TOC_UNRECOGNIZED"));
	}

	@Test
	void preservesExplicitWholeDocumentReference() {
		assertThat(DartViewerReferenceParser.parse(RECEIPT,
			"viewDoc(\"20240416000014\", \"9901648\", \"0\", \"0\", \"0\", \"dart4.xsd\", \"\")"))
			.singleElement().satisfies(ref -> assertThat(ref.elementId()).isEqualTo("0"));
	}
}
