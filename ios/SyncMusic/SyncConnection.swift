import Foundation
import Network

/// TCP link to the Android host: newline-separated text messages (see SyncServer.kt).
/// Also keeps the host clock offset NTP-style (lowest round-trip sample wins).
final class SyncConnection {
    private let queue = DispatchQueue(label: "sync.connection")
    private var connection: NWConnection?
    private var buffer = Data()
    private var closed = false
    private var samples: [(rtt: Int64, offset: Int64)] = []
    private let lock = NSLock()
    private var _offset: Int64 = 0
    private var _bestRtt: Int64 = .max

    /// hostTime = localTime + offset
    var offset: Int64 { lock.lock(); defer { lock.unlock() }; return _offset }
    var bestRtt: Int64 { lock.lock(); defer { lock.unlock() }; return _bestRtt }
    /// The host's address as connected (used to download songs over HTTP).
    private(set) var hostAddress = ""
    private(set) var hostPort: UInt16 = 0

    /// All callbacks arrive on the main queue.
    var onLine: ((String) -> Void)?
    var onReady: (() -> Void)?
    var onClosed: ((String) -> Void)?

    private static func parameters() -> NWParameters {
        let params = NWParameters.tcp
        // IPv4 so the address can go straight into an http:// URL.
        if let ip = params.defaultProtocolStack.internetProtocol as? NWProtocolIP.Options { ip.version = .v4 }
        if let tcp = params.defaultProtocolStack.transportProtocol as? NWProtocolTCP.Options { tcp.noDelay = true }
        return params
    }

    /// Tries each endpoint in turn (a host can have several addresses).
    func connect(to endpoints: [NWEndpoint]) {
        queue.async { self.tryEndpoint(endpoints, at: 0) }
    }

    private func tryEndpoint(_ endpoints: [NWEndpoint], at index: Int) {
        guard !closed else { return }
        guard index < endpoints.count else { finish("ការភ្ជាប់បរាជ័យ"); return }
        let candidate = NWConnection(to: endpoints[index], using: Self.parameters())
        var settled = false
        let giveUp = DispatchWorkItem { [weak self] in
            guard let self, !settled else { return }
            settled = true
            candidate.cancel()
            self.tryEndpoint(endpoints, at: index + 1)
        }
        candidate.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready:
                guard !settled else { return }
                settled = true
                giveUp.cancel()
                self.connection = candidate
                if case let .hostPort(host, port)? = candidate.currentPath?.remoteEndpoint {
                    self.hostAddress = Self.describe(host)
                    self.hostPort = port.rawValue
                }
                self.receive()
                self.startPinging()
                DispatchQueue.main.async { self.onReady?() }
            case .failed, .waiting:
                if !settled {
                    settled = true
                    giveUp.cancel()
                    candidate.cancel()
                    self.tryEndpoint(endpoints, at: index + 1)
                } else if self.connection === candidate {
                    self.finish("Host បានផ្ដាច់")
                }
            default:
                break
            }
        }
        candidate.start(queue: queue)
        queue.asyncAfter(deadline: .now() + 2.5, execute: giveUp)
    }

    private static func describe(_ host: NWEndpoint.Host) -> String {
        let text: String
        switch host {
        case .ipv4(let address): text = "\(address)"
        case .ipv6(let address): text = "\(address)"
        case .name(let name, _): text = name
        @unknown default: text = "\(host)"
        }
        // Drop an interface suffix like "%en0".
        return text.split(separator: "%").first.map(String.init) ?? text
    }

    private func receive() {
        connection?.receive(minimumIncompleteLength: 1, maximumLength: 65_536) { [weak self] data, _, isComplete, error in
            guard let self else { return }
            if let data, !data.isEmpty {
                self.buffer.append(data)
                self.drainLines()
            }
            if isComplete || error != nil {
                self.finish("Host បានផ្ដាច់")
                return
            }
            self.receive()
        }
    }

    private func drainLines() {
        while let newline = buffer.firstIndex(of: 0x0A) {
            let lineData = buffer.subdata(in: buffer.startIndex..<newline)
            buffer.removeSubrange(buffer.startIndex...newline)
            guard var line = String(data: lineData, encoding: .utf8) else { continue }
            if line.hasSuffix("\r") { line.removeLast() }
            // Handled right here, not via the main queue, so the round-trip time stays exact.
            if line.hasPrefix("PONG ") {
                handlePong(line)
                continue
            }
            DispatchQueue.main.async { self.onLine?(line) }
        }
    }

    private func handlePong(_ line: String) {
        let p = line.split(separator: " ")
        guard p.count >= 3, let t0 = Int64(p[1]), let hostTime = Int64(p[2]) else { return }
        let t1 = now()
        samples.append((t1 - t0, hostTime - (t0 + t1) / 2))
        if samples.count > 30 { samples.removeFirst() }
        guard let best = samples.min(by: { $0.rtt < $1.rtt }) else { return }
        lock.lock()
        _offset = best.offset
        _bestRtt = best.rtt
        lock.unlock()
    }

    /// A burst at the start for a quick estimate, then every 2 s to follow drift (as on Android).
    private func startPinging() {
        var count = 0
        func tick() {
            guard !closed else { return }
            write("PING \(now())")
            count += 1
            queue.asyncAfter(deadline: .now() + (count < 15 ? 0.1 : 2.0)) { tick() }
        }
        tick()
    }

    func send(_ line: String) {
        queue.async { self.write(line) }
    }

    private func write(_ line: String) {
        connection?.send(content: Data((line + "\n").utf8), completion: .contentProcessed { _ in })
    }

    private func finish(_ reason: String) {
        guard !closed else { return }
        closed = true
        connection?.cancel()
        DispatchQueue.main.async { self.onClosed?(reason) }
    }

    /// Disconnect on purpose (no onClosed callback).
    func stop() {
        queue.async {
            self.closed = true
            self.connection?.cancel()
        }
    }
}
