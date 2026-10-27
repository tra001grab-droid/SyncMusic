import Foundation

/// Monotonic clock in ms. Like SystemClock.elapsedRealtime() on Android: each phone has its own
/// base, and the connection learns the offset to the host's clock.
func now() -> Int64 {
    Int64(DispatchTime.now().uptimeNanoseconds / 1_000_000)
}
