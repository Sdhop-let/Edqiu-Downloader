package com.ed.edqiu.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaLibraryAutoScanCoordinatorTest {

    @Test
    fun blocksDuplicateScansUntilCurrentScanCompletes() {
        val coordinator = MediaLibraryAutoScanCoordinator()
        var scanCount = 0
        var finishScan: (() -> Unit)? = null

        assertTrue(
            coordinator.requestScan { onComplete ->
                scanCount++
                finishScan = onComplete
            }
        )
        assertFalse(
            coordinator.requestScan {
                scanCount++
            }
        )

        finishScan?.invoke()

        assertTrue(
            coordinator.requestScan { onComplete ->
                scanCount++
                onComplete()
            }
        )
        assertFalse(coordinator.isScanning)
        org.junit.Assert.assertEquals(2, scanCount)
    }
}
