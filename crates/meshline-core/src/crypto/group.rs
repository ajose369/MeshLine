//! Private groups.
//!
//! A group is a symmetric key shared by its members. That key never touches the
//! mesh in the clear: it is delivered to each member individually inside an
//! established pairwise Noise session, so you cannot be added to a group by
//! someone you have not already completed a mutually authenticated handshake
//! with. Group membership therefore inherits the pairwise guarantees rather than
//! introducing a weaker path around them.
//!
//! # What a listener sees
//!
//! Group traffic is flooded like everything else, so it needs an address the
//! mesh can route and dedup on. Using the group id directly would publish a
//! stable, meaningful label for every group to anyone with a radio. Instead each
//! packet is addressed to a **group tag** derived from the group key, which is
//! an opaque 16 bytes to a non-member and changes whenever the group rekeys.
//!
//! Be clear about the limit: within one epoch the tag is constant, so an
//! observer can still count how much traffic a group carries and which node ids
//! transmit it. Hiding that would require trial-decrypting every packet against
//! every known group, which a phone relaying for a crowd cannot afford. Contents
//! and membership stay private; volume and timing do not.
//!
//! # Forward secrecy
//!
//! Pairwise sessions ratchet; a group key does not. A member's device that is
//! seized reveals the group traffic it could already read. [`GroupStore::rekey`]
//! is the answer: it mints a fresh key and epoch and redistributes it to the
//! remaining members, so a removed or compromised member is locked out of
//! everything sent afterwards.

use chacha20poly1305::{
    aead::{Aead, KeyInit, Payload},
    XChaCha20Poly1305, XNonce,
};
use hkdf::Hkdf;
use rand::Rng;
use serde::{Deserialize, Serialize};
use sha2::Sha256;
use zeroize::Zeroize;

use super::session::ReplayWindow;
use super::CryptoError;

/// Upper bound on members, which bounds invite packet size and the cost of a
/// rekey (one pairwise-encrypted packet per remaining member).
pub const MAX_GROUP_MEMBERS: usize = 64;

/// Upper bound on a group name, in bytes.
pub const MAX_GROUP_NAME_BYTES: usize = 96;

/// Framing on a group message: counter + XChaCha nonce + Poly1305 tag.
pub const GROUP_OVERHEAD: usize = 8 + 24 + 16;

/// Largest group message we will produce or accept.
pub const MAX_GROUP_MESSAGE: usize = 4096;

const TAG_INFO: &[u8] = b"meshline/group/tag/v1";
const MESSAGE_KEY_INFO: &[u8] = b"meshline/group/msg/v1";

/// A group as this device knows it.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Group {
    pub group_id: [u8; 16],
    /// Bumped on every rekey. Membership changes are epoch changes.
    pub epoch: u32,
    pub name: String,
    /// The shared secret. Never leaves the device except inside a pairwise
    /// Noise session.
    key: [u8; 32],
    /// The only node whose invites are accepted for this group.
    pub admin: [u8; 16],
    pub members: Vec<[u8; 16]>,
    pub created_at: u64,
    /// Our own send counter for this epoch.
    sending_counter: u64,
    /// Per-sender replay windows, so a replayed group message is rejected even
    /// after the routing dedup cache has evicted its message id.
    replay: Vec<([u8; 16], ReplayWindow)>,
}

impl Drop for Group {
    fn drop(&mut self) {
        self.key.zeroize();
    }
}

/// What travels inside a pairwise session to add someone to a group.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GroupInvite {
    pub group_id: [u8; 16],
    pub epoch: u32,
    pub name: String,
    pub key: [u8; 32],
    pub admin: [u8; 16],
    pub members: Vec<[u8; 16]>,
    pub created_at: u64,
}

impl Drop for GroupInvite {
    fn drop(&mut self) {
        self.key.zeroize();
    }
}

/// Bincode configuration for invites.
///
/// The limit matters: an invite arrives from the radio, and bincode sizes its
/// allocations from a length prefix in that untrusted input. Without a ceiling,
/// a 40-byte packet claiming a billion members would try to allocate for them.
fn invite_codec() -> impl bincode::Options {
    use bincode::Options;
    bincode::DefaultOptions::new()
        .with_fixint_encoding()
        .with_limit(crate::packet::schema::MAX_PAYLOAD_BYTES as u64)
}

