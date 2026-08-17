//! Encryption of everything this device keeps between runs.
//!
//! Session keys, group keys, and verification decisions are exactly the material
//! that makes a seized phone dangerous to everyone else in the mesh. They are
//! never written as plaintext: the caller hands in a 32-byte vault key held in
//! platform-protected storage (the Android Keystore, on phones), and this module
//! seals the state under it.
//!
//! XChaCha20-Poly1305 with a random 192-bit nonce is used rather than the
//! counter-based construction the transport uses, because a vault is rewritten
//! repeatedly with no reliable counter to carry across process restarts, and a
//! random nonce that large will not repeat.

use chacha20poly1305::{
    aead::{Aead, KeyInit, Payload},
    XChaCha20Poly1305, XNonce,
};
use rand::Rng;

use super::CryptoError;

/// Format version, so a future layout change is rejected loudly rather than
/// misparsed into something that looks like valid key material.
pub const VAULT_VERSION: u8 = 1;

/// Bound on a sealed blob, to stop a corrupt or hostile file from sizing an
/// allocation. Generous enough for the maximum number of sessions and groups.
pub const MAX_VAULT_BYTES: usize = 1024 * 1024;

const VAULT_AAD: &[u8] = b"meshline/vault/v1";
const NONCE_LEN: usize = 24;

/// Seals `plaintext` under `key`. Output is `version || nonce || ciphertext`.
pub fn seal(key: &[u8; 32], plaintext: &[u8]) -> Result<Vec<u8>, CryptoError> {
    if plaintext.len() + NONCE_LEN + 17 > MAX_VAULT_BYTES {
        return Err(CryptoError::MessageTooLarge);
    }
    let mut nonce = [0u8; NONCE_LEN];
    rand::thread_rng().fill(&mut nonce);

    let cipher = XChaCha20Poly1305::new(key.into());
    let ciphertext = cipher
        .encrypt(
            XNonce::from_slice(&nonce),
            Payload {
                msg: plaintext,
                aad: VAULT_AAD,
            },
        )
        .map_err(|_| CryptoError::Aead)?;

    let mut out = Vec::with_capacity(1 + NONCE_LEN + ciphertext.len());
    out.push(VAULT_VERSION);
    out.extend_from_slice(&nonce);
    out.extend_from_slice(&ciphertext);
    Ok(out)
}

/// Opens a blob produced by [`seal`].
///
/// A wrong key, a truncated file, and a tampered file are all the same answer:
/// the state is unusable. Nothing partial is ever returned, because half a
/// session table is worse than none.
pub fn open(key: &[u8; 32], blob: &[u8]) -> Result<Vec<u8>, CryptoError> {
    if blob.len() > MAX_VAULT_BYTES {
        return Err(CryptoError::MessageTooLarge);
    }
    if blob.len() < 1 + NONCE_LEN + 16 {
        return Err(CryptoError::VaultCorrupt);
    }
    if blob[0] != VAULT_VERSION {
        return Err(CryptoError::VaultVersion);
    }

    let cipher = XChaCha20Poly1305::new(key.into());
    cipher
        .decrypt(
            XNonce::from_slice(&blob[1..1 + NONCE_LEN]),
            Payload {
                msg: &blob[1 + NONCE_LEN..],
                aad: VAULT_AAD,
            },
        )
        .map_err(|_| CryptoError::VaultCorrupt)
}

#[cfg(test)]
mod tests {
    use super::*;

    const KEY: [u8; 32] = [7u8; 32];

    #[test]
    fn round_trips() {
        let sealed = seal(&KEY, b"session keys and group keys").unwrap();
        assert_eq!(open(&KEY, &sealed).unwrap(), b"session keys and group keys");
    }

    #[test]
    fn the_plaintext_never_appears_in_the_blob() {
        let secret = b"group key material";
        let sealed = seal(&KEY, secret).unwrap();
        assert!(!sealed.windows(secret.len()).any(|w| w == secret));
    }

    #[test]
    fn a_wrong_key_cannot_open_it() {
        let sealed = seal(&KEY, b"state").unwrap();
        assert!(matches!(
            open(&[8u8; 32], &sealed),
            Err(CryptoError::VaultCorrupt)
        ));
    }

    #[test]
    fn tampering_is_detected() {
        let mut sealed = seal(&KEY, b"state").unwrap();
        let last = sealed.len() - 1;
        sealed[last] ^= 0x01;
        assert!(matches!(open(&KEY, &sealed), Err(CryptoError::VaultCorrupt)));
    }

    #[test]
    fn nonce_reuse_does_not_happen_across_writes() {
        // Two seals of identical plaintext must differ, or an observer with the
        // file could tell that nothing changed between saves.
        let a = seal(&KEY, b"same").unwrap();
        let b = seal(&KEY, b"same").unwrap();
        assert_ne!(a, b);
    }

    #[test]
    fn truncated_and_empty_input_is_refused_without_panicking() {
        for len in [0usize, 1, 8, 24, 40] {
            assert!(open(&KEY, &vec![VAULT_VERSION; len]).is_err());
        }
    }

    #[test]
    fn an_unknown_version_is_refused() {
        let mut sealed = seal(&KEY, b"state").unwrap();
        sealed[0] = 99;
        assert!(matches!(open(&KEY, &sealed), Err(CryptoError::VaultVersion)));
    }
}
