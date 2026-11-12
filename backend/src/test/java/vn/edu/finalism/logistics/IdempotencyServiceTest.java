package vn.edu.finalism.logistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import vn.edu.finalism.logistics.platform.idempotency.IdempotencyClaim;
import vn.edu.finalism.logistics.platform.idempotency.IdempotencyService;
import vn.edu.finalism.logistics.shared.BusinessException;
import vn.edu.finalism.logistics.shared.ErrorCode;
import vn.edu.finalism.logistics.shared.IdempotencyKey;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.events.relay.enabled=false")
class IdempotencyServiceTest {

	private static final byte[] FINGERPRINT = {1, 2, 3};

	@Autowired
	IdempotencyService service;
	@Autowired
	JdbcClient jdbc;
	@Autowired
	PlatformTransactionManager transactionManager;

	private <T> T inTransaction(java.util.function.Supplier<T> work) {
		return new TransactionTemplate(transactionManager).execute(status -> work.get());
	}

	private static IdempotencyKey newKey() {
		return new IdempotencyKey("key-" + UUID.randomUUID());
	}

	@Test
	void firstCallIsFreshAndRetryAfterCommitReplaysTheStoredResult() {
		UUID actor = UUID.randomUUID();
		IdempotencyKey key = newKey();
		UUID createdId = UUID.randomUUID();

		IdempotencyClaim first = inTransaction(() -> {
			IdempotencyClaim claim = service.claim(actor, key, "CREATE_ORDER", FINGERPRINT);
			service.complete(actor, key, "ORDER", createdId, 0L);
			return claim;
		});
		IdempotencyClaim retry = inTransaction(() -> service.claim(actor, key, "CREATE_ORDER", FINGERPRINT));

		assertThat(first).isInstanceOf(IdempotencyClaim.Fresh.class);
		assertThat(retry).isInstanceOfSatisfying(IdempotencyClaim.Replay.class, replay -> {
			assertThat(replay.result().resultType()).isEqualTo("ORDER");
			assertThat(replay.result().resultRef()).isEqualTo(createdId);
			assertThat(replay.result().resultVersion()).isZero();
		});
	}

	@Test
	void sameKeyWithDifferentPayloadOrUseCaseIsRejected() {
		UUID actor = UUID.randomUUID();
		IdempotencyKey key = newKey();
		inTransaction(() -> {
			service.claim(actor, key, "CREATE_ORDER", FINGERPRINT);
			service.complete(actor, key, "ORDER", UUID.randomUUID(), 0L);
			return null;
		});

		assertThatThrownBy(() -> inTransaction(() -> service.claim(actor, key, "CREATE_ORDER", new byte[] {9})))
				.isInstanceOfSatisfying(BusinessException.class,
						e -> assertThat(e.code()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED));
		assertThatThrownBy(() -> inTransaction(() -> service.claim(actor, key, "CREATE_BATCH", FINGERPRINT)))
				.isInstanceOfSatisfying(BusinessException.class,
						e -> assertThat(e.code()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED));
	}

	@Test
	void sameKeyFromAnotherActorIsIndependent() {
		IdempotencyKey key = newKey();
		IdempotencyClaim a = inTransaction(() -> service.claim(UUID.randomUUID(), key, "CREATE_ORDER", FINGERPRINT));
		IdempotencyClaim b = inTransaction(() -> service.claim(UUID.randomUUID(), key, "CREATE_ORDER", FINGERPRINT));

		assertThat(a).isInstanceOf(IdempotencyClaim.Fresh.class);
		assertThat(b).isInstanceOf(IdempotencyClaim.Fresh.class);
	}

	@Test
	void rollbackReleasesTheKey() {
		UUID actor = UUID.randomUUID();
		IdempotencyKey key = newKey();

		assertThatThrownBy(() -> inTransaction(() -> {
			service.claim(actor, key, "CREATE_ORDER", FINGERPRINT);
			throw new IllegalStateException("business step failed");
		})).isInstanceOf(IllegalStateException.class);

		assertThat(inTransaction(() -> service.claim(actor, key, "CREATE_ORDER", FINGERPRINT)))
				.isInstanceOf(IdempotencyClaim.Fresh.class);
	}

	@Test
	void expiredKeyIsTreatedAsANewCommand() {
		UUID actor = UUID.randomUUID();
		IdempotencyKey key = newKey();
		inTransaction(() -> {
			service.claim(actor, key, "CREATE_ORDER", FINGERPRINT);
			service.complete(actor, key, "ORDER", UUID.randomUUID(), 0L);
			return null;
		});
		jdbc.sql("UPDATE platform.processed_commands SET expires_at = :past WHERE actor_id = :actor AND idempotency_key = :key")
				.param("past", OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1))
				.param("actor", actor).param("key", key.value()).update();

		assertThat(inTransaction(() -> service.claim(actor, key, "CREATE_ORDER", new byte[] {7})))
				.isInstanceOf(IdempotencyClaim.Fresh.class);
	}

	@Test
	void claimOutsideATransactionIsRefused() {
		assertThatThrownBy(() -> service.claim(UUID.randomUUID(), newKey(), "CREATE_ORDER", FINGERPRINT))
				.isInstanceOf(IllegalTransactionStateException.class);
	}

	@Test
	void twoParallelRequestsWithTheSameKeyRunTheCommandOnlyOnce() throws Exception {
		UUID actor = UUID.randomUUID();
		IdempotencyKey key = newKey();
		CountDownLatch firstHoldsTheKey = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<IdempotencyClaim> first = executor.submit(() -> inTransaction(() -> {
				IdempotencyClaim claim = service.claim(actor, key, "CREATE_ORDER", FINGERPRINT);
				firstHoldsTheKey.countDown();
				sleep(500);
				service.complete(actor, key, "ORDER", UUID.randomUUID(), 0L);
				return claim;
			}));
			assertThat(firstHoldsTheKey.await(5, TimeUnit.SECONDS)).isTrue();
			Future<IdempotencyClaim> second = executor.submit(() ->
					inTransaction(() -> service.claim(actor, key, "CREATE_ORDER", FINGERPRINT)));

			assertThat(first.get(10, TimeUnit.SECONDS)).isInstanceOf(IdempotencyClaim.Fresh.class);
			assertThat(second.get(10, TimeUnit.SECONDS)).isInstanceOf(IdempotencyClaim.Replay.class);
		}
		finally {
			executor.shutdownNow();
		}
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
