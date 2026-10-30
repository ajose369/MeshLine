//! Everything that keeps mesh traffic private.
//!
//! - [`noise`] establishes mutually authenticated pairwise sessions.
//! - [`session`] carries traffic on an established session, in a form that
//!   survives a restart.
//! - [`group`] layers private groups on top of those pairwise sessions.
//! - [`trust`] turns "the key is authentic" into "the human is who they claim",
//!   which cryptography alone cannot do.
//! - [`vault`] encrypts all of the above at rest.

pub mod group;
pub mod noise;
pub mod session;
pub mod trust;
pub mod vault;

use thiserror::Error;

/// Every way the crypto layer can refuse.
///
/// These are deliberately distinct: the UI has to be able to tell "we have no
/// session yet, offer a handshake" apart from "authentication failed, drop this
/// and say nothing", and neither may ever be handled by falling back to
/// plaintext.
#[derive(Error, Debug)]
pub enum CryptoError {
    #[error("Noise protocol failure: {0}")]
    Noise(String),
    #[error("Handshake is not finished")]
    HandshakeIncomplete,
    #[error("Handshake already finished")]
    HandshakeAlreadyComplete,
    #[error("Peer identity proof was missing or malformed")]
    MalformedIdentityProof,
    #[error("Peer identity proof failed verification")]
    UnauthenticatedPeer,
    #[error("Message exceeds maximum message size")]
    MessageTooLarge,
    #[error("Replayed or out-of-window nonce")]
    ReplayedNonce,
    #[error("No established session with peer")]
    NoSession,
    #[error("Authenticated decryption failed")]
    Aead,
    #[error("Session nonce space exhausted; re-handshake required")]
    SessionExhausted,
    #[error("No such group on this device")]
    UnknownGroup,
    #[error("Only the group admin may do that")]
    NotGroupAdmin,
    #[error("Node is not a member of this group")]
    NotAGroupMember,
    #[error("Group has reached its member limit")]
    GroupFull,
    #[error("The group admin cannot be removed")]
    CannotRemoveAdmin,
    #[error("Group epoch space exhausted")]
    GroupExhausted,
    #[error("Group invite was malformed or unauthorised")]
    InvalidGroupInvite,
    #[error("Encrypted state could not be opened")]
    VaultCorrupt,
    #[error("Encrypted state has an unsupported version")]
    VaultVersion,
}
