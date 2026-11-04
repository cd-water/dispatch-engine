package com.ridematching.geoindex.redis;

import com.ridematching.domain.driver.DriverId;
import com.ridematching.geoindex.ClaimResult;
import com.ridematching.geoindex.RedisKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-0004 的验证章节，做成了可执行的形式。
 *
 * <p>这是整个项目赖以构建的那个测试。其他一切都可以争论；这个测试要么通过，要么 README 中
 * 的核心论断就是假的。
 */
@Testcontainers
@EnabledIf("containerRuntimeAvailable")
class DriverClaimIT {

    static boolean containerRuntimeAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private static final Duration TTL = Duration.ofSeconds(10);

    private RedisKeys keys;
    private StringRedisTemplate redis;
    private RedisDriverClaimStore claims;

    @BeforeEach
    void setUp() {
        LettuceConnectionFactory factory =
                new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();

        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();

        keys = new RedisKeys("test");
        claims = new RedisDriverClaimStore(redis, keys);
    }

    /** 按 location-service 会采用的方式，把一个司机以 AVAILABLE 状态放入索引。 */
    private DriverId availableDriver() {
        DriverId driver = DriverId.newId();
        redis.opsForHash().put(keys.driverState(driver), "status", "AVAILABLE");
        return driver;
    }

    @Nested
    class SingleClaim {

        @Test
        @DisplayName("claiming an available driver succeeds and returns a fence token")
        void claimSucceeds() {
            DriverId driver = availableDriver();

            ClaimResult result = claims.claim(driver, "offer-1", TTL);

            assertThat(result.won()).isTrue();
            assertThat(result.fence()).isPresent();
            assertThat(claims.statusOf(driver)).isEqualTo("RESERVED");
        }

        @Test
        @DisplayName("a second claim on the same driver is refused")
        void secondClaimIsRefused() {
            DriverId driver = availableDriver();
            claims.claim(driver, "offer-1", TTL);

            ClaimResult second = claims.claim(driver, "offer-2", TTL);

            assertThat(second.won()).isFalse();
            // 因状态而被拒绝，因为第一次认领已经把它翻转为 RESERVED。
            assertThat(second.reason()).isIn(ClaimResult.NOT_AVAILABLE, ClaimResult.ALREADY_CLAIMED);
        }

        @Test
        @DisplayName("an unknown driver is refused rather than claimed")
        void unknownDriverIsRefused() {
            // 不在索引中的司机已经下线，或已被清理器回收。认领他们只会派出一个无人接收的
            // 报价，并烧掉整个报价 TTL。
            ClaimResult result = claims.claim(DriverId.newId(), "offer-1", TTL);

            assertThat(result.won()).isFalse();
            assertThat(result.reason()).isEqualTo(ClaimResult.UNKNOWN_DRIVER);
        }

        @Test
        @DisplayName("releasing frees the driver for another worker")
        void releaseFreesDriver() {
            DriverId driver = availableDriver();
            claims.claim(driver, "offer-1", TTL);

            assertThat(claims.release(driver, "offer-1")).isTrue();
            assertThat(claims.statusOf(driver)).isEqualTo("AVAILABLE");
            assertThat(claims.claim(driver, "offer-2", TTL).won()).isTrue();
        }

        @Test
        @DisplayName("a stale holder cannot release someone else's claim")
        void releaseRequiresOwnership() {
            DriverId driver = availableDriver();
            claims.claim(driver, "offer-1", TTL);

            // 没有令牌校验，一个认领已过期的工作者就可能删除随后合法接手该司机的那个工作者
            // 所持有的认领。
            assertThat(claims.release(driver, "offer-SOMEONE-ELSE")).isFalse();
            assertThat(claims.statusOf(driver)).isEqualTo("RESERVED");
        }

        @Test
        @DisplayName("a claim expires on its own if the worker dies")
        void claimExpires() throws Exception {
            DriverId driver = availableDriver();
            claims.claim(driver, "offer-1", Duration.ofMillis(300));

            // TTL 是安全阀：一个在认领与派单之间死去的工作者，不能让司机被永远困住。
            Thread.sleep(500);

            assertThat(redis.hasKey(keys.claim(driver))).isFalse();
        }
    }

    @Nested
    class FencingTokens {

        @Test
        @DisplayName("fence tokens increase monotonically across claims")
        void tokensAreMonotonic() {
            DriverId a = availableDriver();
            DriverId b = availableDriver();
            DriverId c = availableDriver();

            long first = claims.claim(a, "o1", TTL).fenceToken();
            long second = claims.claim(b, "o2", TTL).fenceToken();
            long third = claims.claim(c, "o3", TTL).fenceToken();

            // 单调性正是让资源能够拒绝一个在暂停期间租约失效的工作者写入的依据——这是
            // 普通租约锁允许的失败模式。
            assertThat(first).isLessThan(second);
            assertThat(second).isLessThan(third);
        }

