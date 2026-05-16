package com.TTT.Native

import com.TTT.Native.EnumMappings.OperationStatus

/**
 * Handle representing an async operation.
 *
 * Operations are polling-based — the caller polls with TPipe_AsyncHandle_poll()
 * until the status changes from PENDING to COMPLETE or FAILED.
 */
class OperationHandle(
    var status: OperationStatus,
    var resultHandle: Long,  // the result handle (ContentHandle, ListHandle, etc.)
    var errorMessage: String? = null
) {
    /**
     * Poll the operation status.
     * @return Current operation status
     */
    fun poll(): OperationStatus = status
    
    /**
     * Get the result handle if the operation completed.
     * @return The result handle ID, or 0 if operation is still pending or failed
     */
    fun getResult(): Long = resultHandle
    
    /**
     * Get the error message if the operation failed.
     * @return Error message or null if operation succeeded or is pending
     */
    fun getError(): String? = errorMessage
    
    /**
     * Cancel the operation.
     * @return true if cancellation was successful, false if already complete/failed
     */
    fun cancel(): Boolean {
        if (status == OperationStatus.COMPLETE || status == OperationStatus.FAILED) {
            return false // Already done
        }
        status = OperationStatus.FAILED
        errorMessage = "Cancelled by caller"
        return true
    }
    
    /**
     * Check if the operation has completed (successfully or with error).
     */
    fun isDone(): Boolean = status != OperationStatus.PENDING
    
    /**
     * Check if the operation completed successfully.
     */
    fun isSuccessful(): Boolean = status == OperationStatus.COMPLETE
    
    /**
     * Check if the operation failed.
     */
    fun isFailed(): Boolean = status == OperationStatus.FAILED
}