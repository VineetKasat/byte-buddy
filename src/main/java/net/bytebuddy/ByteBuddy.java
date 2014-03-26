package net.bytebuddy;

import net.bytebuddy.asm.ClassVisitorWrapper;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.scaffold.BridgeMethodResolver;
import net.bytebuddy.dynamic.scaffold.FieldRegistry;
import net.bytebuddy.dynamic.scaffold.MethodRegistry;
import net.bytebuddy.dynamic.scaffold.subclass.ConstructorStrategy;
import net.bytebuddy.dynamic.scaffold.subclass.SubclassDynamicTypeBuilder;
import net.bytebuddy.instrumentation.Instrumentation;
import net.bytebuddy.instrumentation.ModifierContributor;
import net.bytebuddy.instrumentation.attribute.FieldAttributeAppender;
import net.bytebuddy.instrumentation.attribute.MethodAttributeAppender;
import net.bytebuddy.instrumentation.attribute.TypeAttributeAppender;
import net.bytebuddy.instrumentation.method.matcher.MethodMatcher;
import net.bytebuddy.instrumentation.type.TypeDescription;
import net.bytebuddy.instrumentation.type.TypeList;

import java.lang.annotation.Annotation;
import java.util.Collections;
import java.util.List;

import static net.bytebuddy.instrumentation.method.matcher.MethodMatchers.*;
import static net.bytebuddy.utility.ByteBuddyCommons.*;

/**
 * {@code ByteBuddy} is a configurable factory for creating {@link net.bytebuddy.dynamic.DynamicType}s which represent
 * dynamically created Java {@link java.lang.Class}es which can be saved on disk or loaded into the Java virtual
 * machine. Each instance of {@code ByteBuddy} is immutable where any of the factory methods returns a new instance
 * that represents the altered configuration.
 * <p>&nbsp;</p>
 * Note that any configuration defines to ignore the instrumentation of any synthetic methods or the default finalizer
 * method {@link Object#finalize()}. This behavior can be altered by
 * {@link net.bytebuddy.ByteBuddy#withIgnoredMethods(net.bytebuddy.instrumentation.method.matcher.MethodMatcher)}.
 */
public class ByteBuddy {

    private static final String BYTE_BUDDY_DEFAULT_PREFIX = "ByteBuddy";

    /**
     * Any definable instance is either {@link net.bytebuddy.ByteBuddy.Definable.Defined} when a value is provided
     * or {@link net.bytebuddy.ByteBuddy.Definable.Undefined} if a value is not provided. A defined definable will
     * return its defined value on request while an undefined definable will return the provided default.
     *
     * @param <T> The type of the definable object.
     */
    public static interface Definable<T> {

        /**
         * A representation of an undefined {@link net.bytebuddy.ByteBuddy.Definable}.
         *
         * @param <T> The type of the definable object.
         */
        static class Undefined<T> implements Definable<T> {

            @Override
            public T resolve(T defaultValue) {
                return defaultValue;
            }

            @Override
            public boolean equals(Object other) {
                return other != null && other instanceof Undefined;
            }

            @Override
            public int hashCode() {
                return 31;
            }
        }

        /**
         * A representation of a defined {@link net.bytebuddy.ByteBuddy.Definable} for a given value.
         *
         * @param <T> The type of the definable object.
         */
        static class Defined<T> implements Definable<T> {

            private final T value;

            /**
             * Creates a new defined instance for the given value.
             *
             * @param value The defined value.
             */
            public Defined(T value) {
                this.value = value;
            }

            @Override
            public T resolve(T defaultValue) {
                return value;
            }

            @Override
            public boolean equals(Object o) {
                return this == o || !(o == null || getClass() != o.getClass())
                        && value.equals(((Defined) o).value);
            }

            @Override
            public int hashCode() {
                return value.hashCode();
            }

            @Override
            public String toString() {
                return "Defined{value=" + value + '}';
            }
        }

        /**
         * Returns the value of this instance or the provided default value for an undefined definable.
         *
         * @param defaultValue The default value that is returned for an {@link net.bytebuddy.ByteBuddy.Definable.Undefined}
         *                     definable.
         * @return The value that is represented by this instance.
         */
        T resolve(T defaultValue);
    }

    /**
     * Implementations of this interface are capable of defining a method interception for a given set of methods.
     */
    public static interface MethodInterceptable {

        /**
         * Intercepts the given method with the given instrumentation.
         *
         * @param instrumentation The instrumentation to apply to the selected methods.
         * @return A method annotation target for this instance with the given instrumentation applied to the
         * current selection.
         */
        MethodAnnotationTarget intercept(Instrumentation instrumentation);

        /**
         * Defines the currently selected methods as {@code abstract}.
         *
         * @return A method annotation target for this instance with implementing the currently selected methods
         * as {@code abstract}.
         */
        MethodAnnotationTarget withoutCode();
    }

    /**
     * A {@link net.bytebuddy.ByteBuddy} configuration with a selected set of methods for which annotations can
     * be defined.
     */
    public static class MethodAnnotationTarget extends ByteBuddy {

        /**
         * The method matcher representing the current method selection.
         */
        protected final MethodMatcher methodMatcher;

        /**
         * The instrumentation that was defined for the current method selection.
         */
        protected final Instrumentation instrumentation;

        /**
         * The method attribute appender factory that was defined for the current method selection.
         */
        protected final MethodAttributeAppender.Factory attributeAppenderFactory;

