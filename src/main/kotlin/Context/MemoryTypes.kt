package com.TTT.Context

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable

/**
 * Error categories that can be returned by the remote memory transport.
 */
@Serializable
enum class MemoryErrorType
{
    auth,
    badRequest,
    notFound,
    conflict,
    transport,
    serialization,
    server,
    unknown
}

/**
 * Structured error payload returned by the remote memory server.
 *
 * @param errorType Error category for the failed operation.
 * @param message Human-readable failure description.
 */
@Serializable
data class MemoryErrorResponse(
    var errorType: MemoryErrorType = MemoryErrorType.unknown,
    var message: String = ""
)

/**
 * Typed result for remote-memory operations.
 *
 * @param T Successful value type.
 */
sealed class MemoryOperationResult<out T>
{
    /**
     * Successful remote-memory response.
     *
     * @param value Parsed success value.
     */
    data class Success<T>(val value: T) : MemoryOperationResult<T>()

    /**
     * Failed remote-memory response.
     *
     * @param statusCode HTTP status returned by the server when available.
     * @param error Structured failure payload.
     */
    data class Failure(
        val statusCode: HttpStatusCode?,
        val error: MemoryErrorResponse
    ) : MemoryOperationResult<Nothing>()
}

/**
 * Exception thrown when an internal remote-memory caller receives a typed failure that it cannot treat as a normal
 * not-found or unlocked state.
 *
 * @param operation Short description of the failed operation.
 * @param failure Typed failure returned by the remote-memory transport.
 */
class MemoryRemoteException(
    private val operation: String,
    val failure: MemoryOperationResult.Failure
) : IllegalStateException(
    "Remote memory operation '$operation' failed with ${failure.error.errorType}: ${failure.error.message}"
)

/**
 * Require that the result is a [MemoryOperationResult.Success] and return its value.
 *
 * @param operation Short description of the failed operation for exception reporting.
 * @return The successful value.
 * @throws MemoryRemoteException when the result is a typed failure.
 */
fun <T> MemoryOperationResult<T>.requireValue(operation: String): T
{
    return when(this)
    {
        is MemoryOperationResult.Success -> value
        is MemoryOperationResult.Failure -> throw MemoryRemoteException(operation, this)
    }
}

/**
 * Require that the result completed successfully.
 *
 * @param operation Short description of the failed operation for exception reporting.
 * @throws MemoryRemoteException when the result is a typed failure.
 */
fun MemoryOperationResult<Unit>.requireSuccess(operation: String)
{
    when(this)
    {
        is MemoryOperationResult.Success -> return
        is MemoryOperationResult.Failure -> throw MemoryRemoteException(operation, this)
    }
}
