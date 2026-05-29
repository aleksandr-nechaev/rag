package com.nechaev.config;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import org.hibernate.bytecode.spi.BytecodeProvider;

// In Hibernate 7.2 the BytecodeProvider is chosen purely by ServiceLoader — the
// hibernate.bytecode.provider setting is no longer read (BytecodeProviderInitiator only calls
// loadJavaServices). hibernate-core ships a ServiceLoader registration for the ByteBuddy provider,
// whose getReflectionOptimizer() generates a per-entity *Instantiator class at runtime via
// ByteBuddy — forbidden under native image ("Classes cannot be defined at runtime").
//
// This GraalVM substitution forces the no-op "none" provider instead. Its getReflectionOptimizer
// returns null, so Hibernate falls back to plain reflection for entity instantiation/field access,
// which is fully native-compatible. Build-time bytecode enhancement (Gradle hibernate plugin)
// already makes entities self-sufficient for lazy loading, so no runtime ProxyFactory is needed
// either. Only compiled into the native image; harmless and unloaded on the JVM.
@TargetClass(className = "org.hibernate.bytecode.internal.BytecodeProviderInitiator")
final class HibernateBytecodeProviderSubstitution {

    @Substitute
    public static BytecodeProvider getBytecodeProvider(Iterable<BytecodeProvider> providers) {
        return new org.hibernate.bytecode.internal.none.BytecodeProviderImpl();
    }
}
