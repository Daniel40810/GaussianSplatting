package com.dan.fgaussian.db;

import com.dan.fgaussian.core.FSplatCloud;
import com.dan.fgaussian.core.FSplatCodec;
import com.dan.fgaussian.core.FSplatCodec.Block;

import java.io.ByteArrayInputStream;
import java.sql.Blob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Liest und schreibt Splat-Szenen in den Tabellen {@code FGS_SCENE} und
 * {@code FGS_BLOCK} (siehe {@code sql/fgs_schema.sql}).
 *
 * <p>Die Klasse kommt ohne oracle-eigene Typen aus: die Bloecke gehen als
 * Bytestrom in die BLOBs, und die Bounding-Box wird im INSERT als
 * {@code SDO_GEOMETRY}-Ausdruck mit Bindevariablen zusammengesetzt. Damit
 * uebersetzt und laeuft das DAO gegen jeden JDBC-Treiber, der die Datenbank
 * erreicht.</p>
 *
 * <p>Die Kodierung selbst steckt in {@link FSplatCodec} - das DAO kuemmert sich
 * nur um SQL.</p>
 *
 * @author com.dan.fgaussian
 */
public final class FGaussianDAO {

    /** Beschreibung einer Szene ohne die Splatdaten. */
    public record SceneInfo(long id, String name, String sourceKind, int splatCount,
                            float exaggeration, double centreEast, double centreNorth,
                            int srid, int shDegree, Timestamp created) {
    }

    /**
     * Angaben, die neben den Splats gespeichert werden.
     *
     * @param sourceKind   XYZ, PLY, SDO oder MIX
     * @param exaggeration Hoehenueberhoehung, mit der die Szene gebaut wurde
     * @param centreEast   Rechtswert des Szenenursprungs
     * @param centreNorth  Hochwert des Szenenursprungs
     */
    public record SceneMeta(String sourceKind, float exaggeration,
                            double centreEast, double centreNorth,
                            int srid, String note) {

        /** Uebliche Angaben fuer eine aus einem Hoehenraster gebaute Szene. */
        public static SceneMeta ofGrid(float exaggeration, double centreEast, double centreNorth) {
            return new SceneMeta("XYZ", exaggeration, centreEast, centreNorth, 25832, null);
        }
    }

    private static final String INSERT_SCENE =
            "INSERT INTO FGS_SCENE (NAME, SRID, SPLAT_COUNT, SOURCE_KIND, EXAGGERATION, "
          + "CENTRE_EAST, CENTRE_NORTH, SH_DEGREE, NOTE, BBOX) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, "
          + "SDO_GEOMETRY(2003, ?, NULL, SDO_ELEM_INFO_ARRAY(1,1003,3), "
          + "SDO_ORDINATE_ARRAY(?, ?, ?, ?)))";

    private static final String INSERT_BLOCK =
            "INSERT INTO FGS_BLOCK (SCENE_ID, KIND, SEQ, SPLAT_COUNT, DATA) VALUES (?, ?, ?, ?, ?)";

    /** Haltet BLOBs klein; ein Block bleibt damit weit unter Oracle-Limits. */
    private static final int BLOCK_SPLATS = 65_536;

    private final Connection connection;

    public FGaussianDAO(Connection connection) {
        this.connection = connection;
    }

    // ------------------------------------------------------------------
    // Schreiben
    // ------------------------------------------------------------------

    /**
     * Speichert eine Szene. Eine vorhandene Szene gleichen Namens wird ersetzt -
     * beim Ausprobieren verschiedener Ueberhoehungen ist das die Regel, nicht
     * die Ausnahme.
     *
     * <p>Die Transaktion wird hier nicht abgeschlossen; das Commit bleibt beim
     * Aufrufer, damit mehrere Szenen gemeinsam geschrieben werden koennen. Die
     * einzelne Szene wird ueber einen Savepoint atomar ersetzt; dafuer muss die
     * Verbindung mit {@code autoCommit=false} betrieben werden.</p>
     *
     * @return die vergebene Szenen-ID
     */
    public long save(String name, FSplatCloud cloud, SceneMeta meta) throws SQLException {
        if (connection.getAutoCommit()) {
            throw new SQLException("FGaussianDAO.save benoetigt autoCommit=false");
        }
        cloud.computeBounds();
        Savepoint savepoint = connection.setSavepoint();

        try {
            deleteByName(name);

            long id;
            try (PreparedStatement ps = connection.prepareStatement(INSERT_SCENE, new String[] { "ID" })) {
                int i = 1;
                ps.setString(i++, name);
                ps.setInt(i++, meta.srid());
                ps.setInt(i++, cloud.count);
                ps.setString(i++, meta.sourceKind());
                ps.setFloat(i++, meta.exaggeration());
                ps.setDouble(i++, meta.centreEast());
                ps.setDouble(i++, meta.centreNorth());
                ps.setInt(i++, cloud.shDegree);
                ps.setString(i++, meta.note());
                ps.setInt(i++, meta.srid());

                // Szenenachse z zeigt nach Sueden, deshalb dreht sich die
                // Nordrichtung beim Zurueckrechnen um.
                double minEast = meta.centreEast() + cloud.minX;
                double maxEast = meta.centreEast() + cloud.maxX;
                double minNorth = meta.centreNorth() - cloud.maxZ;
                double maxNorth = meta.centreNorth() - cloud.minZ;
                ps.setDouble(i++, minEast);
                ps.setDouble(i++, minNorth);
                ps.setDouble(i++, maxEast);
                ps.setDouble(i, maxNorth);

                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new SQLException("Keine Szenen-ID zurueckgeliefert");
                    }
                    id = keys.getLong(1);
                }
            }

