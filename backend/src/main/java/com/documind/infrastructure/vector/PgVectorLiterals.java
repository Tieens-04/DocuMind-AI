package com.documind.infrastructure.vector;

import com.pgvector.PGvector;

/**
 * Validation and formatting helper for 1024-dimensional, non-zero, finite {@code pgvector} parameter literals.
 */
final class PgVectorLiterals {

    static final int EXPECTED_DIMENSIONS = 1024;

    private PgVectorLiterals() {
    }

    static String toNonZeroFiniteVectorLiteral(float[] vector, String paramName) {
        String label = (paramName == null || paramName.isBlank()) ? "vector" : paramName;
        if (vector == null || vector.length == 0) {
            throw new IllegalArgumentException(label + " must not be null or empty");
        }
        if (vector.length != EXPECTED_DIMENSIONS) {
            throw new IllegalArgumentException(
                label + " must have exactly " + EXPECTED_DIMENSIONS + " dimensions, but got " + vector.length
            );
        }
        double normSquared = 0.0d;
        for (int i = 0; i < vector.length; i++) {
            float component = vector[i];
            if (!Float.isFinite(component)) {
                throw new IllegalArgumentException(label + " component at index " + i + " must be finite");
            }
            normSquared += (double) component * component;
        }
        if (normSquared <= 0.0d || !Double.isFinite(normSquared)) {
            throw new IllegalArgumentException(label + " must have a positive finite L2 norm");
        }
        return new PGvector(vector).toString();
    }
}