        /**
         * Creates a new method annotation target.
         *
         * @param classFormatVersion                    The currently defined class format version.
         * @param namingStrategy                        The currently defined naming strategy.
         * @param interfaceTypes                        The currently defined collection of interfaces to be implemented
         *                                              by any dynamically created type.
         * @param ignoredMethods                        The methods to always be ignored.
         * @param bridgeMethodResolverFactory           The bridge method resolver factory to be applied to any instrumentation
         *                                              process.
         * @param classVisitorWrapperChain              The class visitor wrapper chain to be applied to any instrumentation
         *                                              process.
         * @param methodRegistry                        The currently valid method registry.
         * @param modifiers                             The modifiers to define for any instrumentation process.
         * @param typeAttributeAppender                 The type attribute appender to apply to any instrumentation process.
         * @param defaultFieldAttributeAppenderFactory  The field attribute appender to apply as a default for any field
         *                                              definition.
         * @param defaultMethodAttributeAppenderFactory The method attribute appender to apply as a default for any
         *                                              method definition or instrumentation.
         * @param methodMatcher                         The method matcher representing the current method selection.
         * @param instrumentation                       The instrumentation that was defined for the current method
         *                                              selection.
         * @param attributeAppenderFactory              The method attribute appender factory that was defined for the
         *                                              current method selection.
         */
        protected MethodAnnotationTarget(ClassFormatVersion classFormatVersion,
                                         NamingStrategy namingStrategy,
                                         List<TypeDescription> interfaceTypes,
                                         MethodMatcher ignoredMethods,
                                         BridgeMethodResolver.Factory bridgeMethodResolverFactory,
                                         ClassVisitorWrapper.Chain classVisitorWrapperChain,
                                         MethodRegistry methodRegistry,
                                         Definable<Integer> modifiers,
                                         Definable<TypeAttributeAppender> typeAttributeAppender,
                                         FieldAttributeAppender.Factory defaultFieldAttributeAppenderFactory,
                                         MethodAttributeAppender.Factory defaultMethodAttributeAppenderFactory,
                                         MethodMatcher methodMatcher,
                                         Instrumentation instrumentation,
                                         MethodAttributeAppender.Factory attributeAppenderFactory) {
            super(classFormatVersion,
                    namingStrategy,
                    interfaceTypes,
                    ignoredMethods,
                    bridgeMethodResolverFactory,
                    classVisitorWrapperChain,
                    methodRegistry,
                    modifiers,
                    typeAttributeAppender,
                    defaultFieldAttributeAppenderFactory,
                    defaultMethodAttributeAppenderFactory);
            this.methodMatcher = methodMatcher;
            this.instrumentation = instrumentation;
            this.attributeAppenderFactory = attributeAppenderFactory;
        }

        /**
         * Defines a given attribute appender factory to be applied for the currently selected methods.
         *
         * @param attributeAppenderFactory The method attribute appender factory to apply to the currently
         *                                 selected methods.
         * @return A method annotation target that represents the current configuration with the additional
         * attribute appender factory applied to the current method selection.
         */
        public MethodAnnotationTarget attribute(MethodAttributeAppender.Factory attributeAppenderFactory) {
            return new MethodAnnotationTarget(classFormatVersion,
                    namingStrategy,
                    interfaceTypes,
                    ignoredMethods,
                    bridgeMethodResolverFactory,
                    classVisitorWrapperChain,
                    methodRegistry,
                    modifiers,
                    typeAttributeAppender,
                    defaultFieldAttributeAppenderFactory,
                    defaultMethodAttributeAppenderFactory,
                    methodMatcher,
                    instrumentation,
                    new MethodAttributeAppender.Factory.Compound(this.attributeAppenderFactory, nonNull(attributeAppenderFactory)));
        }

        /**
         * Defines an method annotation for the currently selected methods.
         *
         * @param annotation The annotations to defined for the currently selected methods.
         * @return A method annotation target that represents the current configuration with the additional
         * annotations added to the currently selected methods.
         */
        public MethodAnnotationTarget annotateMethod(Annotation... annotation) {
            return attribute(new MethodAttributeAppender.ForAnnotation(nonNull(annotation)));
        }

        /**
         * Defines an method annotation for a parameter of the currently selected methods.
         *
         * @param parameterIndex The index of the parameter for which the annotations should be applied
         *                       with the first parameter index by {@code 0}.
         * @param annotation     The annotations to defined for the currently selected methods' parameters
         *                       ath the given index.
         * @return A method annotation target that represents the current configuration with the additional
         * annotations added to the currently selected methods' parameters at the given index.
         */
        public MethodAnnotationTarget annotateParameter(int parameterIndex, Annotation... annotation) {
            return attribute(new MethodAttributeAppender.ForAnnotation(parameterIndex, nonNull(annotation)));
        }

        @Override
        public ClassFormatVersion getClassFormatVersion() {
            return materialize().getClassFormatVersion();
        }

        @Override
        public NamingStrategy getNamingStrategy() {
            return materialize().getNamingStrategy();
        }

        @Override
        public List<TypeDescription> getInterfaceTypes() {
            return materialize().getInterfaceTypes();
        }

        @Override
        public MethodMatcher getIgnoredMethods() {
            return materialize().getIgnoredMethods();
        }

        @Override
        public ClassVisitorWrapper.Chain getClassVisitorWrapperChain() {
            return materialize().getClassVisitorWrapperChain();
        }

        @Override
        public FieldAttributeAppender.Factory getDefaultFieldAttributeAppenderFactory() {
            return materialize().getDefaultFieldAttributeAppenderFactory();
        }

        @Override
        public MethodAttributeAppender.Factory getDefaultMethodAttributeAppenderFactory() {
            return materialize().getDefaultMethodAttributeAppenderFactory();
        }

