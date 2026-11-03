package com.namanbansal.forge.core

/** A failure whose [message] is already friendly and safe to show the user as-is. */
open class ToolException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Maps any throwable to a short message for the error state. Cancellation must be handled by the caller first. */
fun Throwable.friendlyMessage(): String = when (this) {
    is ToolException -> message ?: "Something went wrong."
    is OutOfMemoryError -> "This file is too large for your device's memory. Try a smaller file."
    is java.io.FileNotFoundException -> "The file could not be found. It may have been moved or deleted."
    is SecurityException -> "Forge no longer has permission to read this file. Please pick it again."
    is java.io.IOException -> "Could not read or write the file (${message ?: "I/O error"}). Check free storage and try again."
    else -> "Something went wrong. Please try again."
}
