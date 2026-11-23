package com.ephemera.render.pacing;

import com.ephemera.core.config.EphemeraConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class CaptureOptimizationServiceTest {
	private EphemeraConfig cfg;

	@BeforeEach
	void setup() {
		cfg = new EphemeraConfig();
		CaptureOptimizationService.setConfigSupplierForTest(() -> cfg);
	}

	@AfterEach
	void tearDown() {
		CaptureOptimizationService.setProcessDetectorForTest(null);
		cfg.captureOptimizationMode = EphemeraConfig.CaptureOptimizationMode.OFF;
		CaptureOptimizationService.refreshNow();
		CaptureOptimizationService.setConfigSupplierForTest(null);
	}

	@Test
	void staleAutoScanCannotOverwriteManualOffOrNewerGeneration() throws Exception {
		cfg.captureOptimizationMode = EphemeraConfig.CaptureOptimizationMode.AUTO;
		CaptureOptimizationService.refreshNow();

		CountDownLatch scanStarted = new CountDownLatch(1);
		CountDownLatch allowScanFinish = new CountDownLatch(1);
		CaptureOptimizationService.setProcessDetectorForTest(() -> {
			scanStarted.countDown();
			try {
				assertTrue(allowScanFinish.await(5, TimeUnit.SECONDS));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return "OBS";
		});

		ExecutorService exec = Executors.newSingleThreadExecutor();
		try {
			Future<?> scanTask = exec.submit(CaptureOptimizationService::pollOnce);
			assertTrue(scanStarted.await(5, TimeUnit.SECONDS), "Detector should have started scanning");

			// User switches AUTO -> OFF while the worker is mid-scan
			cfg.captureOptimizationMode = EphemeraConfig.CaptureOptimizationMode.OFF;
			CaptureOptimizationService.refreshNow();
			assertFalse(CaptureOptimizationService.isCaptureActive());
			assertEquals("Off", CaptureOptimizationService.statusLabel());

			// Release stale scan returning "OBS"
			allowScanFinish.countDown();
			scanTask.get(5, TimeUnit.SECONDS);

			assertFalse(CaptureOptimizationService.isCaptureActive(), "Stale scan must not override OFF");
			assertEquals("Off", CaptureOptimizationService.statusLabel());
			assertFalse(CaptureOptimizationService.currentStateForTest().active());

			// Even switching back to AUTO afterwards must not expose the obsolete OBS state
			cfg.captureOptimizationMode = EphemeraConfig.CaptureOptimizationMode.AUTO;
			CaptureOptimizationService.refreshNow();
			assertFalse(CaptureOptimizationService.isCaptureActive());
			assertEquals("Standby (Auto)", CaptureOptimizationService.statusLabel());
		} finally {
			exec.shutdownNow();
		}
	}

	@Test
	void switchingFromManualOnToAutoClearsManualActiveStateUntilNextScan() {
		cfg.captureOptimizationMode = EphemeraConfig.CaptureOptimizationMode.ON;
		CaptureOptimizationService.refreshNow();
		assertTrue(CaptureOptimizationService.isCaptureActive());
		assertEquals("Active (Manual)", CaptureOptimizationService.statusLabel());

		cfg.captureOptimizationMode = EphemeraConfig.CaptureOptimizationMode.AUTO;
		CaptureOptimizationService.refreshNow();
		assertFalse(CaptureOptimizationService.isCaptureActive());
		assertEquals("Standby (Auto)", CaptureOptimizationService.statusLabel());

		CaptureOptimizationService.setProcessDetectorForTest(() -> "OBS");
		CaptureOptimizationService.pollOnce();
		assertTrue(CaptureOptimizationService.isCaptureActive());
		assertEquals("Active (Auto: OBS)", CaptureOptimizationService.statusLabel());
	}
}
