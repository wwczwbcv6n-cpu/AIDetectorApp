import Foundation
import CryptoKit
import Security
import shared

/// CryptoKit / Security.framework implementation of the Kotlin `HpkeBridge`
/// interface (shared/src/iosMain/.../secure/HpkeBridge.kt).
///
/// Installed once at launch from `iOSApp.swift`:
///     Main_iosKt.installHpkeBridge(bridge: CryptoKitHpkeBridge())
///
/// NOT COMPILED IN THIS ENVIRONMENT: the Linux build host has no Xcode.
/// API names follow Apple's CryptoKit HPKE documentation (iOS 17+):
///   HPKE.Sender(recipientKey:ciphersuite:info:), seal(_:authenticating:),
///   exportSecret(context:outputByteCount:), encapsulatedKey.
/// Verify on the first macOS build. On iOS < 17 `seal` returns nil and the
/// Kotlin side fails closed ("requires iOS 17 or newer") — no plaintext path.
final class CryptoKitHpkeBridge: NSObject, HpkeBridge {

    // MARK: HPKE (RFC 9180) base mode, DHKEM(X25519, HKDF-SHA256) / HKDF-SHA256 / AES-256-GCM

    func seal(recipientPublicKey: Data, info: Data, aad: Data, plaintext: Data,
              exporterContext: Data, exportLength: Int32) -> HpkeBridgeSealed? {
        guard #available(iOS 17.0, *) else { return nil }
        guard recipientPublicKey.count == 32, exportLength > 0 else { return nil }
        do {
            let pkR = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: recipientPublicKey)
            let suite = HPKE.Ciphersuite(kem: .Curve25519_HKDF_SHA256, kdf: .HKDF_SHA256, aead: .AES_GCM_256)
            var sender = try HPKE.Sender(recipientKey: pkR, ciphersuite: suite, info: info)
            // First and only Seal (seq 0), then the exporter for the response key.
            let ciphertext = try sender.seal(plaintext, authenticating: aad)
            let exported = try sender.exportSecret(context: exporterContext, outputByteCount: Int(exportLength))
            let exportedData = exported.withUnsafeBytes { Data($0) }
            return HpkeBridgeSealed(enc: sender.encapsulatedKey, ciphertext: ciphertext, exportedKey: exportedData)
        } catch {
            return nil
        }
    }

    // MARK: AES-256-GCM (response envelope)

    func aesGcmOpen(key: Data, nonce: Data, aad: Data, ciphertextWithTag: Data) -> Data? {
        guard key.count == 32, nonce.count == 12, ciphertextWithTag.count >= 16 else { return nil }
        do {
            let split = ciphertextWithTag.count - 16
            let ct = ciphertextWithTag.prefix(split)
            let tag = ciphertextWithTag.suffix(16)
            let box = try AES.GCM.SealedBox(nonce: AES.GCM.Nonce(data: nonce), ciphertext: ct, tag: tag)
            return try AES.GCM.open(box, using: SymmetricKey(data: key), authenticating: aad)
        } catch {
            return nil
        }
    }

    // MARK: RS256 (attestation token signature) via Security.framework

    func rs256Verify(modulus: Data, exponent: Data, message: Data, signature: Data) -> Bool {
        let n = Self.stripLeadingZeros(modulus)
        guard n.count * 8 >= 2048 else { return false }
        let der = Self.rsaPublicKeyDER(modulus: n, exponent: Self.stripLeadingZeros(exponent))
        let attrs: [CFString: Any] = [
            kSecAttrKeyType: kSecAttrKeyTypeRSA,
            kSecAttrKeyClass: kSecAttrKeyClassPublic,
            kSecAttrKeySizeInBits: n.count * 8,
        ]
        var error: Unmanaged<CFError>?
        guard let key = SecKeyCreateWithData(der as CFData, attrs as CFDictionary, &error) else { return false }
        let algorithm: SecKeyAlgorithm = .rsaSignatureMessagePKCS1v15SHA256
        guard SecKeyIsAlgorithmSupported(key, .verify, algorithm) else { return false }
        return SecKeyVerifySignature(key, algorithm, message as CFData, signature as CFData, &error)
    }

    // MARK: DER helpers (PKCS#1 RSAPublicKey ::= SEQUENCE { modulus INTEGER, publicExponent INTEGER })

    private static func stripLeadingZeros(_ d: Data) -> Data {
        var bytes = [UInt8](d)
        while bytes.count > 1 && bytes[0] == 0 { bytes.removeFirst() }
        return Data(bytes)
    }

    private static func derLength(_ n: Int) -> Data {
        if n < 0x80 { return Data([UInt8(n)]) }
        var value = n
        var out: [UInt8] = []
        while value > 0 { out.insert(UInt8(value & 0xFF), at: 0); value >>= 8 }
        return Data([0x80 | UInt8(out.count)]) + Data(out)
    }

    private static func derInteger(_ unsigned: Data) -> Data {
        var bytes = [UInt8](unsigned)
        if let first = bytes.first, first & 0x80 != 0 { bytes.insert(0, at: 0) }
        return Data([0x02]) + derLength(bytes.count) + Data(bytes)
    }

    private static func rsaPublicKeyDER(modulus: Data, exponent: Data) -> Data {
        let body = derInteger(modulus) + derInteger(exponent)
        return Data([0x30]) + derLength(body.count) + body
    }
}
