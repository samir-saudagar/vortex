package com.vortex.flight.persistence;

import com.vortex.flight.api.*;
import com.vortex.flight.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.*;

@Repository
public class JdbcFlightCatalog implements FlightCatalog, FlightQuery {
    private final JdbcTemplate jdbc;
    public JdbcFlightCatalog(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public Optional<Flight> findById(String id) {
        return jdbc.query("SELECT * FROM vortex.flights WHERE flight_id = ?", (rs, n) -> map(rs), id).stream().findFirst();
    }
    public List<Flight> list(LocalDate date, String origin) {
        String sql = "SELECT * FROM vortex.flights WHERE true";
        List<Object> args = new ArrayList<>();
        if (date != null) { sql += " AND service_date = ?"; args.add(date); }
        if (origin != null) { sql += " AND departure_airport = ?"; args.add(origin); }
        return jdbc.query(sql + " ORDER BY service_date, flight_id", (rs, n) -> map(rs), args.toArray());
    }
    private Flight map(ResultSet rs) throws SQLException {
        var opening = rs.getTimestamp("check_in_opens_at");
        return new Flight(rs.getString("flight_id"), rs.getString("carrier_code"), rs.getString("flight_number"),
                rs.getObject("service_date", LocalDate.class), rs.getString("departure_airport"), rs.getString("destination_airport"),
                FlightStatus.valueOf(rs.getString("status")), Optional.ofNullable(opening).map(java.sql.Timestamp::toInstant),
                rs.getTimestamp("check_in_closes_at").toInstant());
    }
}
