package com.kmarket.navigator.backend.tax.infrastructure.pdf;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import com.kmarket.navigator.backend.tax.domain.TaxDocument;
import com.kmarket.navigator.backend.tax.domain.TaxDocumentType;

@Component
public class TaxCorrectionPreview {
	public byte[] render(List<TaxDocument> documents) {
		var application = documents.stream().filter(d -> d.documentType() == TaxDocumentType.REDUCED_TAX_APPLICATION).findFirst().orElseThrow();
		var certificate = documents.stream().filter(d -> d.documentType() == TaxDocumentType.RESIDENCY_CERTIFICATE).findFirst().orElseThrow();
		try (var template = new ClassPathResource("forms/tax-correction-request.pdf").getInputStream();
			PDDocument pdf = Loader.loadPDF(template.readAllBytes());
			var fontInput = new ClassPathResource("fonts/NanumBarunGothic.ttf").getInputStream()) {
			var font = PDType0Font.load(pdf, fontInput, true);
			for (int i = 0; i < pdf.getNumberOfPages(); i++) {
				try (var stream = new PDPageContentStream(pdf, pdf.getPage(i), PDPageContentStream.AppendMode.APPEND, true, true)) {
					write(stream, font, "예상 작성 경정청구서 - 사전 점검용 / 제출·접수된 문서가 아닙니다", 35, 812, 10, 530);
					if (i != 0) continue;
					// 하나금융 원본 양식 좌표를 사용하며 검증된 정보 외에는 임의로 작성하지 않는다.
					write(stream, font, application.fields().holderName(), 173, 701, 9, 170);
					write(stream, font, application.fields().documentNumber(), 194, 674, 8, 95);
					String country = certificate.fields().residencyCountry();
					write(stream, font, country == null ? null : java.util.Locale.of("", country).getDisplayCountry(java.util.Locale.ENGLISH), 194, 645, 8, 150);
					write(stream, font, certificate.fields().residencyCountry(), 522, 645, 8, 32);
					write(stream, font, "검증 문서에 근거한 예상 초안입니다. 금융사 확인 후 작성·제출해야 합니다.", 125, 437, 8, 405);
					write(stream, font, "배당 지급·원천징수 내역과 청구 금액, 금융사·대리인 정보는 미확인으로 비워 두었습니다.", 125, 424, 8, 405);
				}
			}
			// 편집 위젯과 서명은 생성하지 않는다. 원본 양식에 정적인 텍스트만 합성한다.
			if (pdf.getDocumentCatalog().getAcroForm() != null) pdf.getDocumentCatalog().getAcroForm().flatten();
			pdf.getDocumentInformation().setTitle("예상 작성 경정청구서 - KART 사전 점검용");
			pdf.getDocumentInformation().setAuthor("KART - estimated draft, not submitted");
			var output = new ByteArrayOutputStream(); pdf.save(output); return output.toByteArray();
		} catch (IOException exception) { throw new IllegalStateException("Correction preview could not be rendered", exception); }
	}
	private void write(PDPageContentStream stream, PDType0Font font, String value, float x, float y, float size, float width) throws IOException {
		if (value == null || value.isBlank()) return;
		String text = value.replaceAll("[\\p{Cntrl}]", " ").strip();
		StringBuilder safe = new StringBuilder();
		for (int point : text.codePoints().toArray()) { try { font.encode(new String(Character.toChars(point))); safe.appendCodePoint(point); } catch (IllegalArgumentException exception) { safe.append('?'); } }
		text = safe.toString();
		float actual = Math.min(size, width / Math.max(1, font.getStringWidth(text) / 1000f));
		stream.beginText(); stream.setFont(font, actual); stream.newLineAtOffset(x, y); stream.showText(text); stream.endText();
	}
}
