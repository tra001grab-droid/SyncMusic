import Foundation
import Network

/// Finds Sync Music hosts on the Wi-Fi (the Android host registers "_syncmusic._tcp").
final class HostBrowser: ObservableObject {
    struct Found: Identifiable, Hashable {
        let id: String
        let name: String
        let endpoint: NWEndpoint
        /// The host's permanent id (TXT "id"), to find a remembered host again.
        let hostId: String?
    }

    @Published private(set) var hosts: [Found] = []
    var onResults: (([Found]) -> Void)?
    private var browser: NWBrowser?

    func start() {
        stop()
        let browser = NWBrowser(for: .bonjourWithTXTRecord(type: "_syncmusic._tcp", domain: nil), using: .tcp)
        browser.browseResultsChangedHandler = { [weak self] results, _ in
            let found = results.compactMap { result -> Found? in
                guard case let .service(name, _, _, _) = result.endpoint else { return nil }
                var hostId: String?
                if case let .bonjour(txt) = result.metadata { hostId = txt["id"] }
                return Found(id: "\(result.endpoint)", name: name, endpoint: result.endpoint, hostId: hostId)
            }
            DispatchQueue.main.async {
                self?.hosts = found
                self?.onResults?(found)
            }
        }
        browser.start(queue: .main)
        self.browser = browser
    }

    func stop() {
        browser?.cancel()
        browser = nil
        hosts = []
    }
}
