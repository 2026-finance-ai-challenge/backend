package com.kmarket.navigator.backend.disclosure.infrastructure.stockmaster;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.springframework.stereotype.Component;

import com.kmarket.navigator.backend.disclosure.application.port.ListedStockGatewayException;
import com.kmarket.navigator.backend.disclosure.domain.ListedCommonStock;
import com.kmarket.navigator.backend.disclosure.domain.Market;

@Component
class KisStockMasterParser {

	private static final Charset KOREAN_CHARSET = Charset.forName("x-windows-949");
	private static final int MAX_ARCHIVE_ENTRY_BYTES = 10 * 1024 * 1024;
	private static final int KOSPI_TAIL_BYTES = 227;
	private static final int KOSDAQ_TAIL_BYTES = 221;
	private static final int KOSPI_SPAC_OFFSET = 29;
	private static final int KOSDAQ_SPAC_OFFSET = 24;
	private static final int KOSPI_PREFERRED_OFFSET = 158;
	private static final int KOSDAQ_PREFERRED_OFFSET = 153;

	List<ListedCommonStock> parse(byte[] archive, Market market) {
		byte[] master = unzipSingleEntry(archive);
		int tailBytes = market == Market.KOSPI ? KOSPI_TAIL_BYTES : KOSDAQ_TAIL_BYTES;
		int spacOffset = market == Market.KOSPI ? KOSPI_SPAC_OFFSET : KOSDAQ_SPAC_OFFSET;
		int preferredOffset = market == Market.KOSPI
			? KOSPI_PREFERRED_OFFSET
			: KOSDAQ_PREFERRED_OFFSET;
		List<ListedCommonStock> stocks = new ArrayList<>();
		for (byte[] line : splitLines(master)) {
			if (line.length < 21 + tailBytes) {
				continue;
			}
			int tailStart = line.length - tailBytes;
			String securityGroup = ascii(line, tailStart, 2);
			String spac = ascii(line, tailStart + spacOffset, 1);
			String preferred = ascii(line, tailStart + preferredOffset, 1);
			if (!securityGroup.equals("ST") || spac.equals("Y") || !preferred.equals("0")) {
				continue;
			}

			String stockCode = decode(line, 0, 9).trim();
			if (stockCode.length() > 6) {
				stockCode = stockCode.substring(stockCode.length() - 6);
			}
			String isinCode = ascii(line, 9, 12);
			if (!isinCode.matches("[A-Z]{2}[A-Z0-9]{10}")) {
				isinCode = null;
			}
			String nameKo = decode(line, 21, tailStart - 21).trim();
			if (!stockCode.matches("[0-9A-Z]{6}") || nameKo.isBlank()) {
				throw new ListedStockGatewayException("Invalid stock master row");
			}
			stocks.add(new ListedCommonStock(stockCode, nameKo, market, isinCode));
		}
		return List.copyOf(stocks);
	}

	private static byte[] unzipSingleEntry(byte[] archive) {
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
			ZipEntry entry = zip.getNextEntry();
			if (entry == null || entry.isDirectory()) {
				throw new ListedStockGatewayException("Stock master archive is empty");
			}
			byte[] content = readLimited(zip);
			zip.closeEntry();
			if (zip.getNextEntry() != null) {
				throw new ListedStockGatewayException("Stock master archive has multiple entries");
			}
			return content;
		}
		catch (IOException exception) {
			throw new ListedStockGatewayException("Stock master archive is invalid", exception);
		}
	}

	private static byte[] readLimited(ZipInputStream zip) throws IOException {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		byte[] buffer = new byte[8192];
		int total = 0;
		int read;
		while ((read = zip.read(buffer)) != -1) {
			total += read;
			if (total > MAX_ARCHIVE_ENTRY_BYTES) {
				throw new ListedStockGatewayException("Stock master entry is too large");
			}
			output.write(buffer, 0, read);
		}
		return output.toByteArray();
	}

	private static List<byte[]> splitLines(byte[] content) {
		List<byte[]> lines = new ArrayList<>();
		int start = 0;
		for (int index = 0; index <= content.length; index++) {
			if (index == content.length || content[index] == '\n') {
				int end = index > start && content[index - 1] == '\r' ? index - 1 : index;
				if (end > start) {
					lines.add(java.util.Arrays.copyOfRange(content, start, end));
				}
				start = index + 1;
			}
		}
		return lines;
	}

	private static String ascii(byte[] source, int offset, int length) {
		return new String(source, offset, length, StandardCharsets.US_ASCII).trim();
	}

	private static String decode(byte[] source, int offset, int length) {
		return new String(source, offset, length, KOREAN_CHARSET);
	}
}