        @Override
        public <T> DynamicType.Builder<T> subclass(Class<T> superType) {
            return materialize().subclass(superType);
        }

        @Override
        public <T> DynamicType.Builder<T> subclass(Class<T> superType, ConstructorStrategy constructorStrategy) {
            return materialize().subclass(superType, constructorStrategy);
        }

        @Override
        public <T> DynamicType.Builder<T> subclass(TypeDescription superType) {
            return materialize().subclass(superType);
        }

        @Override
        public <T> DynamicType.Builder<T> subclass(TypeDescription superType, ConstructorStrategy constructorStrategy) {
            return materialize().subclass(superType, constructorStrategy);
        }

        @Override
        public ByteBuddy withModifiers(ModifierContributor.ForType... modifierContributor) {
            return materialize().withModifiers(modifierContributor);
        }

        @Override
        public ByteBuddy withAttribute(TypeAttributeAppender typeAttributeAppender) {
            return materialize().withAttribute(typeAttributeAppender);
        }

        @Override
        public ByteBuddy withTypeAnnotation(Annotation... annotation) {
            return materialize().withTypeAnnotation(annotation);
        }

        @Override
        public ByteBuddy withClassFormatVersion(ClassFormatVersion classFormatVersion) {
            return materialize().withClassFormatVersion(classFormatVersion);
        }

        @Override
        public ByteBuddy withNamingStrategy(NamingStrategy namingStrategy) {
            return materialize().withNamingStrategy(namingStrategy);
        }

        @Override
        public OptionalMethodInterception withImplementing(Class<?> type) {
            return materialize().withImplementing(type);
        }

        @Override
        public OptionalMethodInterception withImplementing(TypeDescription type) {
            return materialize().withImplementing(type);
        }

        @Override
        public ByteBuddy withIgnoredMethods(MethodMatcher ignoredMethods) {
            return materialize().withIgnoredMethods(ignoredMethods);
        }

        @Override
        public ByteBuddy withClassVisitor(ClassVisitorWrapper classVisitorWrapper) {
            return materialize().withClassVisitor(classVisitorWrapper);
        }

        @Override
        public ByteBuddy withDefaultFieldAttributeAppender(FieldAttributeAppender.Factory attributeAppenderFactory) {
            return materialize().withDefaultFieldAttributeAppender(attributeAppenderFactory);
        }

        @Override
        public ByteBuddy withDefaultMethodAttributeAppender(MethodAttributeAppender.Factory attributeAppenderFactory) {
            return materialize().withDefaultMethodAttributeAppender(attributeAppenderFactory);
        }

        @Override
        public MatchedMethodInterception invokable(MethodMatcher methodMatcher) {
            return materialize().invokable(methodMatcher);
        }

        /**
         * Materializes this configuration as new {@code ByteBuddy} instance.
         *
         * @return A {@code ByteBuddy} instance representing the current configuration.
         */
        protected ByteBuddy materialize() {
            return new ByteBuddy(classFormatVersion,
                    namingStrategy,
                    interfaceTypes,
                    ignoredMethods,
                    bridgeMethodResolverFactory,
                    classVisitorWrapperChain,
                    methodRegistry.prepend(new MethodRegistry.LatentMethodMatcher.Simple(methodMatcher),
                            instrumentation,
                            attributeAppenderFactory),
                    modifiers,
                    typeAttributeAppender,
                    defaultFieldAttributeAppenderFactory,
                    defaultMethodAttributeAppenderFactory
            );
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (other == null || getClass() != other.getClass()) return false;
            if (!super.equals(other)) return false;
            MethodAnnotationTarget that = (MethodAnnotationTarget) other;
            return attributeAppenderFactory.equals(that.attributeAppenderFactory)
                    && instrumentation.equals(that.instrumentation)
                    && methodMatcher.equals(that.methodMatcher);
        }

        @Override
        public int hashCode() {
            int result = super.hashCode();
            result = 31 * result + methodMatcher.hashCode();
            result = 31 * result + instrumentation.hashCode();
            result = 31 * result + attributeAppenderFactory.hashCode();
            return result;
        }

        @Override
        public String toString() {
            return "ByteBuddy.MethodAnnotationTarget{" +
                    "base=" + super.toString() +
                    ", methodMatcher=" + methodMatcher +
                    ", instrumentation=" + instrumentation +
                    ", attributeAppenderFactory=" + attributeAppenderFactory +
                    '}';
        }
    }

    /**
     * An optional method interception that allows to intercept a method selection only if this is needed.
     */
    public class OptionalMethodInterception extends ByteBuddy implements MethodInterceptable {

        /**
         * The method matcher that defines the selected that is represented by this instance.
         */
        protected final MethodMatcher methodMatcher;

