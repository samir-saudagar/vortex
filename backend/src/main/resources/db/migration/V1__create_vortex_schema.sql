CREATE SCHEMA IF NOT EXISTS vortex;
SET search_path TO vortex;

CREATE TABLE flights (
    flight_id text PRIMARY KEY,
    carrier_code text NOT NULL,
    flight_number text NOT NULL,
    service_date date NOT NULL,
    departure_airport text NOT NULL,
    destination_airport text NOT NULL,
    status text NOT NULL,
    check_in_opens_at timestamptz,
    check_in_closes_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_flights_carrier_number_date UNIQUE (carrier_code, flight_number, service_date),
    CONSTRAINT ck_flights_status CHECK (status IN ('SCHEDULED', 'BOARDING', 'DEPARTED', 'CANCELLED')),
    CONSTRAINT ck_flights_window CHECK (check_in_opens_at IS NULL OR check_in_opens_at < check_in_closes_at)
);
CREATE INDEX ix_flights_date_departure_status ON flights (service_date, departure_airport, status);

CREATE TABLE equipment (
    equipment_id text PRIMARY KEY,
    equipment_type text NOT NULL,
    status text NOT NULL,
    configured_enabled boolean NOT NULL DEFAULT true,
    last_status_at timestamptz NOT NULL,
    last_report_id uuid UNIQUE,
    status_reason text,
    event_sequence bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_equipment_type CHECK (equipment_type IN ('CONVEYOR', 'DIVERTER', 'SCANNER')),
    CONSTRAINT ck_equipment_status CHECK (status IN ('AVAILABLE', 'BLOCKED', 'OFFLINE', 'MAINTENANCE')),
    CONSTRAINT ck_equipment_event_sequence CHECK (event_sequence >= 0)
);
CREATE INDEX ix_equipment_status_enabled ON equipment (status, configured_enabled);

CREATE TABLE network_versions (
    network_version text PRIMARY KEY,
    status text NOT NULL,
    airport_code text NOT NULL,
    check_in_node_id text NOT NULL,
    created_at timestamptz NOT NULL,
    activated_at timestamptz,
    CONSTRAINT ck_network_versions_status CHECK (status IN ('DRAFT', 'ACTIVE', 'RETIRED'))
);
CREATE UNIQUE INDEX uq_network_versions_one_active ON network_versions ((status)) WHERE status = 'ACTIVE';

CREATE TABLE network_nodes (
    network_version text NOT NULL REFERENCES network_versions(network_version),
    node_id text NOT NULL,
    node_type text NOT NULL,
    airport_destination_code text,
    equipment_scanner_id text REFERENCES equipment(equipment_id),
    enabled boolean NOT NULL DEFAULT true,
    display_name text NOT NULL,
    CONSTRAINT pk_network_nodes PRIMARY KEY (network_version, node_id),
    CONSTRAINT ck_network_nodes_type CHECK (node_type IN ('CHECK_IN', 'JUNCTION', 'SCANNER', 'DIVERTER', 'MAKEUP')),
    CONSTRAINT ck_makeup_nodes_have_scanner CHECK (node_type <> 'MAKEUP' OR equipment_scanner_id IS NOT NULL),
    CONSTRAINT ck_makeup_nodes_have_destination CHECK (node_type <> 'MAKEUP' OR airport_destination_code IS NOT NULL)
);
CREATE INDEX ix_network_nodes_version_destination ON network_nodes (network_version, airport_destination_code);
CREATE UNIQUE INDEX uq_network_nodes_makeup_destination ON network_nodes (network_version, airport_destination_code)
    WHERE node_type = 'MAKEUP' AND airport_destination_code IS NOT NULL;

