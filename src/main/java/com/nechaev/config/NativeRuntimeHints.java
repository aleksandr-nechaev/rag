package com.nechaev.config;

import com.nechaev.model.SessionMessage;
import com.pgvector.PGvector;
import org.jspecify.annotations.Nullable;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

import java.sql.PreparedStatement;
import java.util.ArrayList;

public class NativeRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, @Nullable ClassLoader classLoader) {
        // JDK Proxy created in VectorJdbcTemplate to intercept PreparedStatement.setObject for PGvector.
        // The tracing agent may miss it under light traffic, so we register it explicitly.
        hints.proxies().registerJdkProxy(PreparedStatement.class);

        // PGvector type — covered by the agent but kept here as a belt-and-braces guarantee
        // because the JDBC driver uses reflection to register it on every new connection.
        hints.reflection().registerType(PGvector.class, MemberCategory.values());

        // Value type of the "sessions" Redis cache. Serialized by GenericJacksonJsonRedisSerializer
        // (default typing embeds the class name), so the record, its accessors/constructor, the
        // Role enum and the concrete ArrayList wrapper must be reflectively reachable in the native
        // image — otherwise the read fails-open to empty and history silently collapses.
        hints.reflection().registerType(SessionMessage.class, MemberCategory.values());
        hints.reflection().registerType(SessionMessage.Role.class, MemberCategory.values());
        hints.reflection().registerType(ArrayList.class, MemberCategory.values());

        // Classpath resources read at runtime. The agent picks these up too, but explicit
        // patterns guard against future code paths that read these resources lazily.
        hints.resources().registerPattern("static/.*\\.pdf");
        hints.resources().registerPattern("static/index\\.html");
        hints.resources().registerPattern("prompts/.*\\.txt");
        hints.resources().registerPattern("db/changelog/.*");

        // Liquibase singletons that the tracing agent missed because the local DB already
        // had migrations applied (Liquibase short-circuits parsing on a warm database).
        // On a fresh production DB these classes ARE reached, and Spring/RMR don't cover them.
        String[] liquibaseFirstRunSingletons = new String[]{
                "liquibase.parser.SqlParserFactory",
                "liquibase.parser.core.sql.SqlChangeLogParser",
                "liquibase.parser.core.formattedsql.FormattedSqlChangeLogParser",
                "liquibase.change.AbstractSQLChange",
                "liquibase.util.StringUtil",
                "liquibase.sqlparser.SqlParser",
                "liquibase.sqlparser.core.SimpleSqlParser",
        };
        for (String name : liquibaseFirstRunSingletons) {
            hints.reflection().registerTypeIfPresent(classLoader, name,
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                    MemberCategory.INVOKE_PUBLIC_METHODS,
                    MemberCategory.ACCESS_DECLARED_FIELDS);
        }

        // Hibernate 7 loads BytecodeProvider via ServiceLoader. Spring ORM 7.0.6 tries to
        // exclude it through META-INF/native-image/spring-orm/native-image.properties, but the
        // exclusion doesn't match Hibernate 7's discovery path, so ClassLoaderServiceImpl
        // still reflects on the impl class. Register it explicitly.
        String[] hibernateBytecodeProvider = new String[]{
                "org.hibernate.bytecode.internal.bytebuddy.BytecodeProviderImpl",
                "org.hibernate.bytecode.spi.BytecodeProvider",
                "org.hibernate.bytecode.internal.none.BytecodeProviderImpl",
        };
        for (String name : hibernateBytecodeProvider) {
            hints.reflection().registerTypeIfPresent(classLoader, name,
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                    MemberCategory.INVOKE_PUBLIC_METHODS);
        }

        // Hibernate 7 wraps every JPA/Hibernate annotation it encounters on entities in an
        // internal "JpaAnnotation" or "Annotation" wrapper class, instantiated reflectively
        // with the signature (<Annotation>, ModelsContext). These classes live in
        // org.hibernate.boot.models.annotations.internal and number ~80. Register them all.
        String[] hibernateAnnotationWrappers = new String[]{
                "org.hibernate.boot.models.annotations.internal.EntityJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.TableJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.IdJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.ColumnJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.TransientJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.GeneratedValueJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.BasicJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.EmbeddableJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.EmbeddedJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.EmbeddedIdJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.IdClassJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.InheritanceJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.MappedSuperclassJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.DiscriminatorColumnJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.DiscriminatorValueJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.SecondaryTableJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.SecondaryTablesJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.JoinColumnJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.JoinColumnsJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.JoinTableJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.PrimaryKeyJoinColumnJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.PrimaryKeyJoinColumnsJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.OneToOneJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.OneToManyJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.ManyToOneJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.ManyToManyJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.OrderByJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.OrderColumnJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.MapKeyJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.MapKeyColumnJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.MapKeyClassJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.MapKeyJoinColumnJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.MapKeyJoinColumnsJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.MapKeyEnumeratedJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.MapKeyTemporalJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.VersionJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.PrePersistJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.PostPersistJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.PreUpdateJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.PostUpdateJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.PreRemoveJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.PostRemoveJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.PostLoadJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.EntityListenersJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.LobJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.EnumeratedJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.TemporalJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.TableGeneratorJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.SequenceGeneratorJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.AttributeOverrideJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.AttributeOverridesJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.AssociationOverrideJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.AssociationOverridesJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.AccessJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.CacheableJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.ConvertJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.ConvertsJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.ConverterJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.ElementCollectionJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.CollectionTableJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.ExcludeDefaultListenersJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.ExcludeSuperclassListenersJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.NamedQueryJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.NamedQueriesJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.NamedNativeQueryJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.NamedNativeQueriesJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.SqlResultSetMappingJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.SqlResultSetMappingsJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.UniqueConstraintJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.IndexJpaAnnotation",
                "org.hibernate.boot.models.annotations.internal.CheckConstraintJpaAnnotation",
        };
        for (String name : hibernateAnnotationWrappers) {
            hints.reflection().registerTypeIfPresent(classLoader, name,
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                    MemberCategory.INVOKE_PUBLIC_METHODS);
        }
    }
}