        /**
         * Creates a new optional method interception.
         *
         * @param classFormatVersion                    The currently defined class format version.
         * @param namingStrategy                        The currently defined naming strategy.
         * @param interfaceTypes                        The currently defined collection of interfaces to be implemented
         *                                              by any dynamically created type.
         * @param ignoredMethods                        The methods to always be ignored.
         * @param bridgeMethodResolverFactory           The bridge method resolver factory to be applied to any instrumentation
         *                                              process.
         * @param classVisitorWrapperChain              The class visitor wrapper chain to be applied to any instrumentation
         *                                              process.
         * @param methodRegistry                        The currently valid method registry.
         * @param modifiers                             The modifiers to define for any instrumentation process.
         * @param typeAttributeAppender                 The type attribute appender to apply to any instrumentation process.
         * @param defaultFieldAttributeAppenderFactory  The field attribute appender to apply as a default for any field
         *                                              definition.
         * @param defaultMethodAttributeAppenderFactory The method attribute appender to apply as a default for any
         *                                              method definition or instrumentation.
         * @param methodMatcher                         The method matcher representing the current method selection.
         */
        protected OptionalMethodInterception(ClassFormatVersion classFormatVersion,
                                             NamingStrategy namingStrategy,
                                             List<TypeDescription> interfaceTypes,
                                             MethodMatcher ignoredMethods,
                                             BridgeMethodResolver.Factory bridgeMethodResolverFactory,
                                             ClassVisitorWrapper.Chain classVisitorWrapperChain,
                                             MethodRegistry methodRegistry,
                                             Definable<Integer> modifiers,
                                             Definable<TypeAttributeAppender> typeAttributeAppender,
                                             FieldAttributeAppender.Factory defaultFieldAttributeAppenderFactory,
                                             MethodAttributeAppender.Factory defaultMethodAttributeAppenderFactory,
                                             MethodMatcher methodMatcher) {
            super(classFormatVersion,
                    namingStrategy,
                    interfaceTypes,
                    ignoredMethods,
                    bridgeMethodResolverFactory,
                    classVisitorWrapperChain,
                    methodRegistry,
                    modifiers,
                    typeAttributeAppender,
                    defaultFieldAttributeAppenderFactory,
                    defaultMethodAttributeAppenderFactory);
            this.methodMatcher = methodMatcher;
        }

        @Override
        public MethodAnnotationTarget intercept(Instrumentation instrumentation) {
            return new MatchedMethodInterception(methodMatcher).intercept(instrumentation);
        }

        @Override
        public MethodAnnotationTarget withoutCode() {
            return new MatchedMethodInterception(methodMatcher).withoutCode();
        }

        @Override
        public boolean equals(Object other) {
            return this == other || !(other == null || getClass() != other.getClass())
                    && ByteBuddy.this.equals(((OptionalMethodInterception) other).getByteBuddy())
                    && methodMatcher.equals(((OptionalMethodInterception) other).methodMatcher);
        }

        @Override
        public int hashCode() {
            return 31 * methodMatcher.hashCode() + ByteBuddy.this.hashCode();
        }

        @Override
        public String toString() {
            return "ByteBuddy.OptionalMethodInterception{" +
                    "methodMatcher=" + methodMatcher +
                    "byteBuddy=" + ByteBuddy.this.toString() +
                    '}';
        }

        private ByteBuddy getByteBuddy() {
            return ByteBuddy.this;
        }
    }

    /**
     * A matched method interception for a non-optional method definition.
     */
    public class MatchedMethodInterception implements MethodInterceptable {

        /**
         * A method matcher that represents the current method selection.
         */
        protected final MethodMatcher methodMatcher;

        /**
         * Creates a new matched method interception.
         *
         * @param methodMatcher The method matcher representing the current method selection.
         */
        protected MatchedMethodInterception(MethodMatcher methodMatcher) {
            this.methodMatcher = methodMatcher;
        }

        @Override
        public MethodAnnotationTarget intercept(Instrumentation instrumentation) {
            return new MethodAnnotationTarget(classFormatVersion,
                    namingStrategy,
                    interfaceTypes,
                    ignoredMethods,
                    bridgeMethodResolverFactory,
                    classVisitorWrapperChain,
                    methodRegistry,
                    modifiers,
                    typeAttributeAppender,
                    defaultFieldAttributeAppenderFactory,
                    defaultMethodAttributeAppenderFactory,
                    methodMatcher,
                    nonNull(instrumentation),
                    MethodAttributeAppender.NoOp.INSTANCE);
        }

        @Override
        public MethodAnnotationTarget withoutCode() {
            return intercept(Instrumentation.ForAbstractMethod.INSTANCE);
        }

        @Override
        public boolean equals(Object other) {
            return this == other || !(other == null || getClass() != other.getClass())
                    && ByteBuddy.this.equals(((MatchedMethodInterception) other).getByteBuddy())
                    && methodMatcher.equals(((MatchedMethodInterception) other).methodMatcher);
        }

        @Override
        public int hashCode() {
            return 31 * methodMatcher.hashCode() + ByteBuddy.this.hashCode();
        }

        @Override
        public String toString() {
            return "ByteBuddy.MatchedMethodInterception{" +
                    "methodMatcher=" + methodMatcher +
                    "byteBuddy=" + ByteBuddy.this.toString() +
                    '}';
        }

        private ByteBuddy getByteBuddy() {
            return ByteBuddy.this;
        }
    }

    /**
     * The class formation version of the current configuration.
     */
    protected final ClassFormatVersion classFormatVersion;

    /**
     * The naming strategy of the current configuration.
     */
    protected final NamingStrategy namingStrategy;

    /**
     * A list of interface types to be implemented by any class that is implemented by the current configuration.
     */
    protected final List<TypeDescription> interfaceTypes;

    /**
     * A matcher for identifying methods that should never be intercepted.
     */
    protected final MethodMatcher ignoredMethods;

    /**
     * The factory for generating a bridge method resolver for the current configuration.
     */
    protected final BridgeMethodResolver.Factory bridgeMethodResolverFactory;

    /**
     * The class visitor wrapper chain for the current configuration.
     */
    protected final ClassVisitorWrapper.Chain classVisitorWrapperChain;

    /**
     * The method registry for the current configuration.
     */
    protected final MethodRegistry methodRegistry;

    /**
     * The modifiers to apply to any type that is generated by this configuration.
     */
    protected final Definable<Integer> modifiers;

