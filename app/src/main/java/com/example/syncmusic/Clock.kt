package com.example.syncmusic

import android.os.SystemClock

/** Monotonic clock shared by host and clients. Each phone has its own base; clients learn the offset. */
fun now(): Long = SystemClock.elapsedRealtime()
