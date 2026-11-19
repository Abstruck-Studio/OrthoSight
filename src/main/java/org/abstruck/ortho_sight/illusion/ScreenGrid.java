package org.abstruck.ortho_sight.illusion;

import net.minecraft.util.Mth;
import org.abstruck.ortho_sight.client.OrthoClient;
import net.minecraft.world.phys.Vec3;

/**
 * The screen-space frame of the orthographic camera, in closed form.
 *
 * <p>With the camera locked to a fixed orbit and {@code PITCH = 45}, two combinations of the world
 * coordinates are <b>invariant</b> along the view axis:
 *
 * <pre>
 *     a = x - sin(orbit) * y
 *     b = z + cos(orbit) * y
 * </pre>
 *
 * because {@code cos(45) == sin(45)}. Moving along the axis therefore changes neither {@code a} nor
 * {@code b}, which is exactly why a translation along the axis is invisible in the picture. The inverse,
 * on a horizontal plane at height {@code Y}, is
 *
 * <pre>
 *     x = a + sin(orbit) * Y        z = b - cos(orbit) * Y
 * </pre>
 *
 * <p>So the "oblique integer grid" of the reference material is literally {@code (floor(a), floor(b))}: one
 * cell is one horizontal block top face, and the zoom level never enters it (zoom only scales the picture).
 *
 * <p>Everything here is client-side presentation; the illusion itself (which surface supports the player) is
 * computed from these formulas but produces a normal, legal world position.
 */
public final class ScreenGrid {

    private static float cachedOrbit = Float.NaN;
    private static double sinOrbit;
    private static double cosOrbit;

    private ScreenGrid() {
    }

    private static void ensure(float orbit) {
        if (orbit == cachedOrbit) {
            return;
        }
        cachedOrbit = orbit;
        double radians = Math.toRadians(orbit);
        sinOrbit = Math.sin(radians);
        cosOrbit = Math.cos(radians);
    }

    public static void update(float orbit) {
        ensure(orbit);
    }

    public static double sin() {
        ensure(OrthoClient.orbit);
        return sinOrbit;
    }

    public static double cos() {
        ensure(OrthoClient.orbit);
        return cosOrbit;
    }

    /** Invariant screen-plane coordinate along one axis. */
    public static double a(double x, double y) {
        return x - sin() * y;
    }

    /** Invariant screen-plane coordinate along the other axis. */
    public static double b(double z, double y) {
        return z + cos() * y;
    }

    /** World x of the point at screen coordinates (a, b) that lies on the plane y = Y. */
    public static double xAt(double a, double Y) {
        return a + sin() * Y;
    }

    /** World z of the point at screen coordinates (a, b) that lies on the plane y = Y. */
    public static double zAt(double b, double Y) {
        return b - cos() * Y;
    }

    /** Depth towards the camera; larger is nearer the camera. Translation along the axis is what changes it. */
    public static double depth(double x, double y, double z) {
        return (sin() * x + y - cos() * z) * Mth.SQRT_OF_TWO * 0.5D;
    }

    /**
     * The point that projects to exactly the same screen pixel as {@code p}, but lies at height {@code Y}.
     *
     * <p>This is the entire illusion: a translation along the view axis, whose screen projection is the
     * identity, so the picture does not change at all.
     */
    public static Vec3 liftTo(Vec3 p, double Y) {
        double d = Y - p.y;
        return p.add(sin() * d, d, -cos() * d);
    }
}