    /**
     * The type attribute appender factory to apply to any type that is generated by this configuration.
     */
    protected final Definable<TypeAttributeAppender> typeAttributeAppender;

    /**
     * The default field attribute appender factory which is applied to any field that is defined
     * for instrumentations that are applied by this configuration.
     */
    protected final FieldAttributeAppender.Factory defaultFieldAttributeAppenderFactory;

    /**
     * The default method attribute appender factory which is applied to any method that is defined
     * or intercepted for instrumentations that are applied by this configuration.
     */
    protected final MethodAttributeAppender.Factory defaultMethodAttributeAppenderFactory;

    /**
     * Defines a new {@code ByteBuddy} default configuration for the current Java virtual machine's
     * class format version.
     */
    public ByteBuddy() {
        this(ClassFormatVersion.forCurrentJavaVersion());
    }

    /**
     * Defines a new {@code ByteBuddy} default configuration for the given class format version.
     *
     * @param classFormatVersion The class format version to apply.
     */
    public ByteBuddy(ClassFormatVersion classFormatVersion) {
        this(classFormatVersion,
                new NamingStrategy.SuffixingRandom(BYTE_BUDDY_DEFAULT_PREFIX),
                new TypeList.Empty(),
                isDefaultFinalizer().or(isSynthetic()),
                BridgeMethodResolver.Simple.Factory.FAIL_ON_REQUEST,
                new ClassVisitorWrapper.Chain(),
                new MethodRegistry.Default(),
                new Definable.Undefined<Integer>(),
                new Definable.Undefined<TypeAttributeAppender>(),
                FieldAttributeAppender.NoOp.INSTANCE,
                MethodAttributeAppender.NoOp.INSTANCE);
    }

    /**
     * Defines a new {@code ByteBuddy} configuration.
     *
     * @param classFormatVersion                    The currently defined class format version.
     * @param namingStrategy                        The currently defined naming strategy.
     * @param interfaceTypes                        The currently defined collection of interfaces to be implemented
     *                                              by any dynamically created type.
     * @param ignoredMethods                        The methods to always be ignored.
     * @param bridgeMethodResolverFactory           The bridge method resolver factory to be applied to any instrumentation
     *                                              process.
     * @param classVisitorWrapperChain              The class visitor wrapper chain to be applied to any instrumentation
     *                                              process.
     * @param methodRegistry                        The currently valid method registry.
     * @param modifiers                             The modifiers to define for any instrumentation process.
     * @param typeAttributeAppender                 The type attribute appender to apply to any instrumentation process.
     * @param defaultFieldAttributeAppenderFactory  The field attribute appender to apply as a default for any field
     *                                              definition.
     * @param defaultMethodAttributeAppenderFactory The method attribute appender to apply as a default for any
     *                                              method definition or instrumentation.
     */
    protected ByteBuddy(ClassFormatVersion classFormatVersion,
                        NamingStrategy namingStrategy,
                        List<TypeDescription> interfaceTypes,
                        MethodMatcher ignoredMethods,
                        BridgeMethodResolver.Factory bridgeMethodResolverFactory,
                        ClassVisitorWrapper.Chain classVisitorWrapperChain,
                        MethodRegistry methodRegistry,
                        Definable<Integer> modifiers,
                        Definable<TypeAttributeAppender> typeAttributeAppender,
                        FieldAttributeAppender.Factory defaultFieldAttributeAppenderFactory,
                        MethodAttributeAppender.Factory defaultMethodAttributeAppenderFactory) {
        this.classFormatVersion = classFormatVersion;
        this.namingStrategy = namingStrategy;
        this.interfaceTypes = interfaceTypes;
        this.ignoredMethods = ignoredMethods;
        this.bridgeMethodResolverFactory = bridgeMethodResolverFactory;
        this.classVisitorWrapperChain = classVisitorWrapperChain;
        this.methodRegistry = methodRegistry;
        this.modifiers = modifiers;
        this.typeAttributeAppender = typeAttributeAppender;
        this.defaultFieldAttributeAppenderFactory = defaultFieldAttributeAppenderFactory;
        this.defaultMethodAttributeAppenderFactory = defaultMethodAttributeAppenderFactory;
    }

    /**
     * Returns the class format version that is defined for the current configuration.
     *
     * @return The class format version that is defined for this configuration.
     */
    public ClassFormatVersion getClassFormatVersion() {
        return classFormatVersion;
    }

    /**
     * Returns the naming strategy for the current configuration.
     *
     * @return The naming strategy for the current configuration.
     */
    public NamingStrategy getNamingStrategy() {
        return namingStrategy;
    }

    /**
     * Returns the naming strategy for the current configuration.
     *
     * @return The naming strategy for the current configuration.
     */
    public List<TypeDescription> getInterfaceTypes() {
        return Collections.unmodifiableList(interfaceTypes);
    }

    /**
     * Returns the matcher for the ignored methods for the current configuration.
     *
     * @return The matcher for the ignored methods for the current configuration.
     */
    public MethodMatcher getIgnoredMethods() {
        return ignoredMethods;
    }

    /**
     * Returns the factory for the bridge method resolver for the current configuration.
     *
     * @return The factory for the bridge method resolver for the current configuration.
     */
    public BridgeMethodResolver.Factory getBridgeMethodResolverFactory() {
        return bridgeMethodResolverFactory;
    }

    /**
     * Returns the class visitor wrapper chain for the current configuration.
     *
     * @return The class visitor wrapper chain for the current configuration.
     */
    public ClassVisitorWrapper.Chain getClassVisitorWrapperChain() {
        return classVisitorWrapperChain;
    }

