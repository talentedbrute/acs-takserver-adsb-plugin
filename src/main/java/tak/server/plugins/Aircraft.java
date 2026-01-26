/*
 * Copyright (c) 2026 Adeptus Cyber Solutions, LLC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package tak.server.plugins;

import com.google.gson.JsonObject;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

/**
 * Represents an aircraft from ADS-B data returned by airplanes.live API.
 */
public class Aircraft {

    private static final String COT_TYPE_CIVILIAN = "a-f-A-C-F";  // friendly air civilian fixed-wing
    private static final String COT_TYPE_MILITARY = "a-f-A-M-F";  // friendly air military fixed-wing

    private final String hex;
    private final String flight;
    private final String registration;
    private final String aircraftType;
    private final Double lat;
    private final Double lon;
    private final Integer altitude;
    private final Double groundSpeed;
    private final Double track;
    private final String squawk;
    private final String operator;
    private final boolean isMilitary;

    private Aircraft(Builder builder) {
        this.hex = builder.hex;
        this.flight = builder.flight;
        this.registration = builder.registration;
        this.aircraftType = builder.aircraftType;
        this.lat = builder.lat;
        this.lon = builder.lon;
        this.altitude = builder.altitude;
        this.groundSpeed = builder.groundSpeed;
        this.track = builder.track;
        this.squawk = builder.squawk;
        this.operator = builder.operator;
        this.isMilitary = builder.isMilitary;
    }

    /**
     * Parse an aircraft from the airplanes.live API JSON response.
     */
    public static Aircraft fromJson(JsonObject json) {
        Builder builder = new Builder(getStringOrNull(json, "hex"));

        String flight = getStringOrNull(json, "flight");
        if (flight != null) {
            flight = flight.trim();
            if (flight.isEmpty()) flight = null;
        }
        builder.flight(flight);

        builder.registration(getStringOrNull(json, "r"));
        builder.aircraftType(getStringOrNull(json, "t"));

        if (json.has("lat") && !json.get("lat").isJsonNull()) {
            builder.lat(json.get("lat").getAsDouble());
        }
        if (json.has("lon") && !json.get("lon").isJsonNull()) {
            builder.lon(json.get("lon").getAsDouble());
        }

        // Altitude can be a number or "ground"
        if (json.has("alt_baro") && !json.get("alt_baro").isJsonNull()) {
            try {
                builder.altitude(json.get("alt_baro").getAsInt());
            } catch (NumberFormatException e) {
                // "ground" or other non-numeric value
                builder.altitude(0);
            }
        }

        if (json.has("gs") && !json.get("gs").isJsonNull()) {
            builder.groundSpeed(json.get("gs").getAsDouble());
        }
        if (json.has("track") && !json.get("track").isJsonNull()) {
            builder.track(json.get("track").getAsDouble());
        }

        builder.squawk(getStringOrNull(json, "squawk"));
        builder.operator(getStringOrNull(json, "ownOp"));

        // Military flag from dbFlags (bit 0)
        if (json.has("dbFlags") && !json.get("dbFlags").isJsonNull()) {
            int dbFlags = json.get("dbFlags").getAsInt();
            builder.isMilitary((dbFlags & 1) != 0);
        }

        return builder.build();
    }

    private static String getStringOrNull(JsonObject json, String key) {
        if (json.has(key) && !json.get(key).isJsonNull()) {
            return json.get(key).getAsString();
        }
        return null;
    }

    /**
     * Check if this aircraft has valid position data.
     */
    public boolean hasPosition() {
        return lat != null && lon != null;
    }

