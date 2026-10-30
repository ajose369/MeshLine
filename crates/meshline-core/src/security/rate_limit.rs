use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::sync::Mutex;
use std::time::Instant;

pub struct TokenBucket {
    pub capacity: f64,
    pub tokens: f64,
    pub refill_rate_per_sec: f64,
    pub last_refill: Instant,
}

impl TokenBucket {
    pub fn new(capacity: f64, refill_rate_per_sec: f64) -> Self {
        Self {
            capacity,
            tokens: capacity,
            refill_rate_per_sec,
            last_refill: Instant::now(),
        }
    }

    pub fn try_consume(&mut self, tokens: f64) -> bool {
        let now = Instant::now();
        let elapsed = now.duration_since(self.last_refill).as_secs_f64();
        self.tokens = (self.tokens + elapsed * self.refill_rate_per_sec).min(self.capacity);
        self.last_refill = now;

        if self.tokens >= tokens {
            self.tokens -= tokens;
            true
        } else {
            false
        }
    }
}

pub struct MeshRateLimiter {
    node_buckets: Mutex<HashMap<[u8; 16], TokenBucket>>,
    capacity: f64,
    refill_rate: f64,
}

impl MeshRateLimiter {
    pub fn new(capacity: f64, refill_rate: f64) -> Self {
        Self {
            node_buckets: Mutex::new(HashMap::new()),
            capacity,
            refill_rate,
        }
    }

    pub fn allow_packet(&self, sender_id: &[u8; 16]) -> bool {
        let mut buckets = self.node_buckets.lock().unwrap();
        let bucket = buckets
            .entry(*sender_id)
            .or_insert_with(|| TokenBucket::new(self.capacity, self.refill_rate));
        bucket.try_consume(1.0)
    }
}

pub struct ProofOfWork;

impl ProofOfWork {
    /// Searches for a nonce meeting the difficulty target.
    ///
    /// Returns `None` rather than spinning forever if the search space is
    /// exhausted. This runs on the SOS path, so an unbounded loop here would
    /// hang the one screen that must never hang.
    pub fn solve(msg_bytes: &[u8], target_zero_bits: u8) -> Option<u32> {
        (0..=u32::MAX).find(|&nonce| Self::verify(msg_bytes, nonce, target_zero_bits))
    }

    pub fn verify(msg_bytes: &[u8], nonce: u32, target_zero_bits: u8) -> bool {
        let mut hasher = Sha256::new();
        hasher.update(msg_bytes);
        hasher.update(nonce.to_le_bytes());
        let result = hasher.finalize();

        let mut zero_bits = 0u8;
        for byte in result.iter() {
            if *byte == 0 {
                zero_bits += 8;
            } else {
                zero_bits += byte.leading_zeros() as u8;
                break;
            }
        }
        zero_bits >= target_zero_bits
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn solved_nonce_verifies() {
        let msg = b"trapped near the bridge";
        let nonce = ProofOfWork::solve(msg, 12).expect("12 bits is always solvable");
        assert!(ProofOfWork::verify(msg, nonce, 12));
    }

    #[test]
    fn proof_is_bound_to_its_message() {
        let nonce = ProofOfWork::solve(b"message one", 12).unwrap();
        assert!(!ProofOfWork::verify(b"message two", nonce, 12));
    }

    #[test]
    fn harder_target_rejects_an_easier_proof() {
        let msg = b"help";
        let nonce = ProofOfWork::solve(msg, 4).unwrap();
        // An 4-bit proof will almost never satisfy 24 bits.
        assert!(!ProofOfWork::verify(msg, nonce, 24));
    }

    #[test]
    fn bucket_allows_burst_then_throttles() {
        let mut bucket = TokenBucket::new(3.0, 0.0);
        assert!(bucket.try_consume(1.0));
        assert!(bucket.try_consume(1.0));
        assert!(bucket.try_consume(1.0));
        assert!(!bucket.try_consume(1.0), "capacity must be enforced");
    }

    #[test]
    fn bucket_refills_over_time() {
        let mut bucket = TokenBucket::new(1.0, 1000.0);
        assert!(bucket.try_consume(1.0));
        std::thread::sleep(std::time::Duration::from_millis(20));
        assert!(bucket.try_consume(1.0), "tokens must accrue with elapsed time");
    }

    #[test]
    fn rate_limiter_isolates_senders() {
        let limiter = MeshRateLimiter::new(2.0, 0.0);
        let noisy = [1u8; 16];
        let quiet = [2u8; 16];

        assert!(limiter.allow_packet(&noisy));
        assert!(limiter.allow_packet(&noisy));
        assert!(!limiter.allow_packet(&noisy), "noisy sender is throttled");

        assert!(
            limiter.allow_packet(&quiet),
            "one sender must not consume another's budget"
        );
    }
}
