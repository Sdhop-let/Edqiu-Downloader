package com.ed.twitterdownloader.ui.screens

internal class MediaLibraryAutoScanCoordinator {
    var isScanning: Boolean = false
        private set

    fun requestScan(scan: (onComplete: () -> Unit) -> Unit): Boolean {
        if (isScanning) return false
        isScanning = true
        scan {
            isScanning = false
        }
        return true
    }
}