    /**
     * Returns the method registry for the current configuration.
     *
     * @return The method registry for the current configuration.
     */
    public MethodRegistry getMethodRegistry() {
        return methodRegistry;
    }

    /**
     * Returns the modifiers to apply to any type that is generated by this configuration.
     *
     * @return The modifiers to apply to any type that is generated by this configuration.
     */
    public Definable<Integer> getModifiers() {
        return modifiers;
    }

    /**
     * Returns the type attribute appender factory to apply to any type that is generated by this configuration.
     *
     * @return The type attribute appender factory to apply to any type that is generated by this configuration.
     */
    public Definable<TypeAttributeAppender> getTypeAttributeAppender() {
        return typeAttributeAppender;
    }

    /**
     * Returns the default field attribute appender factory which is applied to any field that is defined
     * for instrumentations that are applied by this configuration.
     *
     * @return The default field attribute appender factory which is applied to any field that is defined
     * for instrumentations that are applied by this configuration.
     */
    public FieldAttributeAppender.Factory getDefaultFieldAttributeAppenderFactory() {
        return defaultFieldAttributeAppenderFactory;
    }

    /**
     * Returns the default method attribute appender factory which is applied to any method that is defined
     * or intercepted for instrumentations that are applied by this configuration.
     *
     * @return The default method attribute appender factory which is applied to any method that is defined
     * or intercepted for instrumentations that are applied by this configuration.
     */
    public MethodAttributeAppender.Factory getDefaultMethodAttributeAppenderFactory() {
        return defaultMethodAttributeAppenderFactory;
    }

    /**
     * Creates a dynamic type builder that creates a subclass of a given loaded type where the subclass
     * is created by the {@link net.bytebuddy.dynamic.scaffold.subclass.ConstructorStrategy.Default#IMITATE_SUPER_TYPE}
     * strategy.
     *
     * @param superType The type or interface to be extended or implemented by the dynamic type.
     * @param <T>       The most specific known type that the created dynamic type represents.
     * @return A dynamic type builder for this configuration that extends or implements the given loaded type.
     */
    public <T> DynamicType.Builder<T> subclass(Class<T> superType) {
        return subclass(superType, ConstructorStrategy.Default.IMITATE_SUPER_TYPE);
    }

    /**
     * Creates a dynamic type builder that creates a subclass of a given loaded type.
     *
     * @param superType The type or interface to be extended or implemented by the dynamic type.
     * @param <T>       The most specific known type that the created dynamic type represents.
     * @return A dynamic type builder for this configuration that extends or implements the given loaded type.
     */
    public <T> DynamicType.Builder<T> subclass(Class<T> superType, ConstructorStrategy constructorStrategy) {
        return subclass(new TypeDescription.ForLoadedType(superType), constructorStrategy);
    }

    /**
     * Creates a dynamic type builder that creates a subclass of a given type description where the subclass
     * is created by the {@link net.bytebuddy.dynamic.scaffold.subclass.ConstructorStrategy.Default#IMITATE_SUPER_TYPE}
     * strategy.
     *
     * @param superType The type or interface to be extended or implemented by the dynamic type.
     * @param <T>       The most specific known type that the created dynamic type represents.
     * @return A dynamic type builder for this configuration that extends or implements the given type description.
     */
    public <T> DynamicType.Builder<T> subclass(TypeDescription superType) {
        return subclass(superType, ConstructorStrategy.Default.IMITATE_SUPER_TYPE);
    }

    /**
     * Creates a dynamic type builder that creates a subclass of a given type description.
     *
     * @param superType The type or interface to be extended or implemented by the dynamic type.
     * @param <T>       The most specific known type that the created dynamic type represents.
     * @return A dynamic type builder for this configuration that extends or implements the given type description.
     */
    public <T> DynamicType.Builder<T> subclass(TypeDescription superType, ConstructorStrategy constructorStrategy) {
        TypeDescription actualSuperType = isImplementable(superType);
        List<TypeDescription> interfaceTypes = this.interfaceTypes;
        if (nonNull(superType).isInterface()) {
            actualSuperType = new TypeDescription.ForLoadedType(Object.class);
            interfaceTypes = join(superType, interfaceTypes);
        }
        return new SubclassDynamicTypeBuilder<T>(classFormatVersion,
                namingStrategy,
                actualSuperType,
                interfaceTypes,
                modifiers.resolve(superType.getModifiers()),
                typeAttributeAppender.resolve(TypeAttributeAppender.NoOp.INSTANCE),
                ignoredMethods,
                bridgeMethodResolverFactory,
                classVisitorWrapperChain,
                new FieldRegistry.Default(),
                methodRegistry,
                defaultFieldAttributeAppenderFactory,
                defaultMethodAttributeAppenderFactory,
                nonNull(constructorStrategy));
    }

    /**
     * Defines a new class format version for this configuration.
     *
     * @param classFormatVersion The class format version to define for this configuration.
     * @return A new configuration that represents this configuration with the given class format version.
     */
    public ByteBuddy withClassFormatVersion(ClassFormatVersion classFormatVersion) {
        return new ByteBuddy(nonNull(classFormatVersion),
                namingStrategy,
                interfaceTypes,
                ignoredMethods,
                bridgeMethodResolverFactory,
                classVisitorWrapperChain,
                methodRegistry,
                modifiers,
                typeAttributeAppender,
                defaultFieldAttributeAppenderFactory,
                defaultMethodAttributeAppenderFactory);
    }