ALTER TABLE network_versions
    ADD CONSTRAINT fk_network_versions_check_in_node
    FOREIGN KEY (network_version, check_in_node_id)
    REFERENCES network_nodes (network_version, node_id)
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE network_edges (
    network_version text NOT NULL,
    edge_id text NOT NULL,
    from_node_id text NOT NULL,
    to_node_id text NOT NULL,
    equipment_id text NOT NULL REFERENCES equipment(equipment_id),
    cost integer NOT NULL,
    travel_time_ms integer,
    enabled boolean NOT NULL DEFAULT true,
    CONSTRAINT pk_network_edges PRIMARY KEY (network_version, edge_id),
    CONSTRAINT fk_network_edges_from_node FOREIGN KEY (network_version, from_node_id)
        REFERENCES network_nodes (network_version, node_id),
    CONSTRAINT fk_network_edges_to_node FOREIGN KEY (network_version, to_node_id)
        REFERENCES network_nodes (network_version, node_id),
    CONSTRAINT uq_network_edges_path UNIQUE (network_version, from_node_id, to_node_id, edge_id),
    CONSTRAINT ck_network_edges_cost CHECK (cost > 0),
    CONSTRAINT ck_network_edges_travel_time CHECK (travel_time_ms IS NULL OR travel_time_ms > 0)
);
CREATE INDEX ix_network_edges_version_source_enabled ON network_edges (network_version, from_node_id, enabled);

CREATE TABLE bags (
    bag_id uuid PRIMARY KEY,
    bag_tag text NOT NULL UNIQUE,
    flight_id text NOT NULL,
    check_in_node_id text NOT NULL,
    current_node_id text NOT NULL,
    current_state text NOT NULL,
    current_route_id uuid,
    active_command_id uuid,
    correlation_id uuid NOT NULL UNIQUE,
    state_version bigint NOT NULL DEFAULT 0,
    event_sequence bigint NOT NULL DEFAULT 0,
    failure_code text,
    failure_reason text,
    checked_in_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ck_bags_state CHECK (current_state IN (
        'CHECKED_IN', 'FLIGHT_VERIFIED', 'FLIGHT_VERIFICATION_FAILED', 'ROUTE_PLANNED',
        'ROUTING_BLOCKED', 'MOVEMENT_PENDING', 'IN_TRANSIT', 'AT_INTERMEDIATE_NODE',
        'MOVEMENT_FAILED', 'AT_MAKEUP'
    )),
    CONSTRAINT ck_bags_versions CHECK (state_version >= 0 AND event_sequence >= 0)
);
CREATE INDEX ix_bags_state_updated ON bags (current_state, updated_at);
CREATE INDEX ix_bags_flight ON bags (flight_id);
CREATE INDEX ix_bags_current_route ON bags (current_route_id);

CREATE TABLE checkin_requests (
    idempotency_key text PRIMARY KEY,
    request_hash text NOT NULL,
    bag_id uuid NOT NULL UNIQUE REFERENCES bags(bag_id),
    response_status smallint NOT NULL,
    response_body jsonb NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT ck_checkin_response_status CHECK (response_status BETWEEN 100 AND 599)
);

CREATE TABLE workflow_retry_requests (
    idempotency_key text PRIMARY KEY,
    request_hash text NOT NULL,
    bag_id uuid NOT NULL REFERENCES bags(bag_id),
    retry_id uuid NOT NULL UNIQUE,
    accepted_at timestamptz NOT NULL,
    response_body jsonb NOT NULL
);

CREATE TABLE route_plans (
    route_id uuid PRIMARY KEY,
    bag_id uuid NOT NULL REFERENCES bags(bag_id),
    route_number integer NOT NULL,
    status text NOT NULL,
    network_version text NOT NULL REFERENCES network_versions(network_version),
    source_node_id text NOT NULL,
    destination_node_id text NOT NULL,
    algorithm text NOT NULL,
    algorithm_version smallint NOT NULL,
    total_cost bigint NOT NULL,
    decision_summary jsonb NOT NULL,
    planned_at timestamptz NOT NULL,
    CONSTRAINT uq_route_plans_bag_number UNIQUE (bag_id, route_number),
    CONSTRAINT ck_route_plans_status CHECK (status IN ('PLANNED', 'ACTIVE', 'COMPLETE', 'BLOCKED', 'SUPERSEDED')),
    CONSTRAINT ck_route_plans_positive_values CHECK (route_number > 0 AND algorithm_version > 0 AND total_cost >= 0)
);
CREATE INDEX ix_route_plans_bag_planned ON route_plans (bag_id, planned_at DESC);

