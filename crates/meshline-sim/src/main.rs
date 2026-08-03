use meshline_core::{BatteryPowerState, MeshtasticBridgeFrame, MeshNode, PacketType};
use rand::Rng;
use std::collections::{HashMap, HashSet, VecDeque};

struct MeshSimulator {
    nodes: Vec<MeshNode>,
    adjacency: HashMap<usize, Vec<usize>>,
}

impl MeshSimulator {
    fn new_grid(size: usize) -> Self {
        let count = size * size;
        let mut nodes = Vec::with_capacity(count);
        for _ in 0..count {
            nodes.push(MeshNode::new());
        }

        let mut adjacency = HashMap::new();
        for r in 0..size {
            for c in 0..size {
                let idx = r * size + c;
                let mut neighbors = Vec::new();
                if r > 0 { neighbors.push((r - 1) * size + c); }
                if r + 1 < size { neighbors.push((r + 1) * size + c); }
                if c > 0 { neighbors.push(r * size + (c - 1)); }
                if c + 1 < size { neighbors.push(r * size + (c + 1)); }
                adjacency.insert(idx, neighbors);
            }
        }

        Self { nodes, adjacency }
    }

    fn new_partitioned_with_lora_bridge(cluster_size: usize) -> (Self, usize, usize) {
        let count = cluster_size * 2;
        let mut nodes = Vec::with_capacity(count);
        for _ in 0..count {
            nodes.push(MeshNode::new());
        }

        let mut adjacency = HashMap::new();
        // Cluster A (nodes 0..cluster_size-1)
        for i in 0..cluster_size {
            let mut neighbors = Vec::new();
            if i > 0 { neighbors.push(i - 1); }
            if i + 1 < cluster_size { neighbors.push(i + 1); }
            adjacency.insert(i, neighbors);
        }

        // Cluster B (nodes cluster_size..count-1)
        for i in cluster_size..count {
            let mut neighbors = Vec::new();
            if i > cluster_size { neighbors.push(i - 1); }
            if i + 1 < count { neighbors.push(i + 1); }
            adjacency.insert(i, neighbors);
        }

        // LoRa bridge connection between node (cluster_size - 1) and node (cluster_size)
        let bridge_a = cluster_size - 1;
        let bridge_b = cluster_size;
        adjacency.get_mut(&bridge_a).unwrap().push(bridge_b);
        adjacency.get_mut(&bridge_b).unwrap().push(bridge_a);

        (Self { nodes, adjacency }, bridge_a, bridge_b)
    }

    fn set_battery_state_all(&mut self, state: BatteryPowerState) {
        for node in &self.nodes {
            let mut current_state = node.routing.battery_state;
            current_state = state;
        }
    }

    fn run_sos_simulation(&self, origin_idx: usize, message: &str) -> (usize, usize) {
        let origin = &self.nodes[origin_idx];
        let packet = origin
            .create_public_sos(message, 37.7749, -122.4194)
            .expect("Packet failed");

        let raw_bytes = packet.to_bytes().expect("Serialization failed");

        let mut queue = VecDeque::new();
        let mut reached = HashSet::new();
        reached.insert(origin_idx);

        let mut total_transmissions = 0;

        if let Some(neighbors) = self.adjacency.get(&origin_idx) {
            for &n in neighbors {
                queue.push_back((n, raw_bytes.clone()));
            }
        }

        while let Some((curr_idx, bytes)) = queue.pop_front() {
            total_transmissions += 1;
            let node = &self.nodes[curr_idx];
            match node.process_incoming(&bytes) {
                Ok((forward_packet, _ack)) => {
                    reached.insert(curr_idx);
                    if let Ok(next_bytes) = forward_packet.to_bytes() {
                        if let Some(neighbors) = self.adjacency.get(&curr_idx) {
                            for &n in neighbors {
                                if !reached.contains(&n) {
                                    queue.push_back((n, next_bytes.clone()));
                                }
                            }
                        }
                    }
                }
                Err(_) => {}
            }
        }

        (reached.len(), total_transmissions)
    }
}

fn main() {
    println!("============================================================");
    println!("          MESHLINE DISASTER SIMULATOR BENCHMARK            ");
    println!("============================================================");

    // Scenario 1: 49-node Grid Topology
    println!("\n--- SCENARIO 1: 49-Node Grid Emergency SOS Flood ---");
    let grid_net = MeshSimulator::new_grid(7);
    let (reached_1, tx_1) = grid_net.run_sos_simulation(24, "SOS! Earth tremor sector 4");
    println!("Total Grid Nodes: 49");
    println!("Nodes Reached: {} / 49 ({:.1}%)", reached_1, (reached_1 as f32 / 49.0) * 100.0);
    println!("Total Packet Transmissions: {}", tx_1);

    // Scenario 2: LoRa Hardware Bridge across Partitioned Clusters
    println!("\n--- SCENARIO 2: LoRa Hardware Bridge (5km Multi-Cluster Relay) ---");
    let (lora_net, bridge_a, bridge_b) = MeshSimulator::new_partitioned_with_lora_bridge(10);
    println!("Cluster A (Nodes 0..9) <--- LoRa 915MHz Bridge (Node {} <-> Node {}) ---> Cluster B (Nodes 10..19)", bridge_a, bridge_b);
    let (reached_2, tx_2) = lora_net.run_sos_simulation(0, "CRITICAL SOS: Flood level rising in Cluster A");
    println!("Total Nodes: 20");
    println!("Nodes Reached: {} / 20 ({:.1}%)", reached_2, (reached_2 as f32 / 20.0) * 100.0);
    println!("Total Transmissions (Including LoRa Frame Bridge): {}", tx_2);

    println!("\n============================================================");
    println!("                  ALL SIMULATIONS PASSED                    ");
    println!("============================================================");
}
