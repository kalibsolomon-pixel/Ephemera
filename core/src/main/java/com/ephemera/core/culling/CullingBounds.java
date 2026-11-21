package com.ephemera.core.culling;

/** Reusable render envelope; owned by one render thread, with no game objects retained. */
public final class CullingBounds {
    public double x0, y0, z0, x1, y1, z1;

    public void entity(double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                       double dx, double dy, double dz, double scale) {
        double pad = Math.max(.25, Math.min(.6, .3 * scale));
        x0 = minX + Math.min(0, dx) - pad;
        y0 = minY + Math.min(0, dy) - .0625;
        z0 = minZ + Math.min(0, dz) - pad;
        x1 = maxX + Math.max(0, dx) + pad;
        y1 = maxY + Math.max(0, dy) + .25;
        z1 = maxZ + Math.max(0, dz) + pad;
    }

    public void shadow(double oldX, double oldY, double oldZ, double x, double y, double z, double radius) {
        if (radius <= 0) return;
        // 26.2 extracts complete block surfaces at floor(position +/- radius).
        // Its vertical sample depth is <= radius, independently of onGround().
        // Include the complete sampled cells, but no extra block below their surfaces.
        x0 = Math.min(x0, Math.floor(Math.min(oldX, x) - radius));
        y0 = Math.min(y0, Math.floor(Math.min(oldY, y) - radius));
        z0 = Math.min(z0, Math.floor(Math.min(oldZ, z) - radius));
        x1 = Math.max(x1, Math.floor(Math.max(oldX, x) + radius) + 1);
        y1 = Math.max(y1, Math.floor(Math.max(oldY, y)) + 1);
        z1 = Math.max(z1, Math.floor(Math.max(oldZ, z) + radius) + 1);
    }

    public void block(int x, int y, int z) {
        x0 = x - .1; y0 = y - .02; z0 = z - .1;
        x1 = x + 1.1; y1 = y + 1.5; z1 = z + 1.1;
    }
}
