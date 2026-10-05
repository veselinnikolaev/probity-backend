package me.veselin.probity.simulation.async;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

/**
 * Shared Testcontainers Kafka broker for the async simulation slice.
 *
 * <p>One broker is started per test JVM and reused by every async test class
 * (the pattern already used by {@link me.veselin.probity.BaseIntegrationTest}
 * for Postgres and Redis).
 *
 * <p>Image: {@code apache/kafka:3.7.0} - the official KRaft image, and the image
 * {@code org.testcontainers.kafka.KafkaContainer} is built for. It is present in
 * the local Docker image cache, so the offline build never pulls.
 *
 * <p><strong>Note on consumer groups.</strong> {@code SimulationRequestedEventConsumer}
 * pins {@code groupId = "probity-simulation-worker"} in its {@code @KafkaListener}
 * annotation, so {@code spring.kafka.consumer.group-id} cannot be overridden per test
 * class - the annotation wins. Every class in this slice therefore shares one group on
 * one broker. That is harmless: tests delete their rows in {@code @AfterEach}, so a
 * record replayed by a later class finds no row, the claim matches nothing, and the
 * consumer acks and skips.
 */
public final class AsyncKafkaTestSupport {

    /** Source topic, matches {@code SimulationRequestedEventConsumer}. */
    public static final String SOURCE_TOPIC = "simulation-requested";

    /** Dead-letter topic, matches {@code KafkaConfig.simulationRequestedDltTopic()}. */
    public static final String DLT_TOPIC = "simulation-requested.DLT";

    /**
     * The one group this slice can use. Pinned by the listener annotation, so it is
     * also the group queried for committed offsets when diagnosing the ack behaviour.
     */
    public static final String CONSUMER_GROUP = "probity-simulation-worker";

    /**
     * A broker address nothing can be listening on.
     *
     * <p>Port 1 is privileged: binding it needs root, so on every platform this suite
     * supports the connection is refused immediately rather than hanging. Used by
     * {@link #registerDormantListener} so a dormant-listener context cannot reach any
     * cluster at all, instead of depending on the absence of a broker on 9092.
     */
    public static final String UNREACHABLE_BROKER = "localhost:1";

    public static final KafkaContainer KAFKA = startBroker();

    private AsyncKafkaTestSupport() {
    }

    private static KafkaContainer startBroker() {
        KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:3.7.0"))
                .withStartupTimeout(Duration.ofMinutes(2));
        kafka.start();
        return kafka;
    }

    /**
     * Points a test class at the shared broker.
     *
     * @param autoStartup whether the {@code simulation-requested} listener should actually
     *                    start. {@code false} keeps the consumer dormant.
     */
    public static void registerAsyncBroker(DynamicPropertyRegistry registry, boolean autoStartup) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.kafka.listener.auto-startup", () -> autoStartup);
        // The broker is brand new every JVM, so the group has no committed offsets and
        // 'earliest' means "everything this JVM published" - which removes the
        // assignment-vs-position-resolution race that 'latest' would leave open.
        registry.add("spring.kafka.consumer.auto-offset-reset", () -> "earliest");
    }

    /**
     * Keeps the listener dormant and the broker unreachable.
     *
     * <p>Needed by tests that exercise the request half of the async flow and then assert
     * on the untouched row — T1 above all, plus the repository-only T3b probe.
     *
     * <p>The requirement is: <em>no broker this context can reach, and no running
     * listener</em>. Both are established absolutely rather than by accident:
     *
     * <ul>
     *   <li>{@code spring.kafka.bootstrap-servers} is repointed at {@link #UNREACHABLE_BROKER}.
     *       The previous version left it at {@code application.yaml}'s
     *       {@code localhost:9092}, so the test silently depended on whatever the developer
     *       happened to be running — and, worse, on the <em>absence</em> of a consumer for
     *       it. The listener pins its {@code groupId} in the annotation, so every class in
     *       this slice shares one group; a container left running by a cached Spring context
     *       could claim a row mid-assertion if the two clusters were the same. A privileged
     *       port nothing binds removes the possibility instead of relying on it.</li>
     *   <li>Callers additionally mock {@code SimulationEventPublisher} with
     *       {@code @MockitoBean}, so nothing is sent and the request path never touches the
     *       network — which also removes the 60 s {@code max.block.ms} and metadata-timeout
     *       noise a dead broker produces.</li>
     * </ul>
     */
    public static void registerDormantListener(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", () -> UNREACHABLE_BROKER);
        registry.add("spring.kafka.listener.auto-startup", () -> false);
    }

    /**
     * Drains the dead-letter topic with a throwaway consumer and returns the raw
     * records, so callers can assert on exact payload bytes.
     *
     * <p>Polls until {@code minRecords} have been seen or {@code timeout} elapses.
     * A short result when nothing was published is a legitimate outcome the caller must
     * assert on, not an error here.
     */
    public static List<ConsumerRecord<String, byte[]>> drainDlt(Duration timeout, int minRecords) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "probity-dlt-probe-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");

        List<ConsumerRecord<String, byte[]>> collected = new ArrayList<>();
        try (Consumer<String, byte[]> probe = new KafkaConsumer<>(props)) {
            probe.subscribe(List.of(DLT_TOPIC));
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline && collected.size() < minRecords) {
                ConsumerRecords<String, byte[]> polled = probe.poll(Duration.ofMillis(500));
                polled.forEach(collected::add);
            }
        }
        return collected;
    }
}
