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
    pub fn solve(msg_bytes: &[u8], target_zero_bits: u8) -> u32 {
        let mut nonce = 0u32;
        loop {
            if Self::verify(msg_bytes, nonce, target_zero_bits) {
                return nonce;
            }
            nonce = nonce.wrapping_add(1);
        }
    }

    pub fn verify(msg_bytes: &[u8], nonce: u32, target_zero_bits: u8) -> bool {
        let mut hasher = Sha256::new();
        hasher.update(msg_bytes);
        hasher.update(&nonce.to_le_bytes());
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