CREATE TABLE route_steps (
    route_id uuid NOT NULL REFERENCES route_plans(route_id),
    step_no integer NOT NULL,
    edge_id text NOT NULL,
    from_node_id text NOT NULL,
    to_node_id text NOT NULL,
    equipment_id text NOT NULL,
    expected_scanner_id text NOT NULL,
    edge_cost integer NOT NULL,
    expected_travel_time_ms integer,
    CONSTRAINT pk_route_steps PRIMARY KEY (route_id, step_no),
    CONSTRAINT uq_route_steps_edge UNIQUE (route_id, edge_id, step_no),
    CONSTRAINT ck_route_steps_values CHECK (
        step_no > 0 AND edge_cost > 0 AND (expected_travel_time_ms IS NULL OR expected_travel_time_ms > 0)
    )
);

CREATE TABLE movement_commands (
    command_id uuid PRIMARY KEY,
    bag_id uuid NOT NULL REFERENCES bags(bag_id),
    route_id uuid NOT NULL REFERENCES route_plans(route_id),
    route_step_no integer NOT NULL,
    attempt integer NOT NULL,
    equipment_id text NOT NULL REFERENCES equipment(equipment_id),
    from_node_id text NOT NULL,
    to_node_id text NOT NULL,
    status text NOT NULL,
    issued_at timestamptz NOT NULL,
    deadline_at timestamptz NOT NULL,
    started_at timestamptz,
    completed_at timestamptz,
    failure_code text,
    failure_reason text,
    CONSTRAINT uq_movement_commands_route_step_attempt UNIQUE (route_id, route_step_no, attempt),
    CONSTRAINT ck_movement_commands_status CHECK (status IN (
        'ISSUED', 'ACCEPTED', 'STARTED', 'SUCCEEDED', 'REJECTED', 'FAILED', 'TIMED_OUT', 'SUPERSEDED'
    )),
    CONSTRAINT ck_movement_commands_positive_values CHECK (route_step_no > 0 AND attempt > 0),
    CONSTRAINT ck_movement_commands_deadline CHECK (deadline_at > issued_at)
);
CREATE INDEX ix_movement_commands_status_deadline ON movement_commands (status, deadline_at);
CREATE INDEX ix_movement_commands_bag_issued ON movement_commands (bag_id, issued_at DESC);
CREATE INDEX ix_movement_commands_route_step ON movement_commands (route_id, route_step_no);

ALTER TABLE bags
    ADD CONSTRAINT fk_bags_current_route FOREIGN KEY (current_route_id)
    REFERENCES route_plans(route_id) DEFERRABLE INITIALLY DEFERRED,
    ADD CONSTRAINT fk_bags_active_command FOREIGN KEY (active_command_id)
    REFERENCES movement_commands(command_id) DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE equipment_observations (
    observation_id uuid PRIMARY KEY,
    event_id uuid NOT NULL UNIQUE,
    bag_id uuid NOT NULL REFERENCES bags(bag_id),
    route_id uuid NOT NULL REFERENCES route_plans(route_id),
    command_id uuid NOT NULL REFERENCES movement_commands(command_id),
    route_step_no integer NOT NULL,
    equipment_id text NOT NULL REFERENCES equipment(equipment_id),
    node_id text NOT NULL,
    scanner_id text NOT NULL REFERENCES equipment(equipment_id),
    observed_at timestamptz NOT NULL,
    received_at timestamptz NOT NULL,
    decision text NOT NULL,
    rejection_code text,
    envelope_correlation_id uuid NOT NULL,
    validated_payload jsonb,
    CONSTRAINT ck_equipment_observations_decision CHECK (decision IN ('ACCEPTED', 'REJECTED')),
    CONSTRAINT ck_equipment_observations_step CHECK (route_step_no > 0),
    CONSTRAINT ck_equipment_observations_rejection CHECK (decision <> 'REJECTED' OR rejection_code IS NOT NULL)
);
CREATE INDEX ix_equipment_observations_bag_observed ON equipment_observations (bag_id, observed_at DESC);
CREATE INDEX ix_equipment_observations_route_step ON equipment_observations (route_id, route_step_no);
CREATE INDEX ix_equipment_observations_command ON equipment_observations (command_id);

