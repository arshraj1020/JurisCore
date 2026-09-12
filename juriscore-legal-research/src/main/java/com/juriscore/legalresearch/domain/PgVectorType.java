package com.juriscore.legalresearch.domain;

import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.usertype.UserType;
import org.postgresql.util.PGobject;

import java.io.Serializable;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Objects;

/**
 * Hibernate mapping for a pgvector {@code vector} column, backed directly by the
 * PostgreSQL JDBC driver's {@link PGobject} rather than an extra dependency (e.g.
 * pgvector-java) — pgvector's wire format is just {@code [f1,f2,...]} text with the
 * type name {@code vector}, which {@link PGobject} carries as-is, so the driver already
 * used for the datasource is enough. That driver is declared explicitly at
 * {@code compile} scope in this module's own pom (it is only a {@code runtime}-scope
 * dependency of juriscore-app, which never imports a JDBC type directly) — see the
 * pom comment next to it for why the two scopes don't collide.
 *
 * <p>Deliberately a plain {@code float[]}, not a wrapper type: the embedding is never
 * inspected or modified by application code between being written by the embedding step
 * and being read back for similarity search, so there is nothing a richer type would buy.
 */
public class PgVectorType implements UserType<float[]> {

    private static final String PG_TYPE_NAME = "vector";

    @Override
    public int getSqlType() {
        return Types.OTHER;
    }

    @Override
    public Class<float[]> returnedClass() {
        return float[].class;
    }

    @Override
    public boolean equals(float[] x, float[] y) {
        return Objects.deepEquals(x, y);
    }

    @Override
    public int hashCode(float[] x) {
        return x == null ? 0 : java.util.Arrays.hashCode(x);
    }

    @Override
    public float[] nullSafeGet(ResultSet rs, int position, SharedSessionContractImplementor session, Object owner)
            throws SQLException {
        String value = rs.getString(position);
        return value == null ? null : parse(value);
    }

    @Override
    public void nullSafeSet(PreparedStatement st, float[] value, int index, SharedSessionContractImplementor session)
            throws SQLException {
        if (value == null) {
            st.setNull(index, Types.OTHER);
            return;
        }
        PGobject vector = new PGobject();
        vector.setType(PG_TYPE_NAME);
        vector.setValue(format(value));
        st.setObject(index, vector);
    }

    @Override
    public float[] deepCopy(float[] value) {
        return value == null ? null : value.clone();
    }

    @Override
    public boolean isMutable() {
        return true;
    }

    @Override
    public Serializable disassemble(float[] value) {
        return deepCopy(value);
    }

    @Override
    public float[] assemble(Serializable cached, Object owner) {
        return deepCopy((float[]) cached);
    }

    static String format(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 8 + 2);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }

    static float[] parse(String value) {
        String trimmed = value.substring(1, value.length() - 1);
        if (trimmed.isEmpty()) {
            return new float[0];
        }
        String[] parts = trimmed.split(",");
        float[] result = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = Float.parseFloat(parts[i]);
        }
        return result;
    }
}