/// Serializes an invite for transport inside a pairwise session.
pub fn encode_invite(invite: &GroupInvite) -> Result<Vec<u8>, CryptoError> {
    use bincode::Options;
    invite_codec()
        .serialize(invite)
        .map_err(|_| CryptoError::InvalidGroupInvite)
}

/// Parses an invite that came off the radio. Malformed input is an error, never
/// a partially populated group.
pub fn decode_invite(bytes: &[u8]) -> Result<GroupInvite, CryptoError> {
    use bincode::Options;
    invite_codec()
        .deserialize(bytes)
        .map_err(|_| CryptoError::InvalidGroupInvite)
}

impl GroupInvite {
    /// Rejects invites that are malformed before any of their content is used.
    fn validate(&self) -> Result<(), CryptoError> {
        if self.members.is_empty() || self.members.len() > MAX_GROUP_MEMBERS {
            return Err(CryptoError::InvalidGroupInvite);
        }
        if self.name.len() > MAX_GROUP_NAME_BYTES {
            return Err(CryptoError::InvalidGroupInvite);
        }
        if !self.members.contains(&self.admin) {
            return Err(CryptoError::InvalidGroupInvite);
        }
        Ok(())
    }
}

impl Group {
    pub fn member_count(&self) -> usize {
        self.members.len()
    }

    pub fn is_member(&self, node_id: &[u8; 16]) -> bool {
        self.members.contains(node_id)
    }

    pub fn is_admin(&self, node_id: &[u8; 16]) -> bool {
        &self.admin == node_id
    }

    /// The routing address for this group's current epoch.
    pub fn tag(&self) -> [u8; 16] {
        let mut tag = [0u8; 16];
        self.derive(TAG_INFO, &mut tag);
        tag
    }

    fn message_key(&self) -> [u8; 32] {
        let mut key = [0u8; 32];
        self.derive(MESSAGE_KEY_INFO, &mut key);
        key
    }

    /// HKDF over the group key, bound to the group id and epoch so that a
    /// rekeyed group shares no derived material with its previous epoch.
    fn derive(&self, info: &[u8], out: &mut [u8]) {
        let hk = Hkdf::<Sha256>::new(Some(&self.group_id), &self.key);
        let mut full_info = Vec::with_capacity(info.len() + 4);
        full_info.extend_from_slice(info);
        full_info.extend_from_slice(&self.epoch.to_le_bytes());
        hk.expand(&full_info, out)
            .expect("HKDF output length is a compile-time constant");
    }

    /// Binds a ciphertext to the group, epoch, sender, and counter. Rebinding
    /// somebody else's ciphertext under a different sender therefore fails, on
    /// top of the packet signature already making it unforgeable.
    fn aad(&self, sender_id: &[u8; 16], counter: u64) -> Vec<u8> {
        let mut aad = Vec::with_capacity(16 + 4 + 16 + 8);
        aad.extend_from_slice(&self.group_id);
        aad.extend_from_slice(&self.epoch.to_le_bytes());
        aad.extend_from_slice(sender_id);
        aad.extend_from_slice(&counter.to_le_bytes());
        aad
    }

    /// Encrypts a message for the group. `sender_id` must be this node.
    pub fn encrypt(
        &mut self,
        sender_id: &[u8; 16],
        plaintext: &[u8],
    ) -> Result<Vec<u8>, CryptoError> {
        if plaintext.len() + GROUP_OVERHEAD > MAX_GROUP_MESSAGE {
            return Err(CryptoError::MessageTooLarge);
        }
        if !self.is_member(sender_id) {
            return Err(CryptoError::NotAGroupMember);
        }
        let counter = self.sending_counter;
        if counter == u64::MAX {
            return Err(CryptoError::SessionExhausted);
        }

        let mut nonce = [0u8; 24];
        rand::thread_rng().fill(&mut nonce);

        let mut key = self.message_key();
        let cipher = XChaCha20Poly1305::new((&key).into());
        key.zeroize();

        let ciphertext = cipher
            .encrypt(
                XNonce::from_slice(&nonce),
                Payload {
                    msg: plaintext,
                    aad: &self.aad(sender_id, counter),
                },
            )
            .map_err(|_| CryptoError::Aead)?;

        self.sending_counter += 1;

        let mut out = Vec::with_capacity(GROUP_OVERHEAD + plaintext.len());
        out.extend_from_slice(&counter.to_le_bytes());
        out.extend_from_slice(&nonce);
        out.extend_from_slice(&ciphertext);
        Ok(out)
    }