CREATE TABLE route_progress (
    route_id uuid PRIMARY KEY REFERENCES route_plans(route_id),
    bag_id uuid NOT NULL REFERENCES bags(bag_id),
    next_step_no integer NOT NULL,
    last_confirmed_node_id text NOT NULL,
    active_command_id uuid REFERENCES movement_commands(command_id),
    status text NOT NULL,
    observation_deadline_at timestamptz,
    updated_at timestamptz NOT NULL,
    row_version bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_route_progress_status CHECK (status IN (
        'READY', 'WAITING_FOR_COMMAND', 'WAITING_FOR_OBSERVATION', 'COMPLETE', 'FAILED', 'SUPERSEDED'
    )),
    CONSTRAINT ck_route_progress_values CHECK (next_step_no > 0 AND row_version >= 0)
);
CREATE INDEX ix_route_progress_bag ON route_progress (bag_id);
CREATE UNIQUE INDEX uq_route_progress_active_bag ON route_progress (bag_id)
    WHERE status IN ('READY', 'WAITING_FOR_COMMAND', 'WAITING_FOR_OBSERVATION');
CREATE INDEX ix_route_progress_observation_deadline ON route_progress (status, observation_deadline_at);

CREATE TABLE bag_history (
    history_id uuid PRIMARY KEY,
    bag_id uuid NOT NULL REFERENCES bags(bag_id),
    record_type text NOT NULL,
    previous_state text,
    current_state text,
    node_id text,
    reason_code text NOT NULL,
    details jsonb,
    event_id uuid UNIQUE,
    route_id uuid REFERENCES route_plans(route_id),
    command_id uuid REFERENCES movement_commands(command_id),
    observation_id uuid REFERENCES equipment_observations(observation_id),
    state_version bigint,
    occurred_at timestamptz NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_bag_history_record_type CHECK (record_type IN (
        'STATE_TRANSITION', 'WORKFLOW_RESULT', 'RETRY_REQUEST', 'OBSERVATION_DECISION'
    )),
    CONSTRAINT ck_bag_history_state_version CHECK (state_version IS NULL OR state_version >= 0)
);
CREATE INDEX ix_bag_history_bag_recorded ON bag_history (bag_id, recorded_at, history_id);
CREATE INDEX ix_bag_history_bag_state_version ON bag_history (bag_id, state_version);

CREATE TABLE processed_events (
    consumer_name text NOT NULL,
    event_id uuid NOT NULL,
    processed_at timestamptz NOT NULL,
    outcome text NOT NULL,
    CONSTRAINT pk_processed_events PRIMARY KEY (consumer_name, event_id)
);

CREATE TABLE outbox (
    outbox_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id uuid NOT NULL UNIQUE,
    event_type text NOT NULL,
    event_version smallint NOT NULL,
    topic text NOT NULL,
    message_key text NOT NULL,
    envelope jsonb NOT NULL,
    aggregate_type text NOT NULL,
    aggregate_id text NOT NULL,
    aggregate_sequence bigint NOT NULL,
    status text NOT NULL,
    attempts integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    next_attempt_at timestamptz NOT NULL,
    claimed_until timestamptz,
    published_at timestamptz,
    last_error text,
    CONSTRAINT uq_outbox_aggregate_sequence UNIQUE (aggregate_type, aggregate_id, aggregate_sequence),
    CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING', 'IN_FLIGHT', 'PUBLISHED', 'FAILED')),
    CONSTRAINT ck_outbox_positive_values CHECK (event_version > 0 AND aggregate_sequence > 0 AND attempts >= 0)
);
CREATE INDEX ix_outbox_status_next_attempt ON outbox (status, next_attempt_at, outbox_id);
CREATE INDEX ix_outbox_aggregate_sequence ON outbox (aggregate_type, aggregate_id, aggregate_sequence);