    /**
     * Defines a new naming strategy for this configuration.
     *
     * @param namingStrategy The class format version to define for this configuration.
     * @return A new configuration that represents this configuration with the given class format version.
     */
    public ByteBuddy withNamingStrategy(NamingStrategy namingStrategy) {
        return new ByteBuddy(classFormatVersion,
                nonNull(namingStrategy),
                interfaceTypes,
                ignoredMethods,
                bridgeMethodResolverFactory,
                classVisitorWrapperChain,
                methodRegistry,
                modifiers,
                typeAttributeAppender,
                defaultFieldAttributeAppenderFactory,
                defaultMethodAttributeAppenderFactory);
    }

    /**
     * Defines a new modifier contributors for this configuration that replaces the currently defined modifier
     * contributes which might currently be implicit.
     *
     * @param modifierContributor The modifier contributors to define explicitly for this configuration.
     * @return A new configuration that represents this configuration with the given modifier contributors.
     */
    public ByteBuddy withModifiers(ModifierContributor.ForType... modifierContributor) {
        return new ByteBuddy(classFormatVersion,
                namingStrategy,
                interfaceTypes,
                ignoredMethods,
                bridgeMethodResolverFactory,
                classVisitorWrapperChain,
                methodRegistry,
                new Definable.Defined<Integer>(resolveModifierContributors(TYPE_MODIFIER_MASK, nonNull(modifierContributor))),
                typeAttributeAppender,
                defaultFieldAttributeAppenderFactory,
                defaultMethodAttributeAppenderFactory);
    }

    /**
     * Defines a new type attribute appender for this configuration that replaces the currently defined type
     * attribute appender.
     *
     * @param typeAttributeAppender The type attribute appender to define for this configuration.
     * @return A new configuration that represents this configuration with the given type attribute appender.
     */
    public ByteBuddy withAttribute(TypeAttributeAppender typeAttributeAppender) {
        return new ByteBuddy(classFormatVersion,
                namingStrategy,
                interfaceTypes,
                ignoredMethods,
                bridgeMethodResolverFactory,
                classVisitorWrapperChain,
                methodRegistry,
                modifiers,
                new Definable.Defined<TypeAttributeAppender>(nonNull(typeAttributeAppender)),
                defaultFieldAttributeAppenderFactory,
                defaultMethodAttributeAppenderFactory);
    }

    /**
     * Defines a new type annotation for this configuration that replaces the currently defined type
     * attribute appender.
     *
     * @param annotation The type annotations to define for this configuration.
     * @return A new configuration that represents this configuration with the given annotations as its new
     * type attribute appender.
     */
    public ByteBuddy withTypeAnnotation(Annotation... annotation) {
        return new ByteBuddy(classFormatVersion,
                namingStrategy,
                interfaceTypes,
                ignoredMethods,
                bridgeMethodResolverFactory,
                classVisitorWrapperChain,
                methodRegistry,
                modifiers,
                new Definable.Defined<TypeAttributeAppender>(new TypeAttributeAppender.ForAnnotation(nonNull(annotation))),
                defaultFieldAttributeAppenderFactory,
                defaultMethodAttributeAppenderFactory);
    }

    /**
     * Defines all dynamic types that are created by this configuration to implement the given interface.
     *
     * @param type The interface type to implement.
     * @return This configuration where any dynamic type that is created by the resulting configuration will
     * implement the given interface.
     */
    public OptionalMethodInterception withImplementing(Class<?> type) {
        return withImplementing(new TypeDescription.ForLoadedType(nonNull(type)));
    }

    /**
     * Defines all dynamic types that are created by this configuration to implement the given interface.
     *
     * @param type The interface type to implement.
     * @return This configuration where any dynamic type that is created by the resulting configuration will
     * implement the given interface.
     */
    public OptionalMethodInterception withImplementing(TypeDescription type) {
        return new OptionalMethodInterception(classFormatVersion,
                namingStrategy,
                join(interfaceTypes, isInterface(nonNull(type))),
                ignoredMethods,
                bridgeMethodResolverFactory,
                classVisitorWrapperChain,
                methodRegistry,
                modifiers,
                typeAttributeAppender,
                defaultFieldAttributeAppenderFactory,
                defaultMethodAttributeAppenderFactory,
                isDeclaredBy(type));
    }

    /**
     * Defines a new method matcher for methods that are ignored by any dynamic type that is created by this
     * configuration which will replace the current configuration. By default, this method matcher is defined
     * to ignore instrumenting synthetic methods and the default finalizer method.
     *
     * @param ignoredMethods The methods to always be ignored for any instrumentation.
     * @return A new configuration that represents this configuration with the given method matcher defining methods
     * that are to be ignored for any instrumentation.
     */
    public ByteBuddy withIgnoredMethods(MethodMatcher ignoredMethods) {
        return new ByteBuddy(classFormatVersion,
                namingStrategy,
                interfaceTypes,
                nonNull(ignoredMethods),
                bridgeMethodResolverFactory,
                classVisitorWrapperChain,
                methodRegistry,
                modifiers,
                typeAttributeAppender,
                defaultFieldAttributeAppenderFactory,
                defaultMethodAttributeAppenderFactory);
    }

    /**
     * Defines a new class visitor to be appended to the current collection of {@link org.objectweb.asm.ClassVisitor}s
     * that are to be applied onto any creation process of a dynamic type.
     *
     * @param classVisitorWrapper The class visitor wrapper to ba appended to the current chain of class visitor wrappers.
     * @return This configuration with the given class visitor wrapper to be applied onto any creation process of a dynamic
     * type.
     */
    public ByteBuddy withClassVisitor(ClassVisitorWrapper classVisitorWrapper) {
        return new ByteBuddy(classFormatVersion,
                namingStrategy,
                interfaceTypes,
                ignoredMethods,
                bridgeMethodResolverFactory,
                classVisitorWrapperChain.append(nonNull(classVisitorWrapper)),
                methodRegistry,
                modifiers,
                typeAttributeAppender,
                defaultFieldAttributeAppenderFactory,
                defaultMethodAttributeAppenderFactory);
    }

