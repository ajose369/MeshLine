use meshline_core::{BatteryPowerState, MeshNode};
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
            node.routing.set_battery_state(state);
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
            if let Ok(outcome) = node.process_incoming(&bytes) {
                reached.insert(curr_idx);
                if outcome.should_relay {
                    if let Ok(next_bytes) = outcome.packet.to_bytes() {
                        if let Some(neighbors) = self.adjacency.get(&curr_idx) {
                            for &n in neighbors {
                                if !reached.contains(&n) {
                                    queue.push_back((n, next_bytes.clone()));
                                }
                            }
                        }
                    }
                }
            }
        }

        (reached.len(), total_transmissions)
    }

    /// Counts how many nodes accept a tampered SOS. Any acceptance at all means
    /// the mesh will happily carry forged distress traffic, so the only passing
    /// result here is zero.
    fn run_forgery_simulation(&self, origin_idx: usize) -> (usize, usize) {
        let origin = &self.nodes[origin_idx];
        let packet = origin
            .create_public_sos("SOS: genuine distress call", 37.7749, -122.4194)
            .expect("Packet failed");
        let genuine = packet.to_bytes().expect("Serialization failed");

        let mut accepted = 0;
        let mut rejected = 0;

        for (idx, node) in self.nodes.iter().enumerate() {
            if idx == origin_idx {
                continue;
            }
            // Rewrite the distress text, leaving the signature untouched: the
            // classic "relay edits the message in flight" attack.
            let mut forged = genuine.clone();
            let tail = forged.len() - 70;
            forged[tail] ^= 0xFF;

            if node.process_incoming(&forged).is_ok() {
                accepted += 1;
            } else {
                rejected += 1;
            }
        }
        (accepted, rejected)
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

    // Scenario 2: LoRa Hardware Bridge across Partitioned Clusters.
    //
    // Cluster size is chosen so the bridge sits inside the TTL horizon. A line
    // topology costs one hop per node, and DEFAULT_TTL is 8, so a chain longer
    // than ~9 nodes cannot be traversed end to end no matter how healthy the
    // radios are. That is a property of the protocol, not a bug in the bridge.
    const CLUSTER: usize = 4;
    let total_2 = CLUSTER * 2;
    println!("\n--- SCENARIO 2: LoRa Hardware Bridge (5km Multi-Cluster Relay) ---");
    let (lora_net, bridge_a, bridge_b) =
        MeshSimulator::new_partitioned_with_lora_bridge(CLUSTER);
    println!(
        "Cluster A (Nodes 0..{}) <--- LoRa 915MHz Bridge (Node {} <-> Node {}) ---> Cluster B (Nodes {}..{})",
        CLUSTER - 1,
        bridge_a,
        bridge_b,
        CLUSTER,
        total_2 - 1
    );
    let (reached_2, tx_2) =
        lora_net.run_sos_simulation(0, "CRITICAL SOS: Flood level rising in Cluster A");
    println!("Total Nodes: {}", total_2);
    println!(
        "Nodes Reached: {} / {} ({:.1}%)",
        reached_2,
        total_2,
        (reached_2 as f32 / total_2 as f32) * 100.0
    );
    println!("Total Transmissions (Including LoRa Frame Bridge): {}", tx_2);
    println!(
        "Note: TTL {} caps a line topology at ~{} hops from the origin.",
        meshline_core::DEFAULT_TTL,
        meshline_core::DEFAULT_TTL - 1
    );

    // Scenario 3: Low-power duty cycling must still carry SOS to everyone.
    println!("\n--- SCENARIO 3: 49-Node Grid on Low-Power Duty Cycle ---");
    let mut low_power_net = MeshSimulator::new_grid(7);
    low_power_net.set_battery_state_all(BatteryPowerState::LowPowerSaver);
    let (reached_3, tx_3) = low_power_net.run_sos_simulation(24, "SOS! Battery saver active");
    println!("Nodes Reached: {} / 49 ({:.1}%)", reached_3, (reached_3 as f32 / 49.0) * 100.0);
    println!("Total Packet Transmissions: {}", tx_3);

    // Scenario 4: forged traffic must not propagate at all.
    println!("\n--- SCENARIO 4: Forged SOS Injection (49 nodes) ---");
    let adversarial_net = MeshSimulator::new_grid(7);
    let (accepted, rejected) = adversarial_net.run_forgery_simulation(24);
    println!("Forged packets accepted: {accepted}");
    println!("Forged packets rejected: {rejected}");

    // These are the properties the mesh is supposed to guarantee. Assert them
    // rather than printing a banner that says "PASSED" regardless of outcome.
    let mut failures = Vec::new();
    if reached_1 < 49 {
        failures.push(format!("grid SOS reached only {reached_1}/49 nodes"));
    }
    if reached_2 < total_2 {
        failures.push(format!(
            "LoRa-bridged SOS reached only {reached_2}/{total_2} nodes; the bridge did not carry it"
        ));
    }
    if reached_3 < 49 {
        failures.push(format!(
            "low-power SOS reached only {reached_3}/49 nodes; SOS must bypass dampening"
        ));
    }
    if accepted > 0 {
        failures.push(format!("{accepted} nodes accepted a forged SOS"));
    }

    println!("\n============================================================");
    if failures.is_empty() {
        println!("            ALL SIMULATION ASSERTIONS PASSED               ");
        println!("============================================================");
    } else {
        println!("                 SIMULATION FAILURES                       ");
        for f in &failures {
            println!("  - {f}");
        }
        println!("============================================================");
        std::process::exit(1);
    }
}
