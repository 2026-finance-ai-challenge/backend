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
		return render(documents, "ko");
	}
	public byte[] render(List<TaxDocument> documents, String locale) {
		try (var template = new ClassPathResource("forms/tax-correction-request.pdf").getInputStream();
			PDDocument pdf = Loader.loadPDF(template.readAllBytes());
			var fontInput = new ClassPathResource("fonts/NanumBarunGothic.ttf").getInputStream()) {
			var font = PDType0Font.load(pdf, fontInput, true);
			for (int i = 0; i < pdf.getNumberOfPages(); i++) {
				try (var stream = new PDPageContentStream(pdf, pdf.getPage(i), PDPageContentStream.AppendMode.APPEND, true, true)) {
					write(stream, font, locale.equals("ko") ? "예상 작성 경정청구서 - 사전 점검용 / 제출·접수된 문서가 아닙니다" : "Estimated correction request - preview only / not signed or submitted", 35, 812, 10, 530);
					if (i != 0) continue;
					// 하나금융 원본 양식 좌표를 사용하며 검증된 정보 외에는 임의로 작성하지 않는다.
					for (var field : fields(documents, locale)) {
						if (field.value() == null || field.value().isBlank()) continue;
						stream.setNonStrokingColor(new java.awt.Color(250, 247, 229));
						stream.addRect(field.x(), field.y(), field.width(), field.height()); stream.fill();
						stream.setStrokingColor(new java.awt.Color(182, 164, 81)); stream.setLineWidth(0.4f);
						stream.addRect(field.x(), field.y(), field.width(), field.height()); stream.stroke();
						stream.setNonStrokingColor(java.awt.Color.BLACK);
						write(stream, font, field.value(), field.x() + 2, field.y() + 4, 8, field.width() - 4);
					}
					write(stream, font, locale.equals("ko") ? "검증 문서에 근거한 예상 초안이며 금융사의 확인과 보완이 필요합니다." : "Draft based on checked documents. Your institution must review and complete it.", 125, 437, 8, 405);
					write(stream, font, locale.equals("ko") ? "지급·원천징수 내역, 청구 금액, 금융사·대리인 정보는 미확인으로 비워 두었습니다." : "Payment, withholding, claim amount and institution/agent details are not provided.", 125, 424, 8, 405);
				}
			}
			// 편집 위젯과 서명은 생성하지 않는다. 원본 양식에 정적인 텍스트만 합성한다.
			if (pdf.getDocumentCatalog().getAcroForm() != null) pdf.getDocumentCatalog().getAcroForm().flatten();
			pdf.getDocumentInformation().setTitle(locale.equals("ko") ? "예상 작성 경정청구서 - KART 사전 점검용" : "Estimated correction request - KART preview");
			pdf.getDocumentInformation().setAuthor("KART - estimated draft, not submitted");
			var output = new ByteArrayOutputStream(); pdf.save(output); return output.toByteArray();
		} catch (IOException exception) { throw new IllegalStateException("Correction preview could not be rendered", exception); }
	}
	public List<PreviewField> fields(List<TaxDocument> documents, String locale) {
		var application = documents.stream().filter(d -> d.documentType() == TaxDocumentType.REDUCED_TAX_APPLICATION).findFirst().orElseThrow().fields();
		var certificate = documents.stream().filter(d -> d.documentType() == TaxDocumentType.RESIDENCY_CERTIFICATE).findFirst().orElseThrow().fields();
		boolean ko = locale.equals("ko");
		String country = certificate.residencyCountry();
		return List.of(
			new PreviewField("claimantName", ko ? "청구인 성명" : "Claimant name", application.holderName(), 1, 173, 697, 170, 16),
			new PreviewField("birthDate", ko ? "생년월일" : "Date of birth", application.birthDate(), 1, 432, 697, 90, 16),
			new PreviewField("taxpayerIdentificationNumber", ko ? "납세자번호" : "Taxpayer ID", application.documentNumber(), 1, 194, 670, 95, 16),
			new PreviewField("claimantPhone", ko ? "전화번호" : "Phone number", application.phoneNumber(), 1, 441, 670, 90, 16),
			new PreviewField("claimantResidence", ko ? "거주지국" : "Country of residence", country == null ? null : java.util.Locale.of("", country).getDisplayCountry(ko ? java.util.Locale.KOREAN : java.util.Locale.ENGLISH), 1, 194, 641, 150, 16),
			new PreviewField("residencyCountryCode", ko ? "거주지국 코드" : "Country code", country, 1, 522, 641, 32, 16),
			new PreviewField("claimantAddress", ko ? "주소" : "Address", application.address(), 1, 173, 613, 360, 16));
	}
	public record PreviewField(String key, String label, String value, int page, float x, float y, float width, float height) { }
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