    /// Decrypts a message sent to the group by `sender_id`.
    ///
    /// The sender is taken from the already-verified packet header, never from
    /// the ciphertext, so a member cannot relabel another member's message as
    /// their own.
    pub fn decrypt(
        &mut self,
        sender_id: &[u8; 16],
        framed: &[u8],
    ) -> Result<Vec<u8>, CryptoError> {
        if framed.len() < GROUP_OVERHEAD || framed.len() > MAX_GROUP_MESSAGE {
            return Err(CryptoError::MessageTooLarge);
        }
        // A node that is not in the group has no business being decrypted for,
        // even if it somehow learned the key.
        if !self.is_member(sender_id) {
            return Err(CryptoError::NotAGroupMember);
        }

        let mut counter_bytes = [0u8; 8];
        counter_bytes.copy_from_slice(&framed[..8]);
        let counter = u64::from_le_bytes(counter_bytes);

        self.replay_window(sender_id).check(counter)?;

        let mut key = self.message_key();
        let cipher = XChaCha20Poly1305::new((&key).into());
        key.zeroize();

        let plaintext = cipher
            .decrypt(
                XNonce::from_slice(&framed[8..32]),
                Payload {
                    msg: &framed[32..],
                    aad: &self.aad(sender_id, counter),
                },
            )
            .map_err(|_| CryptoError::Aead)?;

        self.replay_window_mut(sender_id).record(counter);
        Ok(plaintext)
    }

    fn replay_window(&self, sender_id: &[u8; 16]) -> ReplayWindow {
        self.replay
            .iter()
            .find(|(id, _)| id == sender_id)
            .map(|(_, w)| w.clone())
            .unwrap_or_default()
    }

    fn replay_window_mut(&mut self, sender_id: &[u8; 16]) -> &mut ReplayWindow {
        if let Some(idx) = self.replay.iter().position(|(id, _)| id == sender_id) {
            return &mut self.replay[idx].1;
        }
        self.replay.push((*sender_id, ReplayWindow::new()));
        let last = self.replay.len() - 1;
        &mut self.replay[last].1
    }

    /// The invite that hands this group's current epoch to one member.
    pub fn to_invite(&self) -> GroupInvite {
        GroupInvite {
            group_id: self.group_id,
            epoch: self.epoch,
            name: self.name.clone(),
            key: self.key,
            admin: self.admin,
            members: self.members.clone(),
            created_at: self.created_at,
        }
    }

    fn from_invite(invite: &GroupInvite) -> Self {
        Self {
            group_id: invite.group_id,
            epoch: invite.epoch,
            name: invite.name.clone(),
            key: invite.key,
            admin: invite.admin,
            members: invite.members.clone(),
            created_at: invite.created_at,
            sending_counter: 0,
            replay: Vec::new(),
        }
    }
}

/// What happened when an invite was applied.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum InviteOutcome {
    /// A group this device had never heard of.
    Joined,
    /// An existing group moved to a new epoch, usually after a membership change.
    Rekeyed,
    /// The new epoch no longer lists us, so the group was dropped locally.
    Removed,
    /// Nothing changed: an epoch we already have or have moved past.
    Stale,
}

/// Every group this device belongs to.
#[derive(Debug, Default, Serialize, Deserialize)]
pub struct GroupStore {
    groups: Vec<Group>,
}

impl GroupStore {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn len(&self) -> usize {
        self.groups.len()
    }

    pub fn is_empty(&self) -> bool {
        self.groups.is_empty()
    }

    pub fn all(&self) -> &[Group] {
        &self.groups
    }

    pub fn get(&self, group_id: &[u8; 16]) -> Option<&Group> {
        self.groups.iter().find(|g| &g.group_id == group_id)
    }

    pub fn get_mut(&mut self, group_id: &[u8; 16]) -> Option<&mut Group> {
        self.groups.iter_mut().find(|g| &g.group_id == group_id)
    }

    /// Finds the group a packet is addressed to. A non-member simply gets
    /// `None` and relays the packet without learning anything about it.
    pub fn by_tag(&self, tag: &[u8; 16]) -> Option<[u8; 16]> {
        self.groups
            .iter()
            .find(|g| &g.tag() == tag)
            .map(|g| g.group_id)
    }