    /**
     * Build CoT XML for this aircraft.
     *
     * @param staleTimeSec Seconds until the marker becomes stale
     * @return CoT XML string
     */
    public String toCotXml(int staleTimeSec) {
        if (!hasPosition()) {
            return null;
        }

        Instant now = Instant.now();
        String timeStr = DateTimeFormatter.ISO_INSTANT.format(now);
        String staleStr = DateTimeFormatter.ISO_INSTANT.format(now.plusSeconds(staleTimeSec));

        // Build callsign: prefer flight, then registration, then hex
        String callsign = flight != null ? flight : (registration != null ? registration : hex);

        // UID from hex code
        String uid = "adsb-" + hex;

        // CoT type based on military status
        String cotType = isMilitary ? COT_TYPE_MILITARY : COT_TYPE_CIVILIAN;

        // Convert altitude from feet to meters (HAE)
        double hae = 0.0;
        if (altitude != null && altitude > 0) {
            hae = altitude * 0.3048;
        }

        // Convert speed from knots to m/s
        double speedMs = 0.0;
        if (groundSpeed != null) {
            speedMs = groundSpeed * 0.514444;
        }

        // Course/track
        double course = track != null ? track : 0.0;

        // Build remarks
        StringBuilder remarks = new StringBuilder();
        if (registration != null) remarks.append("Reg: ").append(registration).append("\n");
        if (aircraftType != null) remarks.append("Type: ").append(aircraftType).append("\n");
        if (operator != null) remarks.append("Operator: ").append(operator).append("\n");
        if (squawk != null) remarks.append("Squawk: ").append(squawk).append("\n");
        if (altitude != null) remarks.append("Alt: ").append(altitude).append(" ft\n");
        if (isMilitary) remarks.append("MILITARY\n");

        // Build CoT XML
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        xml.append("<event version=\"2.0\"");
        xml.append(" uid=\"").append(escapeXml(uid)).append("\"");
        xml.append(" type=\"").append(cotType).append("\"");
        xml.append(" how=\"m-g\"");
        xml.append(" time=\"").append(timeStr).append("\"");
        xml.append(" start=\"").append(timeStr).append("\"");
        xml.append(" stale=\"").append(staleStr).append("\"");
        xml.append(">");

        xml.append("<point");
        xml.append(" lat=\"").append(lat).append("\"");
        xml.append(" lon=\"").append(lon).append("\"");
        xml.append(" hae=\"").append(hae).append("\"");
        xml.append(" ce=\"9999999.0\"");
        xml.append(" le=\"9999999.0\"");
        xml.append("/>");

        xml.append("<detail>");
        xml.append("<contact callsign=\"").append(escapeXml(callsign)).append("\"/>");
        xml.append("<track speed=\"").append(speedMs).append("\" course=\"").append(course).append("\"/>");

        if (remarks.length() > 0) {
            xml.append("<remarks source=\"ADS-B\">").append(escapeXml(remarks.toString().trim())).append("</remarks>");
        }

        xml.append("</detail>");
        xml.append("</event>");

        return xml.toString();
    }

    private String escapeXml(String str) {
        if (str == null) return "";
        return str.replace("&", "&amp;")
                  .replace("<", "&lt;")
                  .replace(">", "&gt;")
                  .replace("\"", "&quot;")
                  .replace("'", "&apos;");
    }

    // Getters
    public String getHex() { return hex; }
    public String getFlight() { return flight; }
    public String getRegistration() { return registration; }
    public String getAircraftType() { return aircraftType; }
    public Double getLat() { return lat; }
    public Double getLon() { return lon; }
    public Integer getAltitude() { return altitude; }
    public Double getGroundSpeed() { return groundSpeed; }
    public Double getTrack() { return track; }
    public String getSquawk() { return squawk; }
    public String getOperator() { return operator; }
    public boolean isMilitary() { return isMilitary; }

    @Override
    public String toString() {
        return "Aircraft{" +
                "hex='" + hex + '\'' +
                ", flight='" + flight + '\'' +
                ", lat=" + lat +
                ", lon=" + lon +
                ", altitude=" + altitude +
                ", isMilitary=" + isMilitary +
                '}';
    }

    public static class Builder {
        private final String hex;
        private String flight;
        private String registration;
        private String aircraftType;
        private Double lat;
        private Double lon;
        private Integer altitude;
        private Double groundSpeed;
        private Double track;
        private String squawk;
        private String operator;
        private boolean isMilitary;

        public Builder(String hex) {
            this.hex = hex != null ? hex : "unknown";
        }

        public Builder flight(String flight) { this.flight = flight; return this; }
        public Builder registration(String registration) { this.registration = registration; return this; }
        public Builder aircraftType(String aircraftType) { this.aircraftType = aircraftType; return this; }
        public Builder lat(Double lat) { this.lat = lat; return this; }
        public Builder lon(Double lon) { this.lon = lon; return this; }
        public Builder altitude(Integer altitude) { this.altitude = altitude; return this; }
        public Builder groundSpeed(Double groundSpeed) { this.groundSpeed = groundSpeed; return this; }
        public Builder track(Double track) { this.track = track; return this; }
        public Builder squawk(String squawk) { this.squawk = squawk; return this; }
        public Builder operator(String operator) { this.operator = operator; return this; }
        public Builder isMilitary(boolean isMilitary) { this.isMilitary = isMilitary; return this; }

        public Aircraft build() {
            return new Aircraft(this);
        }
    }
}
