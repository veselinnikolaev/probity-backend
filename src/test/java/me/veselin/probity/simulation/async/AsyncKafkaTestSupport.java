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
     * Keeps the listener dormant <em>without</em> repointing the broker.
     *
     * <p>Needed by tests that publish a real {@code SimulationRequestedEvent} and then
     * assert on the untouched row. If they used the shared broker, a listener container
     * left running by an earlier class's cached Spring context would be in the same
     * consumer group and would claim the row out from under the assertion. Leaving
     * {@code spring.kafka.bootstrap-servers} at {@code localhost:9092} - the same broker
     * the other 337 tests already depend on - puts the record on a different cluster,
     * where no container in this slice can reach it.
     */
    public static void registerDormantListener(DynamicPropertyRegistry registry) {
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
