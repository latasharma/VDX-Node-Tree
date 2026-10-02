// UNVERIFIED: written without Xcode. Build and test on a device before relying on it.
import Contacts
import UIKit
import NodeTreeShared

/// Contacts via CNContactStore. Returns everyone matching the last spoken word;
/// the shared ContactMatcher decides who was meant. Access is requested the
/// first time a request needs it, not at launch.
final class IOSContacts: NSObject, ContactsSource {
    private let store = CNContactStore()
    private let queue = DispatchQueue(label: "app.nodetree.contacts")

    func search(name: String, callback: @escaping (ContactsResult) -> Void) {
        let status = CNContactStore.authorizationStatus(for: .contacts)
        if status == .notDetermined {
            store.requestAccess(for: .contacts) { granted, _ in
                if granted {
                    self.queue.async { callback(self.query(name)) }
                } else {
                    callback(Self.denied)
                }
            }
            return
        }
        if canRead(status) {
            queue.async { callback(self.query(name)) }
        } else {
            callback(Self.denied)
        }
    }

    private func canRead(_ status: CNAuthorizationStatus) -> Bool {
        if status == .authorized { return true }
        if #available(iOS 18.0, *), status == .limited { return true }
        return false
    }

    private static let denied = ContactsResult(contacts: [], permissionDenied: true, error: nil)

    private func query(_ name: String) -> ContactsResult {
        let words = name.split(separator: " ").map(String.init).filter { $0.lowercased() != "my" }
        guard let word = words.last else {
            return ContactsResult(contacts: [], permissionDenied: false, error: nil)
        }
        let keys: [CNKeyDescriptor] = [
            CNContactFormatter.descriptorForRequiredKeys(for: .fullName),
            CNContactPhoneNumbersKey as CNKeyDescriptor,
        ]
        do {
            let found = try store.unifiedContacts(matching: CNContact.predicateForContacts(matchingName: word), keysToFetch: keys)
            let contacts = found.map { contact -> Contact in
                let numbers = contact.phoneNumbers.map { labeled -> ContactNumber in
                    let label = labeled.label ?? ""
                    let isMobile = label == CNLabelPhoneNumberMobile || label == CNLabelPhoneNumberiPhone
                    return ContactNumber(label: isMobile ? "mobile" : "other", number: labeled.value.stringValue, isMobile: isMobile)
                }
                return Contact(displayName: CNContactFormatter.string(from: contact, style: .fullName) ?? "", numbers: numbers)
            }
            return ContactsResult(contacts: contacts, permissionDenied: false, error: nil)
        } catch {
            return ContactsResult(contacts: [], permissionDenied: false, error: String(describing: error))
        }
    }
}

/// Opens a tel: link. iOS shows its own "Call …?" prompt, so nothing is ever
/// dialed without the user tapping Call.
final class IOSDialer: NSObject, Dialer {
    func openDialer(number: String, callback: @escaping (DialOutcome) -> Void) {
        let digits = number.filter { "0123456789+*#".contains($0) }
        DispatchQueue.main.async {
            guard !digits.isEmpty, let url = URL(string: "tel:\(digits)") else {
                callback(DialOutcome(opened: false, error: "invalid number"))
                return
            }
            UIApplication.shared.open(url, options: [:]) { opened in
                callback(DialOutcome(opened: opened, error: opened ? nil : "could not open tel link"))
            }
        }
    }
}
