-- Fence delayed workflow facts after an explicit retry, and retain the verified
-- flight destination even when the local flight catalog is later corrected.
ALTER TABLE vortex.bags
    ADD COLUMN workflow_event_id uuid,
    ADD COLUMN verified_departure_airport text,
    ADD COLUMN verified_destination_airport text,
    ADD COLUMN verified_flight_status text,
    ADD COLUMN verified_at timestamptz,
    ADD CONSTRAINT ck_bags_verified_flight_status CHECK (verified_flight_status IN ('SCHEDULED', 'BOARDING')),
    ADD CONSTRAINT ck_bags_verified_flight_snapshot CHECK (
        num_nonnulls(verified_departure_airport, verified_destination_airport, verified_flight_status, verified_at) IN (0, 4)
    );

-- Several records can commit in one transaction with the same timestamp.
ALTER TABLE vortex.bag_history ADD COLUMN history_sequence bigint GENERATED ALWAYS AS IDENTITY;
CREATE INDEX ix_bag_history_sequence ON vortex.bag_history (bag_id, history_sequence);
