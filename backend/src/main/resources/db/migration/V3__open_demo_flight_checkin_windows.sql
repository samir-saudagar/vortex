-- Keep the fixed future fixtures eligible for local demonstrations during the approved baseline period.
UPDATE flights
SET check_in_opens_at = TIMESTAMPTZ '2020-01-01 00:00:00+00'
WHERE flight_id IN ('FL-204-2030-01-15', 'FL-205-2030-01-15')
  AND status IN ('SCHEDULED', 'BOARDING');
