SET search_path TO vortex;

-- Fixed future fixtures keep the local catalog deterministic without a mutable demo clock.
INSERT INTO flights (
    flight_id, carrier_code, flight_number, service_date, departure_airport, destination_airport,
    status, check_in_opens_at, check_in_closes_at
) VALUES
    ('FL-204-2030-01-15', 'VX', '204', DATE '2030-01-15', 'JFK', 'LHR', 'SCHEDULED',
     TIMESTAMPTZ '2030-01-14 00:00:00+00', TIMESTAMPTZ '2030-01-15 12:00:00+00'),
    ('FL-205-2030-01-15', 'VX', '205', DATE '2030-01-15', 'JFK', 'LAX', 'BOARDING',
     TIMESTAMPTZ '2030-01-14 00:00:00+00', TIMESTAMPTZ '2030-01-15 15:00:00+00'),
    ('FL-999-2030-01-15', 'VX', '999', DATE '2030-01-15', 'JFK', 'LHR', 'CANCELLED',
     TIMESTAMPTZ '2030-01-14 00:00:00+00', TIMESTAMPTZ '2030-01-15 12:00:00+00')
ON CONFLICT (flight_id) DO NOTHING;

INSERT INTO equipment (equipment_id, equipment_type, status, configured_enabled, last_status_at, status_reason)
VALUES
    ('belt-west', 'CONVEYOR', 'AVAILABLE', true, TIMESTAMPTZ '2030-01-01 00:00:00+00', NULL),
    ('belt-east', 'CONVEYOR', 'AVAILABLE', true, TIMESTAMPTZ '2030-01-01 00:00:00+00', NULL),
    ('belt-blocked', 'CONVEYOR', 'BLOCKED', true, TIMESTAMPTZ '2030-01-01 00:00:00+00', 'DEMO_BLOCKED'),
    ('diverter-lhr', 'DIVERTER', 'AVAILABLE', true, TIMESTAMPTZ '2030-01-01 00:00:00+00', NULL),
    ('diverter-lax', 'DIVERTER', 'AVAILABLE', true, TIMESTAMPTZ '2030-01-01 00:00:00+00', NULL),
    ('scanner-west', 'SCANNER', 'AVAILABLE', true, TIMESTAMPTZ '2030-01-01 00:00:00+00', NULL),
    ('scanner-east', 'SCANNER', 'AVAILABLE', true, TIMESTAMPTZ '2030-01-01 00:00:00+00', NULL),
    ('scanner-lhr', 'SCANNER', 'AVAILABLE', true, TIMESTAMPTZ '2030-01-01 00:00:00+00', NULL),
    ('scanner-lax', 'SCANNER', 'AVAILABLE', true, TIMESTAMPTZ '2030-01-01 00:00:00+00', NULL)
ON CONFLICT (equipment_id) DO NOTHING;

INSERT INTO network_versions (
    network_version, status, airport_code, check_in_node_id, created_at, activated_at
) VALUES (
    'demo-v1', 'ACTIVE', 'JFK', 'checkin-main', TIMESTAMPTZ '2030-01-01 00:00:00+00', TIMESTAMPTZ '2030-01-01 00:00:00+00'
) ON CONFLICT (network_version) DO NOTHING;

INSERT INTO network_nodes (
    network_version, node_id, node_type, airport_destination_code, equipment_scanner_id, enabled, display_name
) VALUES
    ('demo-v1', 'checkin-main', 'CHECK_IN', NULL, NULL, true, 'Main check-in'),
    ('demo-v1', 'junction-west', 'JUNCTION', NULL, 'scanner-west', true, 'West junction'),
    ('demo-v1', 'junction-east', 'JUNCTION', NULL, 'scanner-east', true, 'East junction'),
    ('demo-v1', 'makeup-lhr', 'MAKEUP', 'LHR', 'scanner-lhr', true, 'LHR makeup'),
    ('demo-v1', 'makeup-lax', 'MAKEUP', 'LAX', 'scanner-lax', true, 'LAX makeup')
ON CONFLICT (network_version, node_id) DO NOTHING;

INSERT INTO network_edges (
    network_version, edge_id, from_node_id, to_node_id, equipment_id, cost, travel_time_ms, enabled
) VALUES
    ('demo-v1', 'edge-checkin-west', 'checkin-main', 'junction-west', 'belt-west', 4, 1200, true),
    ('demo-v1', 'edge-west-lhr', 'junction-west', 'makeup-lhr', 'diverter-lhr', 8, 1800, true),
    ('demo-v1', 'edge-checkin-east', 'checkin-main', 'junction-east', 'belt-east', 6, 1400, true),
    ('demo-v1', 'edge-east-lax', 'junction-east', 'makeup-lax', 'diverter-lax', 9, 1900, true),
    ('demo-v1', 'edge-checkin-blocked', 'checkin-main', 'junction-west', 'belt-blocked', 1, 500, true)
ON CONFLICT (network_version, edge_id) DO NOTHING;
