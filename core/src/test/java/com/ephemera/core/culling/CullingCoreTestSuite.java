package com.ephemera.core.culling;

import java.lang.reflect.InvocationTargetException;
import org.junit.jupiter.api.Test;

/** Runs the same JUnit tests with the lightweight worker classpath, without launching Gradle. */
public final class CullingCoreTestSuite {
    public static void main(String[] args) throws Exception {
        int count = 0;
        for (Class<?> type : new Class<?>[] {OpaqueLayerSweepTest.class, DirectMappedSweepCacheTest.class,
                ShadowGroundCacheCoreTest.class, LeafCullingPolicyTest.class, CullingBoundsTest.class,
                ParticleCellOcclusionTest.class}) {
            Object suite = type.getDeclaredConstructor().newInstance();
            for (var method : type.getDeclaredMethods()) if (method.isAnnotationPresent(Test.class)) {
                try { method.invoke(suite); count++; }
                catch (InvocationTargetException e) { throw new AssertionError(type.getSimpleName() + "." + method.getName(), e.getCause()); }
            }
        }
        System.out.println("CULLING CORE TESTS OK (" + count + " tests)");
    }
}