    /**
     * Defines a new default field attribute appender factory that is applied onto any field.
     *
     * @param attributeAppenderFactory The attribute appender factory that is applied as a default on any
     *                                 field that is created by a dynamic type that is created with this
     *                                 configuration.
     * @return This configuration with the given field attribute appender factory to be applied as a default to
     * the creation process of any field of a dynamic type.
     */
    public ByteBuddy withDefaultFieldAttributeAppender(FieldAttributeAppender.Factory attributeAppenderFactory) {
        return new ByteBuddy(classFormatVersion,
                namingStrategy,
                interfaceTypes,
                ignoredMethods,
                bridgeMethodResolverFactory,
                classVisitorWrapperChain,
                methodRegistry,
                modifiers,
                typeAttributeAppender,
                nonNull(attributeAppenderFactory),
                defaultMethodAttributeAppenderFactory);
    }

    /**
     * Defines a new default method attribute appender factory that is applied onto any method.
     *
     * @param attributeAppenderFactory The attribute appender factory that is applied as a default on any
     *                                 method that is created or intercepted by a dynamic type that is created
     *                                 with this configuration.
     * @return This configuration with the given method attribute appender factory to be applied as a default to
     * the creation or interception process of any method of a dynamic type.
     */
    public ByteBuddy withDefaultMethodAttributeAppender(MethodAttributeAppender.Factory attributeAppenderFactory) {
        return new ByteBuddy(classFormatVersion,
                namingStrategy,
                interfaceTypes,
                ignoredMethods,
                bridgeMethodResolverFactory,
                classVisitorWrapperChain,
                methodRegistry,
                modifiers,
                typeAttributeAppender,
                defaultFieldAttributeAppenderFactory,
                nonNull(attributeAppenderFactory));
    }

    /**
     * Intercepts a given selection of byte code methods that can be a method or a constructor.
     *
     * @param methodMatcher The method matcher representing all byte code methods to intercept.
     * @return A matched method interception for the given selection.
     */
    public MatchedMethodInterception invokable(MethodMatcher methodMatcher) {
        return new MatchedMethodInterception(nonNull(methodMatcher));
    }

    /**
     * Intercepts a given method selection
     *
     * @param methodMatcher The method matcher representing all methods to intercept.
     * @return A matched method interception for the given selection.
     */
    public MatchedMethodInterception method(MethodMatcher methodMatcher) {
        return invokable(isMethod().and(nonNull(methodMatcher)));
    }

    /**
     * Intercepts a given constructor selection
     *
     * @param methodMatcher The method matcher representing all constructors to intercept.
     * @return A matched method interception for the given selection.
     */
    public MatchedMethodInterception constructor(MethodMatcher methodMatcher) {
        return invokable(isConstructor().and(nonNull(methodMatcher)));
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (other == null || getClass() != other.getClass()) return false;
        ByteBuddy byteBuddy = (ByteBuddy) other;
        return classFormatVersion.equals(byteBuddy.classFormatVersion)
                && classVisitorWrapperChain.equals(byteBuddy.classVisitorWrapperChain)
                && defaultFieldAttributeAppenderFactory.equals(byteBuddy.defaultFieldAttributeAppenderFactory)
                && defaultMethodAttributeAppenderFactory.equals(byteBuddy.defaultMethodAttributeAppenderFactory)
                && ignoredMethods.equals(byteBuddy.ignoredMethods)
                && bridgeMethodResolverFactory.equals(byteBuddy.bridgeMethodResolverFactory)
                && interfaceTypes.equals(byteBuddy.interfaceTypes)
                && methodRegistry.equals(byteBuddy.methodRegistry)
                && modifiers.equals(byteBuddy.modifiers)
                && namingStrategy.equals(byteBuddy.namingStrategy)
                && typeAttributeAppender.equals(byteBuddy.typeAttributeAppender);
    }

    @Override
    public int hashCode() {
        int result = classFormatVersion.hashCode();
        result = 31 * result + namingStrategy.hashCode();
        result = 31 * result + interfaceTypes.hashCode();
        result = 31 * result + ignoredMethods.hashCode();
        result = 31 * result + bridgeMethodResolverFactory.hashCode();
        result = 31 * result + classVisitorWrapperChain.hashCode();
        result = 31 * result + methodRegistry.hashCode();
        result = 31 * result + modifiers.hashCode();
        result = 31 * result + typeAttributeAppender.hashCode();
        result = 31 * result + defaultFieldAttributeAppenderFactory.hashCode();
        result = 31 * result + defaultMethodAttributeAppenderFactory.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "ByteBuddy{" +
                "classFormatVersion=" + classFormatVersion +
                ", namingStrategy=" + namingStrategy +
                ", interfaceTypes=" + interfaceTypes +
                ", ignoredMethods=" + ignoredMethods +
                ", bridgeMethodResolverFactory=" + bridgeMethodResolverFactory +
                ", classVisitorWrapperChain=" + classVisitorWrapperChain +
                ", methodRegistry=" + methodRegistry +
                ", modifiers=" + modifiers +
                ", typeAttributeAppender=" + typeAttributeAppender +
                ", defaultFieldAttributeAppenderFactory=" + defaultFieldAttributeAppenderFactory +
                ", defaultMethodAttributeAppenderFactory=" + defaultMethodAttributeAppenderFactory +
                '}';
    }
}
