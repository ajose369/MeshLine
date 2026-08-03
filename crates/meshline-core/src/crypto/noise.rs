use chacha20poly1305::{
    aead::{Aead, KeyInit},
    ChaCha20Poly1305, Nonce,
};
use hkdf::Hkdf;
use rand::rngs::OsRng;
use sha2::Sha256;
use thiserror::Error;
use x25519_dalek::{EphemeralSecret, PublicKey as XPublicKey, StaticSecret};

#[derive(Error, Debug)]
pub enum CryptoError {
    #[error("Encryption failed")]
    EncryptionFailed,
    #[error("Decryption failed")]
    DecryptionFailed,
    #[error("Handshake state invalid")]
    InvalidHandshakeState,
    #[error("Key derivation failed")]
    KeyDerivationFailed,
}

pub struct SymmetricCipher;

impl SymmetricCipher {
    pub fn encrypt(key: &[u8; 32], nonce_counter: u64, plaintext: &[u8]) -> Result<Vec<u8>, CryptoError> {
        let cipher = ChaCha20Poly1305::new_from_slice(key).map_err(|_| CryptoError::EncryptionFailed)?;
        let mut nonce_bytes = [0u8; 12];
        nonce_bytes[4..12].copy_from_slice(&nonce_counter.to_le_bytes());
        let nonce = Nonce::from_slice(&nonce_bytes);
        cipher.encrypt(nonce, plaintext).map_err(|_| CryptoError::EncryptionFailed)
    }

    pub fn decrypt(key: &[u8; 32], nonce_counter: u64, ciphertext: &[u8]) -> Result<Vec<u8>, CryptoError> {
        let cipher = ChaCha20Poly1305::new_from_slice(key).map_err(|_| CryptoError::DecryptionFailed)?;
        let mut nonce_bytes = [0u8; 12];
        nonce_bytes[4..12].copy_from_slice(&nonce_counter.to_le_bytes());
        let nonce = Nonce::from_slice(&nonce_bytes);
        cipher.decrypt(nonce, ciphertext).map_err(|_| CryptoError::DecryptionFailed)
    }
}

pub struct NoiseHandshakeState {
    pub local_static_secret: StaticSecret,
    pub local_static_public: XPublicKey,
    pub remote_static_public: Option<XPublicKey>,
    pub shared_session_key: Option<[u8; 32]>,
}

impl NoiseHandshakeState {
    pub fn new() -> Self {
        let static_secret = StaticSecret::random_from_rng(OsRng);
        let static_public = XPublicKey::from(&static_secret);
        Self {
            local_static_secret: static_secret,
            local_static_public: static_public,
            remote_static_public: None,
            shared_session_key: None,
        }
    }

    pub fn initiate(&mut self) -> ([u8; 32], EphemeralSecret) {
        let ephemeral_secret = EphemeralSecret::random_from_rng(OsRng);
        let ephemeral_public = XPublicKey::from(&ephemeral_secret);
        (*ephemeral_public.as_bytes(), ephemeral_secret)
    }

    pub fn respond(
        &mut self,
        initiator_ephemeral_bytes: &[u8; 32],
    ) -> Result<([u8; 32], [u8; 32]), CryptoError> {
        let initiator_ephemeral = XPublicKey::from(*initiator_ephemeral_bytes);
        let responder_ephemeral_secret = EphemeralSecret::random_from_rng(OsRng);
        let responder_ephemeral_public = XPublicKey::from(&responder_ephemeral_secret);

        let shared_ee = responder_ephemeral_secret.diffie_hellman(&initiator_ephemeral);
        let shared_es = self.local_static_secret.diffie_hellman(&initiator_ephemeral);

        let hk = Hkdf::<Sha256>::new(None, shared_ee.as_bytes());
        let mut session_key = [0u8; 32];
        hk.expand(shared_es.as_bytes(), &mut session_key)
            .map_err(|_| CryptoError::KeyDerivationFailed)?;

        self.shared_session_key = Some(session_key);
        Ok((*responder_ephemeral_public.as_bytes(), session_key))
    }

    pub fn finalize(
        &mut self,
        initiator_ephemeral_secret: EphemeralSecret,
        responder_ephemeral_bytes: &[u8; 32],
    ) -> Result<[u8; 32], CryptoError> {
        let responder_ephemeral = XPublicKey::from(*responder_ephemeral_bytes);

        let shared_ee = initiator_ephemeral_secret.diffie_hellman(&responder_ephemeral);
        let shared_se = self.local_static_secret.diffie_hellman(&responder_ephemeral);

        let hk = Hkdf::<Sha256>::new(None, shared_ee.as_bytes());
        let mut session_key = [0u8; 32];
        hk.expand(shared_se.as_bytes(), &mut session_key)
            .map_err(|_| CryptoError::KeyDerivationFailed)?;

        self.shared_session_key = Some(session_key);
        Ok(session_key)
    }
}
