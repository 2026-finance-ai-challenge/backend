package com.kmarket.navigator.backend.stock.infrastructure.kis;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.annotation.PreDestroy;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.WebSocketContainer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.kmarket.navigator.backend.stock.application.port.MarketSnapshotRepository;
import com.kmarket.navigator.backend.stock.domain.MarketDataStatus;
import com.kmarket.navigator.backend.stock.domain.MarketIndexSnapshot;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class KisRealtimeMarketService {

	private static final Logger log = LoggerFactory.getLogger(KisRealtimeMarketService.class);
	private static final ZoneId KOREA_ZONE = ZoneId.of("Asia/Seoul");
	private static final DateTimeFormatter KIS_DATE = DateTimeFormatter.BASIC_ISO_DATE;
	private static final DateTimeFormatter KIS_TIME = DateTimeFormatter.ofPattern("HHmmss");
	private static final Map<String, String> INDEX_NAMES = Map.of("0001", "KOSPI", "1001", "KOSDAQ");
	private static final int KIS_FRAME_FIELD_COUNT = 4;
	private static final int MAX_CLIENTS = 500;
	private final RestClient restClient;
	private final KisMarketProperties properties;
	private final MarketSnapshotRepository snapshotRepository;
	private final ObjectMapper objectMapper;
	private final StandardWebSocketClient webSocketClient;
	private final ScheduledExecutorService transportExecutor;
	private final AtomicReference<WebSocketSession> session = new AtomicReference<>();
	private final AtomicBoolean stopping = new AtomicBoolean();
	private final AtomicBoolean connecting = new AtomicBoolean();
	private final AtomicInteger reconnectAttempt = new AtomicInteger();
	private final Map<String, RealtimeMarketEvent> latestEvents = new ConcurrentHashMap<>();
	private final CopyOnWriteArrayList<Client> clients = new CopyOnWriteArrayList<>();
	private final LinkedHashMap<String, Boolean> stockSubscriptions = new LinkedHashMap<>(40, 0.75f, true);
	private volatile String approvalKey = "";

	public KisRealtimeMarketService(
		@Qualifier("kisMarketRestClient") RestClient restClient,
		KisMarketProperties properties,
		MarketSnapshotRepository snapshotRepository,
		ObjectMapper objectMapper
	) {
		this.restClient = restClient;
		this.properties = properties;
		this.snapshotRepository = snapshotRepository;
		this.objectMapper = objectMapper;
		WebSocketContainer container = ContainerProvider.getWebSocketContainer();
		container.setDefaultMaxTextMessageBufferSize(1024 * 1024);
		this.webSocketClient = new StandardWebSocketClient(container);
		this.transportExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "kis-realtime-transport");
			thread.setDaemon(true);
			return thread;
		});
	}

	@EventListener(ApplicationReadyEvent.class)
	public void start() {
		if (configured()) connect();
	}

	public boolean configured() {
		return properties.configured() && properties.isRealtimeEnabled();
	}

	public SseEmitter stream(String stockCode) {
		if (clients.size() >= MAX_CLIENTS) {
			throw new IllegalStateException("Realtime market stream capacity is unavailable");
		}
		if (stockCode != null) subscribeStock(stockCode);
		SseEmitter emitter = new SseEmitter(0L);
		Client client = new Client(stockCode, emitter);
		clients.add(client);
		Runnable cleanup = () -> clients.remove(client);
		emitter.onCompletion(cleanup);
		emitter.onTimeout(cleanup);
		emitter.onError(error -> cleanup.run());
		try {
			emitter.send(SseEmitter.event().name("connected").data(
				Map.of("realtime", configured(), "source", configured() ? "KIS_WEBSOCKET" : "UNAVAILABLE"),
				MediaType.APPLICATION_JSON
			));
			latestEvents.values().stream()
				.filter(event -> client.accepts(event))
				.forEach(event -> send(client, event));
		} catch (RuntimeException | java.io.IOException exception) {
			clients.remove(client);
			emitter.completeWithError(exception);
		}
		return emitter;
	}

	public synchronized SubscriptionResult subscribeStock(String stockCode) {
		if (stockCode == null || !stockCode.matches("[0-9A-Za-z]{6}")) {
			throw new IllegalArgumentException("Stock code format is invalid");
		}
		String normalized = stockCode.toUpperCase(java.util.Locale.ROOT);
		if (!configured()) return new SubscriptionResult(normalized, "DISABLED", null, 0);
		if (stockSubscriptions.get(normalized) != null) {
			return new SubscriptionResult(normalized, "ACTIVE", null, stockSubscriptions.size());
		}
		String rotatedOut = null;
		int capacity = Math.max(1, Math.min(40, properties.getMaxRealtimeStocks()));
		if (stockSubscriptions.size() >= capacity) {
			rotatedOut = stockSubscriptions.keySet().iterator().next();
			stockSubscriptions.remove(rotatedOut);
			sendSubscription("2", "H0STCNT0", rotatedOut);
		}
		stockSubscriptions.put(normalized, Boolean.TRUE);
		sendSubscription("1", "H0STCNT0", normalized);
		return new SubscriptionResult(normalized, "ACTIVE", rotatedOut, stockSubscriptions.size());
	}

	@Scheduled(fixedDelay = 15_000, initialDelay = 15_000)
	public void heartbeat() {
		for (Client client : clients) {
			try {
				client.emitter().send(SseEmitter.event().name("heartbeat").comment("keepalive"));
			} catch (Exception exception) {
				clients.remove(client);
				client.emitter().complete();
			}
		}
	}

	private void connect() {
		if (stopping.get() || !connecting.compareAndSet(false, true)) return;
		try {
			approvalKey = issueApprovalKey();
			URI uri = properties.getWebsocketUrl();
			webSocketClient.execute(new Handler(), uri.toString()).whenComplete((connected, error) -> {
				connecting.set(false);
				if (error != null) {
					log.warn("KIS realtime websocket connection failed type={}", rootType(error));
					scheduleReconnect();
				}
			});
		} catch (RuntimeException exception) {
			connecting.set(false);
			log.warn("KIS realtime websocket startup failed type={}", rootType(exception));
			scheduleReconnect();
		}
	}

	private String issueApprovalKey() {
		JsonNode response = restClient.post()
			.uri("/oauth2/Approval")
			.contentType(MediaType.APPLICATION_JSON)
			.body(Map.of(
				"grant_type", "client_credentials",
				"appkey", properties.getAppKey(),
				"secretkey", properties.getAppSecret()
			))
			.retrieve()
			.body(JsonNode.class);
		String key = response == null ? "" : response.path("approval_key").asString();
		if (key.isBlank()) throw new KisProviderException("KIS realtime approval key is unavailable");
		return key;
	}

	private void afterConnected(WebSocketSession connected) {
		session.set(connected);
		reconnectAttempt.set(0);
		List<Frame> frames = new ArrayList<>();
		INDEX_NAMES.keySet().forEach(code -> frames.add(new Frame("1", "H0UPCNT0", code)));
		synchronized (this) {
			stockSubscriptions.keySet().forEach(code -> frames.add(new Frame("1", "H0STCNT0", code)));
		}
		sendFrames(frames);
		log.info("KIS realtime websocket connected indexCount={} stockCount={}", INDEX_NAMES.size(), stockSubscriptions.size());
	}

	private void sendSubscription(String type, String transactionId, String key) {
		if (session.get() != null && session.get().isOpen()) sendFrames(List.of(new Frame(type, transactionId, key)));
	}

	private void sendFrames(List<Frame> frames) {
		for (int index = 0; index < frames.size(); index++) {
			Frame frame = frames.get(index);
			transportExecutor.schedule(() -> sendFrame(frame), index * 130L, TimeUnit.MILLISECONDS);
		}
	}

	private void sendFrame(Frame frame) {
		WebSocketSession current = session.get();
		if (current == null || !current.isOpen()) return;
		Map<String, Object> payload = Map.of(
			"header", Map.of("approval_key", approvalKey, "custtype", "P", "tr_type", frame.type(), "content-type", "utf-8"),
			"body", Map.of("input", Map.of("tr_id", frame.transactionId(), "tr_key", frame.key()))
		);
		try {
			synchronized (current) {
				current.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
			}
		} catch (Exception exception) {
			log.warn("KIS realtime subscription send failed trId={} type={}", frame.transactionId(), rootType(exception));
		}
	}

	void accept(String payload) {
		if (payload == null || payload.isBlank()) return;
		if (payload.startsWith("{")) return;
		String[] frame = payload.split("\\|", KIS_FRAME_FIELD_COUNT);
		if (frame.length != KIS_FRAME_FIELD_COUNT) return;
		String[] fields = frame[3].split("\\^", -1);
		if ("H0STCNT0".equals(frame[1]) && fields.length >= 46) acceptStock(fields);
		else if ("H0UPCNT0".equals(frame[1]) && fields.length >= 30) acceptIndex(fields);
	}

	private void acceptStock(String[] fields) {
		Instant asOf = marketInstant(fields[33], fields[1]);
		BigDecimal change = signed(decimal(fields[4]), fields[3]);
		RealtimeMarketEvent event = new RealtimeMarketEvent(
			"STOCK", fields[0], null, decimal(fields[2]), change,
			signed(decimal(fields[5]), fields[3]), decimal(fields[7]), decimal(fields[8]), decimal(fields[9]),
			longValue(fields[13]), longValue(fields[12]), asOf, "LIVE", "KIS_WEBSOCKET_TRADE"
		);
		publish("STOCK:" + fields[0], event);
	}

	private void acceptIndex(String[] fields) {
		String name = INDEX_NAMES.get(fields[0]);
		if (name == null) return;
		Instant asOf = marketInstant(LocalDate.now(KOREA_ZONE).format(KIS_DATE), fields[1]);
		BigDecimal change = signed(decimal(fields[4]), fields[3]);
		BigDecimal rate = signed(decimal(fields[9]), fields[3]);
		RealtimeMarketEvent event = new RealtimeMarketEvent(
			"INDEX", null, fields[0], decimal(fields[2]), change, rate,
			decimal(fields[10]), decimal(fields[11]), decimal(fields[12]), longValue(fields[5]), 0L,
			asOf, "LIVE", "KIS_WEBSOCKET_INDEX"
		);
		snapshotRepository.saveIndex(new MarketIndexSnapshot(
			fields[0], name, event.currentValue(), change, rate, event.volume(),
			MarketDataStatus.LIVE, asOf, event.source()
		));
		publish("INDEX:" + fields[0], event);
	}

	private void publish(String key, RealtimeMarketEvent event) {
		latestEvents.put(key, event);
		for (Client client : clients) if (client.accepts(event)) send(client, event);
	}

	private void send(Client client, RealtimeMarketEvent event) {
		try {
			client.emitter().send(SseEmitter.event().name("market").data(event, MediaType.APPLICATION_JSON));
		} catch (Exception exception) {
			clients.remove(client);
			client.emitter().complete();
		}
	}

	private void scheduleReconnect() {
		if (stopping.get()) return;
		int attempt = reconnectAttempt.incrementAndGet();
		long ceiling = Math.min(30L, 1L << Math.min(attempt - 1, 5));
		long delay = ThreadLocalRandom.current().nextLong(Math.max(1L, ceiling / 2), ceiling + 1);
		transportExecutor.schedule(this::connect, delay, TimeUnit.SECONDS);
	}

	private Instant marketInstant(String date, String time) {
		try {
			return LocalDateTime.of(LocalDate.parse(date, KIS_DATE), LocalTime.parse(time, KIS_TIME))
				.atZone(KOREA_ZONE).toInstant();
		} catch (RuntimeException exception) {
			return Instant.now();
		}
	}

	private static BigDecimal decimal(String value) {
		try { return value == null || value.isBlank() ? BigDecimal.ZERO : new BigDecimal(value); }
		catch (NumberFormatException exception) { return BigDecimal.ZERO; }
	}

	private static long longValue(String value) {
		try { return value == null || value.isBlank() ? 0L : Long.parseLong(value); }
		catch (NumberFormatException exception) { return 0L; }
	}

	static BigDecimal signed(BigDecimal value, String sign) {
		BigDecimal magnitude = value.abs();
		return Set.of("4", "5").contains(sign) ? magnitude.negate() : magnitude;
	}

	private static String rootType(Throwable error) {
		Throwable current = error;
		while (current.getCause() != null) current = current.getCause();
		return current.getClass().getSimpleName();
	}

	@PreDestroy
	public void stop() {
		stopping.set(true);
		transportExecutor.shutdownNow();
		WebSocketSession current = session.getAndSet(null);
		if (current != null && current.isOpen()) {
			try { current.close(CloseStatus.NORMAL); }
			catch (java.io.IOException exception) { log.debug("KIS realtime close failed", exception); }
		}
	}

	private final class Handler extends TextWebSocketHandler {
		@Override
		public void afterConnectionEstablished(WebSocketSession connected) {
			afterConnected(connected);
		}

		@Override
		protected void handleTextMessage(WebSocketSession connected, TextMessage message) {
			String payload = message.getPayload();
			if (payload.startsWith("{") && payload.contains("PINGPONG")) {
				try { connected.sendMessage(new PongMessage(ByteBuffer.wrap(payload.getBytes(StandardCharsets.UTF_8)))); }
				catch (java.io.IOException exception) { log.warn("KIS realtime pong failed type={}", rootType(exception)); }
				return;
			}
			accept(payload);
		}

		@Override
		public void handleTransportError(WebSocketSession connected, Throwable error) {
			log.warn("KIS realtime transport failed type={}", rootType(error));
		}

		@Override
		public void afterConnectionClosed(WebSocketSession connected, CloseStatus status) {
			session.compareAndSet(connected, null);
			if (!stopping.get()) scheduleReconnect();
		}
	}

	private record Client(String stockCode, SseEmitter emitter) {
		boolean accepts(RealtimeMarketEvent event) {
			return event.stockCode() == null || stockCode == null || stockCode.equals(event.stockCode());
		}
	}

	private record Frame(String type, String transactionId, String key) {
	}

	public record SubscriptionResult(String stockCode, String status, String rotatedOutStockCode, int activeCount) {
	}

	public record RealtimeMarketEvent(
		String type,
		String stockCode,
		String indexCode,
		BigDecimal currentValue,
		BigDecimal changeAmount,
		BigDecimal changeRate,
		BigDecimal openValue,
		BigDecimal highValue,
		BigDecimal lowValue,
		long volume,
		long executionVolume,
		Instant asOf,
		String status,
		String source
	) {
	}
}
