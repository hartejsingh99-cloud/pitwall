package dev.pitwall.data

const val DATASET_VERSION = 1

expect suspend fun ensureF1dbFile(): String
