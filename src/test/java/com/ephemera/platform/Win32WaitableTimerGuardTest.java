package com.ephemera.platform;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class Win32WaitableTimerGuardTest {
	@Test
	void shutdownWaitsForInFlightNativeWaitBeforeClosingAndRejectsNewWaits() throws Exception {
		CountDownLatch waitEntered = new CountDownLatch(1);
		CountDownLatch releaseWait = new CountDownLatch(1);
		AtomicBoolean handleClosed = new AtomicBoolean();
		AtomicBoolean bufferFreed = new AtomicBoolean();
		AtomicBoolean freedDuringWait = new AtomicBoolean();
		AtomicInteger waitCalls = new AtomicInteger();

		Win32DisplayRuntime.NativeTimerOps fakeOps = new Win32DisplayRuntime.NativeTimerOps() {
			@Override
			public long createHandle() {
				return 0x1234L;
			}

			@Override
			public LongBuffer allocDueTimeBuffer() {
				return ByteBuffer.allocateDirect(8).asLongBuffer();
			}

			@Override
			public long bufferAddress(LongBuffer buf) {
				return 0x5678L;
			}

			@Override
			public boolean waitNanos(long handle, long dueTimeAddr, long nanos) {
				waitCalls.incrementAndGet();
				waitEntered.countDown();
				try {
					assertTrue(releaseWait.await(5, TimeUnit.SECONDS));
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				if (handleClosed.get() || bufferFreed.get()) {
					freedDuringWait.set(true);
				}
				return true;
			}

			@Override
			public void closeHandle(long handle) {
				assertEquals(0x1234L, handle);
				assertFalse(handleClosed.getAndSet(true), "Handle must be closed at most once");
			}

			@Override
			public void freeDueTimeBuffer(LongBuffer buf) {
				assertFalse(bufferFreed.getAndSet(true), "Buffer must be freed at most once");
			}
		};

		Win32DisplayRuntime.WaitableTimerGuard guard = new Win32DisplayRuntime.WaitableTimerGuard(fakeOps);
		ExecutorService exec = Executors.newFixedThreadPool(2);
		try {
			Future<Boolean> renderWait = exec.submit(() -> {
				assertTrue(guard.init(Thread.currentThread()));
				return guard.tryParkNanos(1_000_000L, Thread.currentThread());
			});

			assertTrue(waitEntered.await(5, TimeUnit.SECONDS), "Render thread should enter native wait");

			Future<?> shutdownTask = exec.submit(() -> guard.shutdown(5000L));
			// Give shutdown time to hit the write lock while the render wait is still paused
			Thread.sleep(60L);
			assertFalse(handleClosed.get(), "Handle must not close while wait is in flight");
			assertFalse(bufferFreed.get(), "Buffer must not be freed while wait is in flight");
			assertFalse(guard.isActive(), "Guard must immediately report inactive once shutdown begins");

			// Any new wait attempt while shutdown is pending must fail immediately without calling waitNanos
			assertFalse(guard.tryParkNanos(1_000_000L, Thread.currentThread()));
			assertEquals(1, waitCalls.get());

			// Release in-flight wait and verify shutdown finishes and cleans up cleanly
			releaseWait.countDown();
			assertTrue(renderWait.get(5, TimeUnit.SECONDS));
			shutdownTask.get(5, TimeUnit.SECONDS);

			assertFalse(freedDuringWait.get(), "Resources must never be freed during an active wait");
			assertTrue(handleClosed.get(), "Handle must be closed after wait exits");
			assertTrue(bufferFreed.get(), "Buffer must be freed after wait exits");
			assertFalse(guard.init(Thread.currentThread()), "Guard must refuse re-init after shutdown");
		} finally {
			exec.shutdownNow();
		}
	}
}