    /// Creates a group with this device as its only member and admin.
    pub fn create(
        &mut self,
        creator: [u8; 16],
        name: &str,
        now: u64,
    ) -> Result<[u8; 16], CryptoError> {
        if name.len() > MAX_GROUP_NAME_BYTES {
            return Err(CryptoError::InvalidGroupInvite);
        }
        let mut rng = rand::thread_rng();
        let mut group_id = [0u8; 16];
        let mut key = [0u8; 32];
        rng.fill(&mut group_id);
        rng.fill(&mut key);

        self.groups.push(Group {
            group_id,
            epoch: 0,
            name: name.to_string(),
            key,
            admin: creator,
            members: vec![creator],
            created_at: now,
            sending_counter: 0,
            replay: Vec::new(),
        });
        Ok(group_id)
    }

    /// Adds a member. Admin only; the caller is responsible for then sending
    /// that member an invite over a pairwise session.
    pub fn add_member(
        &mut self,
        group_id: &[u8; 16],
        actor: &[u8; 16],
        new_member: [u8; 16],
    ) -> Result<(), CryptoError> {
        let group = self.get_mut(group_id).ok_or(CryptoError::UnknownGroup)?;
        if !group.is_admin(actor) {
            return Err(CryptoError::NotGroupAdmin);
        }
        if group.members.contains(&new_member) {
            return Ok(());
        }
        if group.members.len() >= MAX_GROUP_MEMBERS {
            return Err(CryptoError::GroupFull);
        }
        group.members.push(new_member);
        Ok(())
    }

    /// Removes a member and rekeys, so everything sent from now on is out of
    /// their reach. Returns the remaining members, who each need a fresh invite.
    pub fn remove_member(
        &mut self,
        group_id: &[u8; 16],
        actor: &[u8; 16],
        member: &[u8; 16],
    ) -> Result<Vec<[u8; 16]>, CryptoError> {
        {
            let group = self.get_mut(group_id).ok_or(CryptoError::UnknownGroup)?;
            if !group.is_admin(actor) {
                return Err(CryptoError::NotGroupAdmin);
            }
            if member == &group.admin {
                return Err(CryptoError::CannotRemoveAdmin);
            }
            group.members.retain(|m| m != member);
        }
        self.rekey(group_id, actor)
    }

    /// Mints a fresh key and epoch. Returns the members to redistribute to.
    ///
    /// Rekeying is the only thing that actually excludes someone who already
    /// holds the key, so removals and suspected device compromise both route
    /// through here.
    pub fn rekey(
        &mut self,
        group_id: &[u8; 16],
        actor: &[u8; 16],
    ) -> Result<Vec<[u8; 16]>, CryptoError> {
        let group = self.get_mut(group_id).ok_or(CryptoError::UnknownGroup)?;
        if !group.is_admin(actor) {
            return Err(CryptoError::NotGroupAdmin);
        }
        let next_epoch = group.epoch.checked_add(1).ok_or(CryptoError::GroupExhausted)?;

        let mut key = [0u8; 32];
        rand::thread_rng().fill(&mut key);
        group.key.zeroize();
        group.key = key;
        group.epoch = next_epoch;
        // Counters and replay history are per-epoch; a new key means the old
        // ones carry no meaning.
        group.sending_counter = 0;
        group.replay.clear();

        Ok(group.members.iter().copied().filter(|m| m != actor).collect())
    }

    /// Applies an invite that arrived over a verified pairwise session.
    ///
    /// `sender` is the authenticated node that sent it. An invite is only
    /// honoured when it comes from the group's own admin, which is what stops a
    /// member from silently rewriting membership or swapping in a key they
    /// control.
    pub fn apply_invite(
        &mut self,
        sender: &[u8; 16],
        us: &[u8; 16],
        invite: &GroupInvite,
    ) -> Result<InviteOutcome, CryptoError> {
        invite.validate()?;

        match self.get(&invite.group_id) {
            None => {
                if &invite.admin != sender {
                    return Err(CryptoError::NotGroupAdmin);
                }
                if !invite.members.contains(us) {
                    return Err(CryptoError::InvalidGroupInvite);
                }
                self.groups.push(Group::from_invite(invite));
                Ok(InviteOutcome::Joined)
            }
            Some(existing) => {
                // The admin is fixed at creation. Accepting an invite that
                // renames the admin would let anyone who is handed the key take
                // the group over.
                if &existing.admin != sender || invite.admin != existing.admin {
                    return Err(CryptoError::NotGroupAdmin);
                }
                if invite.epoch <= existing.epoch {
                    return Ok(InviteOutcome::Stale);
                }
                if !invite.members.contains(us) {
                    self.groups.retain(|g| g.group_id != invite.group_id);
                    return Ok(InviteOutcome::Removed);
                }
                let group = self
                    .get_mut(&invite.group_id)
                    .expect("group was present a moment ago");
                group.key.zeroize();
                *group = Group::from_invite(invite);
                Ok(InviteOutcome::Rekeyed)
            }
        }
    }