        @Test
        @DisplayName("a re-claim after release carries a strictly higher token")
        void reclaimAdvancesTheToken() {
            DriverId driver = availableDriver();
            long firstFence = claims.claim(driver, "o1", TTL).fenceToken();
            claims.release(driver, "o1");

            long secondFence = claims.claim(driver, "o2", TTL).fenceToken();

            assertThat(secondFence).isGreaterThan(firstFence);
            assertThat(claims.fenceOf(driver)).contains(secondFence);
        }
    }

    @Nested
    class Contention {

        @Test
        @DisplayName("200 threads racing for one driver: exactly one wins")
        void exactlyOneWinnerUnderContention() throws Exception {
            int threads = 200;
            DriverId driver = availableDriver();

            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch finished = new CountDownLatch(threads);
            AtomicInteger winners = new AtomicInteger();
            Set<Long> fences = ConcurrentHashMap.newKeySet();
            Set<String> lossReasons = ConcurrentHashMap.newKeySet();
            AtomicInteger errors = new AtomicInteger();

            for (int i = 0; i < threads; i++) {
                String token = "offer-" + i;
                Thread.ofVirtual().start(() -> {
                    try {
                        // 每个线程都先在这里停住，从而被同时放行。HGET 与 HSET 之间的窗口
                        // 只有微秒级；这才是真正瞄准这个窗口的方法。
                        startGate.await();
                        ClaimResult result = claims.claim(driver, token, TTL);
                        if (result.won()) {
                            winners.incrementAndGet();
                            fences.add(result.fenceToken());
                        } else {
                            lossReasons.add(result.reason());
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    } finally {
                        finished.countDown();
                    }
                });
            }

            startGate.countDown();
            assertThat(finished.await(30, TimeUnit.SECONDS)).isTrue();

            assertThat(winners.get())
                    .as("200 workers raced for one driver; exactly one must hold the claim")
                    .isEqualTo(1);
            assertThat(fences).hasSize(1);
            assertThat(errors.get()).isZero();
            assertThat(lossReasons)
                    .as("every loser must get a clean refusal, not an exception")
                    .isSubsetOf(ClaimResult.NOT_AVAILABLE, ClaimResult.ALREADY_CLAIMED);
            assertThat(claims.statusOf(driver)).isEqualTo("RESERVED");
        }

        @Test
        @DisplayName("100 drivers claimed concurrently: every claim succeeds independently")
        void independentDriversDoNotBlockEachOther() throws Exception {
            int count = 100;
            List<DriverId> drivers = java.util.stream.IntStream.range(0, count)
                    .mapToObj(i -> availableDriver())
                    .toList();

            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch finished = new CountDownLatch(count);
            AtomicInteger winners = new AtomicInteger();
            Set<Long> fences = ConcurrentHashMap.newKeySet();

            for (DriverId driver : drivers) {
                Thread.ofVirtual().start(() -> {
                    try {
                        startGate.await();
                        if (claims.claim(driver, "offer-" + UUID.randomUUID(), TTL).won()) {
                            winners.incrementAndGet();
                        }
                    } catch (Exception ignored) {
                        Thread.currentThread().interrupt();
                    } finally {
                        finished.countDown();
                    }
                });
            }

            startGate.countDown();
            assertThat(finished.await(30, TimeUnit.SECONDS)).isTrue();

            // 一个司机上的争用不得把不相关司机的认领串行化。
            assertThat(winners.get()).isEqualTo(count);
            drivers.forEach(d -> fences.add(claims.fenceOf(d).orElseThrow()));
            assertThat(fences)
                    .as("every driver must have a distinct fence token")
                    .hasSize(count);
        }

        @Test
        @DisplayName("after the winner releases, exactly one of the next wave wins")
        void releaseAllowsExactlyOneSuccessor() throws Exception {
            DriverId driver = availableDriver();
            claims.claim(driver, "winner", TTL);
            claims.release(driver, "winner");

            int threads = 50;
            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch finished = new CountDownLatch(threads);
            AtomicInteger winners = new AtomicInteger();

            for (int i = 0; i < threads; i++) {
                String token = "wave2-" + i;
                Thread.ofVirtual().start(() -> {
                    try {
                        startGate.await();
                        if (claims.claim(driver, token, TTL).won()) {
                            winners.incrementAndGet();
                        }
                    } catch (Exception ignored) {
                        Thread.currentThread().interrupt();
                    } finally {
                        finished.countDown();
                    }
                });
            }

            startGate.countDown();
            assertThat(finished.await(30, TimeUnit.SECONDS)).isTrue();

            assertThat(winners.get()).isEqualTo(1);
        }
    }
}