            try (PreparedStatement ps = connection.prepareStatement(INSERT_BLOCK)) {
                for (Block block : Block.values()) {
                    writeBlocks(ps, id, block.name(), cloud, block);
                }
                if (cloud.shDegree > 0 && cloud.sh != null) {
                    writeShBlocks(ps, id, cloud);
                }
            }
            connection.releaseSavepoint(savepoint);
            return id;
        } catch (SQLException failure) {
            connection.rollback(savepoint);
            throw failure;
        }
    }

    /**
     * Schreibt einen Block sofort statt ueber einen Batch: Bindevariablen als
     * Datenstrom vertragen sich mit {@code addBatch} nicht bei jedem Treiber,
     * und bei fuenf Bloecken je Szene bringt ein Batch ohnehin nichts.
     */
    private void writeBlocks(PreparedStatement ps, long sceneId, String kind,
                             FSplatCloud cloud, Block block) throws SQLException {
        int seq = 0;
        for (int offset = 0; offset < cloud.count; offset += BLOCK_SPLATS) {
            int count = Math.min(BLOCK_SPLATS, cloud.count - offset);
            writeBlock(ps, sceneId, kind, seq++, count,
                    FSplatCodec.encode(cloud, block, offset, count));
        }
    }

    private void writeShBlocks(PreparedStatement ps, long sceneId,
                               FSplatCloud cloud) throws SQLException {
        int seq = 0;
        for (int offset = 0; offset < cloud.count; offset += BLOCK_SPLATS) {
            int count = Math.min(BLOCK_SPLATS, cloud.count - offset);
            writeBlock(ps, sceneId, "SH", seq++, count,
                    FSplatCodec.encodeSh(cloud, offset, count));
        }
    }

    private void writeBlock(PreparedStatement ps, long sceneId, String kind,
                            int seq, int count, byte[] data) throws SQLException {
        ps.setLong(1, sceneId);
        ps.setString(2, kind);
        ps.setInt(3, seq);
        ps.setInt(4, count);
        ps.setBinaryStream(5, new ByteArrayInputStream(data), data.length);
        ps.executeUpdate();
    }

    // ------------------------------------------------------------------
    // Lesen
    // ------------------------------------------------------------------

    /** Liest eine Szene mit allen Bloecken; {@code null}, wenn es sie nicht gibt. */
    public FSplatCloud load(long id) throws SQLException {
        int count;
        int shDegree;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT SPLAT_COUNT, SH_DEGREE FROM FGS_SCENE WHERE ID = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                count = rs.getInt(1);
                shDegree = rs.getInt(2);
            }
        }

        FSplatCloud cloud = new FSplatCloud(count);
        if (shDegree > 0) {
            cloud.allocateSh(shDegree);
        }

        Map<String, Integer> offsets = new HashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT KIND, SPLAT_COUNT, DATA FROM FGS_BLOCK WHERE SCENE_ID = ? ORDER BY KIND, SEQ")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String kind = rs.getString(1);
                    int blockCount = rs.getInt(2);
                    byte[] data = readBlob(rs.getBlob(3));
                    int offset = offsets.getOrDefault(kind, 0);
                    if (offset + blockCount > count) {
                        throw new SQLException("Zu viele Splats im Block " + kind);
                    }
                    if ("SH".equals(kind)) {
                        FSplatCodec.decodeSh(data, cloud, offset, blockCount);
                    } else {
                        FSplatCodec.decode(data, cloud, Block.valueOf(kind), offset, blockCount);
                    }
                    offsets.put(kind, offset + blockCount);
                }
            }
        }
        for (Block block : Block.values()) {
            if (offsets.getOrDefault(block.name(), 0) != count) {
                throw new SQLException("Block " + block + " ist unvollstaendig");
            }
        }
        if (shDegree > 0 && offsets.getOrDefault("SH", 0) != count) {
            throw new SQLException("SH-Block ist unvollstaendig");
        }
        cloud.count = count;
        cloud.computeBounds();
        return cloud;
    }

    /** Alle Szenen, neueste zuerst. */
    public List<SceneInfo> list() throws SQLException {
        List<SceneInfo> out = new ArrayList<>();
        String sql = "SELECT ID, NAME, SOURCE_KIND, SPLAT_COUNT, EXAGGERATION, "
                   + "CENTRE_EAST, CENTRE_NORTH, SRID, SH_DEGREE, CREATED "
                   + "FROM FGS_SCENE ORDER BY CREATED DESC";
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(readSceneInfo(rs));
            }
        }
        return out;
    }

    /** Sucht eine Szene ueber ihren Namen. */
    public SceneInfo findByName(String name) throws SQLException {
        String sql = "SELECT ID, NAME, SOURCE_KIND, SPLAT_COUNT, EXAGGERATION, "
                   + "CENTRE_EAST, CENTRE_NORTH, SRID, SH_DEGREE, CREATED "
                   + "FROM FGS_SCENE WHERE NAME = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readSceneInfo(rs) : null;
            }
        }
    }

    // ------------------------------------------------------------------
    // Anker
    // ------------------------------------------------------------------

    /**
     * Ein benannter Ort in der Szene - Molenkopf, Kaikante, Liegeplatz.
     * Koordinaten in der SRID der Szene, also in UTM-Metern.
     */
    public record Anchor(long id, long sceneId, String label, String kind,
                         double east, double north, Long paletteId) {

        /** Neuer Anker ohne ID; die vergibt die Datenbank. */
        public static Anchor of(String label, String kind, double east, double north) {
            return new Anchor(0L, 0L, label, kind, east, north, null);
        }
    }

    /** Schreibt Anker zu einer Szene; vorhandene dieser Szene werden ersetzt. */
    public int saveAnchors(long sceneId, int srid, List<Anchor> anchors) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM FGS_ANCHOR WHERE SCENE_ID = ?")) {
            ps.setLong(1, sceneId);
            ps.executeUpdate();
        }
        String sql = "INSERT INTO FGS_ANCHOR (SCENE_ID, LABEL, KIND, PALETTE_ID, GEOM) VALUES "
                   + "(?, ?, ?, ?, SDO_GEOMETRY(2001, ?, SDO_POINT_TYPE(?, ?, NULL), NULL, NULL))";
        int written = 0;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (Anchor a : anchors) {
                ps.setLong(1, sceneId);
                ps.setString(2, a.label());
                ps.setString(3, a.kind());
                if (a.paletteId() == null) {
                    ps.setNull(4, java.sql.Types.NUMERIC);
                } else {
                    ps.setLong(4, a.paletteId());
                }
                ps.setInt(5, srid);
                ps.setDouble(6, a.east());
                ps.setDouble(7, a.north());
                written += ps.executeUpdate();
            }
        }
        return written;
    }

    /**
     * Liest die Anker einer Szene.
     *
     * <p>Die Koordinaten kommen ueber {@code GEOM.SDO_POINT.X/Y} direkt aus dem
     * Objekttyp - fuer Punktgeometrien braucht es dafuer weder JGeometry noch
     * einen oracle-eigenen Typ im Java-Code.</p>
     */
    public List<Anchor> loadAnchors(long sceneId) throws SQLException {
        List<Anchor> out = new ArrayList<>();
        String sql = "SELECT a.ID, a.LABEL, a.KIND, a.PALETTE_ID, "
                   + "a.GEOM.SDO_POINT.X, a.GEOM.SDO_POINT.Y "
                   + "FROM FGS_ANCHOR a WHERE a.SCENE_ID = ? ORDER BY a.ID";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, sceneId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long palette = rs.getLong(4);
                    // wasNull() bezieht sich immer auf den zuletzt gelesenen
                    // Wert, muss also unmittelbar nach getLong stehen.
                    Long paletteId = rs.wasNull() ? null : palette;
                    out.add(new Anchor(
                            rs.getLong(1), sceneId, rs.getString(2), rs.getString(3),
                            rs.getDouble(5), rs.getDouble(6), paletteId));
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Loeschen
    // ------------------------------------------------------------------

    /** Loescht eine Szene samt Bloecken und Ankern (Fremdschluessel mit CASCADE). */
    public int delete(long id) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM FGS_SCENE WHERE ID = ?")) {
            ps.setLong(1, id);
            return ps.executeUpdate();
        }
    }

    /** Loescht eine Szene ueber ihren Namen. */
    public int deleteByName(String name) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM FGS_SCENE WHERE NAME = ?")) {
            ps.setString(1, name);
            return ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------

    private static byte[] readBlob(Blob blob) throws SQLException {
        if (blob == null) {
            return new byte[0];
        }
        try {
            long length = blob.length();
            if (length > Integer.MAX_VALUE) {
                throw new SQLException("Block groesser als 2 GB");
            }
            return blob.getBytes(1L, (int) length);
        } finally {
            try {
                blob.free();
            } catch (SQLException | UnsupportedOperationException ignored) {
                // Manche Treiber geben das LOB erst mit dem ResultSet frei.
            }
        }
    }

    private static SceneInfo readSceneInfo(ResultSet rs) throws SQLException {
        return new SceneInfo(
                rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                rs.getFloat(5), rs.getDouble(6), rs.getDouble(7),
                rs.getInt(8), rs.getInt(9), rs.getTimestamp(10));
    }
}
