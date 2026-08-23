package com.kmarket.navigator.backend.disclosure.infrastructure.stockmaster;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;

import com.kmarket.navigator.backend.disclosure.domain.Market;

class KisStockMasterParserTests {

	private static final Charset KOREAN_CHARSET = Charset.forName("x-windows-949");

	private final KisStockMasterParser parser = new KisStockMasterParser();

	@Test
	void keepsOnlyKospiCommonStocks() throws IOException {
		byte[] archive = archive(
			line("005930", "삼성전자", 227, 29, 158, "ST", "N", "0"),
			line("005935", "삼성전자우", 227, 29, 158, "ST", "N", "1"),
			line("069500", "KODEX 200", 227, 29, 158, "EF", "N", "0")
		);

		var stocks = parser.parse(archive, Market.KOSPI);

		assertThat(stocks).singleElement().satisfies(stock -> {
			assertThat(stock.stockCode()).isEqualTo("005930");
			assertThat(stock.nameKo()).isEqualTo("삼성전자");
			assertThat(stock.market()).isEqualTo(Market.KOSPI);
			assertThat(stock.isinCode()).isEqualTo("KR7005930003");
		});
	}

	@Test
	void excludesKosdaqPreferredStocksAndSpacs() throws IOException {
		byte[] archive = archive(
			line("0001A0", "덕양에너젠", 221, 24, 153, "ST", "N", "0"),
			line("021045", "대호특수강우", 221, 24, 153, "ST", "N", "2"),
			line("123456", "테스트스팩", 221, 24, 153, "ST", "Y", "0")
		);

		var stocks = parser.parse(archive, Market.KOSDAQ);

		assertThat(stocks).singleElement().satisfies(stock -> {
			assertThat(stock.stockCode()).isEqualTo("0001A0");
			assertThat(stock.market()).isEqualTo(Market.KOSDAQ);
		});
	}

	private static byte[] line(
		String code,
		String name,
		int tailLength,
		int spacOffset,
		int preferredOffset,
		String group,
		String spac,
		String preferred
	) throws IOException {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		output.write(("   " + code).getBytes(StandardCharsets.US_ASCII));
		String isinCode = code.equals("005930") ? "KR7005930003" : " ".repeat(12);
		output.write(isinCode.getBytes(StandardCharsets.US_ASCII));
		output.write(name.getBytes(KOREAN_CHARSET));
		byte[] tail = " ".repeat(tailLength).getBytes(StandardCharsets.US_ASCII);
		System.arraycopy(group.getBytes(StandardCharsets.US_ASCII), 0, tail, 0, 2);
		tail[spacOffset] = (byte)spac.charAt(0);
		tail[preferredOffset] = (byte)preferred.charAt(0);
		output.write(tail);
		return output.toByteArray();
	}

	private static byte[] archive(byte[]... lines) throws IOException {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(output)) {
			zip.putNextEntry(new ZipEntry("stock.mst"));
			for (byte[] line : lines) {
				zip.write(line);
				zip.write('\n');
			}
			zip.closeEntry();
		}
		return output.toByteArray();
	}
}