    /// Leaves a group locally. The remaining members are not told; the admin
    /// should rekey to actually revoke this device's access.
    pub fn leave(&mut self, group_id: &[u8; 16]) -> bool {
        let before = self.groups.len();
        self.groups.retain(|g| &g.group_id != group_id);
        self.groups.len() != before
    }

    pub fn clear(&mut self) {
        self.groups.clear();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const ALICE: [u8; 16] = [0xA1; 16];
    const BOB: [u8; 16] = [0xB0; 16];
    const CAROL: [u8; 16] = [0xC0; 16];
    const MALLORY: [u8; 16] = [0x3D; 16];

    /// Alice creates a group and adds Bob and Carol, as the app does.
    fn three_person_group() -> (GroupStore, GroupStore, GroupStore, [u8; 16]) {
        let mut alice = GroupStore::new();
        let gid = alice.create(ALICE, "affinity", 1000).unwrap();
        alice.add_member(&gid, &ALICE, BOB).unwrap();
        alice.add_member(&gid, &ALICE, CAROL).unwrap();

        let invite = alice.get(&gid).unwrap().to_invite();
        let mut bob = GroupStore::new();
        bob.apply_invite(&ALICE, &BOB, &invite).unwrap();
        let mut carol = GroupStore::new();
        carol.apply_invite(&ALICE, &CAROL, &invite).unwrap();

        (alice, bob, carol, gid)
    }

    #[test]
    fn members_can_read_each_others_messages() {
        let (mut alice, mut bob, mut carol, gid) = three_person_group();

        let ct = alice
            .get_mut(&gid)
            .unwrap()
            .encrypt(&ALICE, b"police forming a line on 5th")
            .unwrap();

        assert_eq!(
            bob.get_mut(&gid).unwrap().decrypt(&ALICE, &ct).unwrap(),
            b"police forming a line on 5th"
        );
        assert_eq!(
            carol.get_mut(&gid).unwrap().decrypt(&ALICE, &ct).unwrap(),
            b"police forming a line on 5th"
        );
    }

    #[test]
    fn the_ciphertext_does_not_leak_the_message() {
        let (mut alice, _b, _c, gid) = three_person_group();
        let secret = b"medic needed at the south barricade";
        let ct = alice.get_mut(&gid).unwrap().encrypt(&ALICE, secret).unwrap();
        assert!(!ct.windows(secret.len()).any(|w| w == secret));
    }

    #[test]
    fn a_non_member_with_the_wrong_key_learns_nothing() {
        let (mut alice, _b, _c, gid) = three_person_group();
        let ct = alice.get_mut(&gid).unwrap().encrypt(&ALICE, b"regroup").unwrap();

        // Mallory has her own group, and no way to match the tag or the key.
        let mut mallory = GroupStore::new();
        let other = mallory.create(MALLORY, "not the same group", 1000).unwrap();
        assert!(mallory.get_mut(&other).unwrap().decrypt(&ALICE, &ct).is_err());
    }

    #[test]
    fn the_group_tag_is_routable_by_members_and_opaque_to_others() {
        let (alice, bob, _c, gid) = three_person_group();
        let tag = alice.get(&gid).unwrap().tag();

        assert_eq!(bob.by_tag(&tag), Some(gid), "a member must recognise the tag");

        let mut mallory = GroupStore::new();
        mallory.create(MALLORY, "other", 1000).unwrap();
        assert!(mallory.by_tag(&tag).is_none(), "an outsider must not");
        assert_ne!(tag, gid, "the group id itself must never go on the wire");
    }

    #[test]
    fn a_removed_member_cannot_read_later_messages() {
        let (mut alice, mut bob, mut carol, gid) = three_person_group();

        let remaining = alice.remove_member(&gid, &ALICE, &CAROL).unwrap();
        assert_eq!(remaining, vec![BOB]);

        // Bob gets the new epoch; Carol does not.
        let invite = alice.get(&gid).unwrap().to_invite();
        assert_eq!(
            bob.apply_invite(&ALICE, &BOB, &invite).unwrap(),
            InviteOutcome::Rekeyed
        );

        let ct = alice
            .get_mut(&gid)
            .unwrap()
            .encrypt(&ALICE, b"new meeting point")
            .unwrap();

        assert_eq!(
            bob.get_mut(&gid).unwrap().decrypt(&ALICE, &ct).unwrap(),
            b"new meeting point"
        );
        assert!(
            carol.get_mut(&gid).unwrap().decrypt(&ALICE, &ct).is_err(),
            "a removed member must be locked out of the new epoch"
        );
    }

    #[test]
    fn a_rekey_changes_the_tag_so_old_traffic_is_unlinkable() {
        let (mut alice, _b, _c, gid) = three_person_group();
        let before = alice.get(&gid).unwrap().tag();
        alice.rekey(&gid, &ALICE).unwrap();
        assert_ne!(before, alice.get(&gid).unwrap().tag());
    }

    #[test]
    fn a_member_who_is_dropped_learns_it_and_deletes_the_group() {
        let (mut alice, _b, mut carol, gid) = three_person_group();
        alice.remove_member(&gid, &ALICE, &CAROL).unwrap();

        let invite = alice.get(&gid).unwrap().to_invite();
        assert_eq!(
            carol.apply_invite(&ALICE, &CAROL, &invite).unwrap(),
            InviteOutcome::Removed
        );
        assert!(carol.get(&gid).is_none());
    }

    #[test]
    fn only_the_admin_can_change_membership() {
        let (mut alice, mut bob, _c, gid) = three_person_group();

        assert!(matches!(
            bob.add_member(&gid, &BOB, MALLORY),
            Err(CryptoError::NotGroupAdmin)
        ));
        assert!(matches!(
            bob.remove_member(&gid, &BOB, &CAROL),
            Err(CryptoError::NotGroupAdmin)
        ));
        assert!(matches!(
            alice.remove_member(&gid, &BOB, &CAROL),
            Err(CryptoError::NotGroupAdmin)
        ));
    }

    #[test]
    fn a_member_cannot_hijack_the_group_by_forging_an_invite() {
        let (_a, mut bob, _c, gid) = three_person_group();

        // Bob mints his own key and claims to be admin of Alice's group.
        let mut bobs_forgery = GroupStore::new();
        bobs_forgery.create(BOB, "affinity", 1000).unwrap();
        let mut invite = bobs_forgery.all()[0].to_invite();
        invite.group_id = gid;
        invite.epoch = 99;
        invite.admin = BOB;
        invite.members = vec![BOB, CAROL, MALLORY];

        assert!(matches!(
            bob.apply_invite(&BOB, &BOB, &invite),
            Err(CryptoError::NotGroupAdmin)
        ));
    }

    #[test]
    fn an_invite_that_does_not_include_us_is_refused() {
        let mut alice = GroupStore::new();
        let gid = alice.create(ALICE, "affinity", 1000).unwrap();
        alice.add_member(&gid, &ALICE, BOB).unwrap();

        let invite = alice.get(&gid).unwrap().to_invite();
        let mut carol = GroupStore::new();
        assert!(carol.apply_invite(&ALICE, &CAROL, &invite).is_err());
    }

    #[test]
    fn a_replayed_old_epoch_invite_is_ignored() {
        let (mut alice, mut bob, _c, gid) = three_person_group();
        let old = alice.get(&gid).unwrap().to_invite();

        alice.rekey(&gid, &ALICE).unwrap();
        let new = alice.get(&gid).unwrap().to_invite();
        bob.apply_invite(&ALICE, &BOB, &new).unwrap();

        // Replaying the pre-rekey invite must not drag Bob back to a key a
        // removed member might still hold.
        assert_eq!(
            bob.apply_invite(&ALICE, &BOB, &old).unwrap(),
            InviteOutcome::Stale
        );
        assert_eq!(bob.get(&gid).unwrap().epoch, new.epoch);
    }

    #[test]
    fn a_replayed_group_message_is_rejected() {
        let (mut alice, mut bob, _c, gid) = three_person_group();
        let ct = alice.get_mut(&gid).unwrap().encrypt(&ALICE, b"go").unwrap();

        bob.get_mut(&gid).unwrap().decrypt(&ALICE, &ct).unwrap();
        assert!(matches!(
            bob.get_mut(&gid).unwrap().decrypt(&ALICE, &ct),
            Err(CryptoError::ReplayedNonce)
        ));
    }

    #[test]
    fn a_member_cannot_relabel_anothers_message_as_their_own() {
        let (mut alice, mut bob, _c, gid) = three_person_group();
        let ct = alice.get_mut(&gid).unwrap().encrypt(&ALICE, b"hold").unwrap();

        // The sender is bound into the AAD, so claiming a different author
        // breaks authentication rather than silently succeeding.
        assert!(matches!(
            bob.get_mut(&gid).unwrap().decrypt(&CAROL, &ct),
            Err(CryptoError::Aead)
        ));
    }

    #[test]
    fn messages_from_a_node_outside_the_group_are_refused() {
        let (_a, mut bob, _c, gid) = three_person_group();
        assert!(matches!(
            bob.get_mut(&gid).unwrap().decrypt(&MALLORY, &[0u8; 64]),
            Err(CryptoError::NotAGroupMember)
        ));
    }

    #[test]
    fn out_of_order_group_messages_still_decrypt() {
        let (mut alice, mut bob, _c, gid) = three_person_group();
        let a1 = alice.get_mut(&gid).unwrap().encrypt(&ALICE, b"one").unwrap();
        let a2 = alice.get_mut(&gid).unwrap().encrypt(&ALICE, b"two").unwrap();

        let group = bob.get_mut(&gid).unwrap();
        assert_eq!(group.decrypt(&ALICE, &a2).unwrap(), b"two");
        assert_eq!(group.decrypt(&ALICE, &a1).unwrap(), b"one");
    }

    #[test]
    fn two_senders_do_not_share_a_replay_window() {
        let (mut alice, mut bob, mut carol, gid) = three_person_group();
        // Both send their first message, so both use counter 0.
        let from_alice = alice.get_mut(&gid).unwrap().encrypt(&ALICE, b"a").unwrap();
        let from_bob = bob.get_mut(&gid).unwrap().encrypt(&BOB, b"b").unwrap();

        let group = carol.get_mut(&gid).unwrap();
        assert_eq!(group.decrypt(&ALICE, &from_alice).unwrap(), b"a");
        assert_eq!(
            group.decrypt(&BOB, &from_bob).unwrap(),
            b"b",
            "Bob's counter 0 must not be shadowed by Alice's"
        );
    }

    #[test]
    fn a_group_survives_serialization() {
        let (mut alice, bob, _c, gid) = three_person_group();
        let ct = alice.get_mut(&gid).unwrap().encrypt(&ALICE, b"persisted").unwrap();

        let bytes = bincode::serialize(&bob).unwrap();
        let mut restored: GroupStore = bincode::deserialize(&bytes).unwrap();

        assert_eq!(
            restored.get_mut(&gid).unwrap().decrypt(&ALICE, &ct).unwrap(),
            b"persisted"
        );
    }

    #[test]
    fn group_size_is_bounded() {
        let mut alice = GroupStore::new();
        let gid = alice.create(ALICE, "big", 1000).unwrap();
        for i in 0..(MAX_GROUP_MEMBERS - 1) {
            let mut member = [0u8; 16];
            member[0] = (i % 251) as u8;
            member[1] = (i / 251) as u8;
            alice.add_member(&gid, &ALICE, member).unwrap();
        }
        assert!(matches!(
            alice.add_member(&gid, &ALICE, MALLORY),
            Err(CryptoError::GroupFull)
        ));
    }

    #[test]
    fn the_admin_cannot_be_removed() {
        let (mut alice, _b, _c, gid) = three_person_group();
        assert!(matches!(
            alice.remove_member(&gid, &ALICE, &ALICE),
            Err(CryptoError::CannotRemoveAdmin)
        ));
    }

    #[test]
    fn oversized_names_are_refused() {
        let mut alice = GroupStore::new();
        let long = "x".repeat(MAX_GROUP_NAME_BYTES + 1);
        assert!(alice.create(ALICE, &long, 1000).is_err());
    }

    #[test]
    fn leaving_removes_the_key_from_this_device() {
        let (_a, mut bob, _c, gid) = three_person_group();
        assert!(bob.leave(&gid));
        assert!(bob.get(&gid).is_none());
        assert!(!bob.leave(&gid));
    }
}
