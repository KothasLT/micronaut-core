package org.particleframework.ast.groovy

import groovy.transform.CompilationUnitAware
import groovy.transform.CompileStatic
import groovy.transform.PackageScope
import org.codehaus.groovy.ast.*
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilePhase
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.transform.ASTTransformation
import org.codehaus.groovy.transform.GroovyASTTransformation
import org.particleframework.aop.Around
import org.particleframework.aop.Interceptor
import org.particleframework.aop.Introduction
import org.particleframework.aop.writer.AopProxyWriter
import org.particleframework.ast.groovy.annotation.GroovyAnnotationMetadataBuilder
import org.particleframework.ast.groovy.utils.AstAnnotationUtils
import org.particleframework.ast.groovy.utils.AstGenericUtils
import org.particleframework.ast.groovy.utils.AstMessageUtils
import org.particleframework.ast.groovy.utils.PublicMethodVisitor
import org.particleframework.context.annotation.*
import org.particleframework.core.annotation.AnnotationMetadata
import org.particleframework.core.annotation.Internal
import org.particleframework.core.naming.NameUtils
import org.particleframework.core.util.ArrayUtils
import org.particleframework.core.value.OptionalValues
import org.particleframework.inject.annotation.AnnotationMetadataReference
import org.particleframework.inject.writer.*

import javax.annotation.PostConstruct
import javax.annotation.PreDestroy
import javax.inject.*
import java.lang.reflect.Modifier

import static org.codehaus.groovy.ast.ClassHelper.makeCached
import static org.codehaus.groovy.ast.tools.GeneralUtils.getSetterName

/**
 * An AST transformation that produces metadata for use by the injection container
 *
 * @author Graeme Rocher
 * @since 1.0
 */
@CompileStatic
@GroovyASTTransformation(phase = CompilePhase.SEMANTIC_ANALYSIS)
class InjectTransform implements ASTTransformation, CompilationUnitAware {

    CompilationUnit unit

    @Override
    void visit(ASTNode[] nodes, SourceUnit source) {
        ModuleNode moduleNode = source.getAST()
        Map<AnnotatedNode, BeanDefinitionVisitor> beanDefinitionWriters = [:]

        List<ClassNode> classes = moduleNode.getClasses()
        if (classes.size() == 1) {
            ClassNode classNode = classes[0]
            if (classNode.nameWithoutPackage == 'package-info') {
                PackageNode packageNode = classNode.getPackage()
                if (AstAnnotationUtils.hasStereotype(packageNode, Configuration)) {
                    BeanConfigurationWriter writer = new BeanConfigurationWriter(classNode.packageName, AstAnnotationUtils.getAnnotationMetadata(packageNode))
                    try {
                        writer.writeTo(source.configuration.targetDirectory)
                    } catch (Throwable e) {
                        AstMessageUtils.error(source, classNode, "Error generating bean configuration for package-info class [${classNode.name}]: $e.message")
                    }
                }

                return
            }
        }

        for (ClassNode classNode in classes) {
            if ((classNode instanceof InnerClassNode && !Modifier.isStatic(classNode.getModifiers()))) {
                continue
            } else if (classNode.isAbstract()) {
                if (AstAnnotationUtils.hasStereotype(classNode, InjectVisitor.INTRODUCTION_TYPE)) {
                    InjectVisitor injectVisitor = new InjectVisitor(source, classNode)
                    injectVisitor.visitClass(classNode)
                    beanDefinitionWriters.putAll(injectVisitor.beanDefinitionWriters)
                }
            } else {

                InjectVisitor injectVisitor = new InjectVisitor(source, classNode)
                injectVisitor.visitClass(classNode)
                beanDefinitionWriters.putAll(injectVisitor.beanDefinitionWriters)
            }
        }



        for (entry in beanDefinitionWriters) {
            BeanDefinitionVisitor beanDefWriter = entry.value
            File classesDir = source.configuration.targetDirectory
            String beanTypeName = beanDefWriter.beanTypeName
            AnnotatedNode beanClassNode = entry.key
            try {
                String beanDefinitionName = beanDefWriter.beanDefinitionName
                BeanDefinitionReferenceWriter beanReferenceWriter = new BeanDefinitionReferenceWriter(beanTypeName, beanDefinitionName, beanDefWriter.annotationMetadata)
                beanReferenceWriter.setContextScope(AstAnnotationUtils.hasStereotype(beanClassNode, Context))

                Optional<String> replacesOpt = AstAnnotationUtils.getAnnotationMetadata(beanClassNode).getValue(Replaces, String.class)
                if (replacesOpt.isPresent()) {
                    beanReferenceWriter.setReplaceBeanName(replacesOpt.get())
                }
                beanReferenceWriter.writeTo(classesDir)
                beanDefWriter.visitBeanDefinitionEnd()
                beanDefWriter.writeTo(classesDir)

            } catch (Throwable e) {
                AstMessageUtils.error(source, beanClassNode, "Error generating bean definition class for dependency injection of class [${beanTypeName}]: $e.message")
                if (e.message == null) {
                    e.printStackTrace(System.err)
                }
            }


        }
    }

    @Override
    void setCompilationUnit(CompilationUnit unit) {
        this.unit = unit
    }

    private static class InjectVisitor extends ClassCodeVisitorSupport {
        public static final String AROUND_TYPE = "org.particleframework.aop.Around"
        public static final String INTRODUCTION_TYPE = "org.particleframework.aop.Introduction"
        final SourceUnit sourceUnit
        final ClassNode concreteClass
        final AnnotationMetadata annotationMetadata
        final boolean isConfigurationProperties
        final boolean isFactoryClass
        final boolean isExecutableType
        final boolean isAopProxyType
        final OptionalValues<Boolean> aopSettings

        final Map<AnnotatedNode, BeanDefinitionVisitor> beanDefinitionWriters = [:]
        BeanDefinitionVisitor beanWriter
        BeanDefinitionVisitor aopProxyWriter

        InjectVisitor(SourceUnit sourceUnit, ClassNode targetClassNode) {
            this(sourceUnit, targetClassNode, null)
        }

        InjectVisitor(SourceUnit sourceUnit, ClassNode targetClassNode, Boolean configurationProperties) {
            this.sourceUnit = sourceUnit
            this.concreteClass = targetClassNode
            this.annotationMetadata = AstAnnotationUtils.getAnnotationMetadata(targetClassNode)
            this.isFactoryClass = annotationMetadata.hasStereotype(Factory)
            this.isAopProxyType = annotationMetadata.hasStereotype(AROUND_TYPE)
            this.aopSettings = isAopProxyType ? annotationMetadata.getValues(AROUND_TYPE, Boolean.class) : OptionalValues.<Boolean> empty()
            this.isExecutableType = isAopProxyType || annotationMetadata.hasStereotype(Executable)
            this.isConfigurationProperties = configurationProperties != null ? configurationProperties : isConfigurationProperties(annotationMetadata)
            if (isFactoryClass || isConfigurationProperties || annotationMetadata.hasStereotype(Bean, Scope)) {
                defineBeanDefinition(concreteClass)
            }
        }

        @Override
        void visitClass(ClassNode node) {
            AnnotationMetadata annotationMetadata = AstAnnotationUtils.getAnnotationMetadata(node)
            if (annotationMetadata.hasStereotype(INTRODUCTION_TYPE)) {
                String scopeType = annotationMetadata.getAnnotationNameByStereotype(Scope).orElse(null)
                String packageName = node.packageName
                String beanClassName = node.nameWithoutPackage

                Object[] aroundInterceptors = annotationMetadata
                        .getAnnotationNamesByStereotype(AROUND_TYPE)
                        .toArray()
                Object[] introductionInterceptors = annotationMetadata
                        .getAnnotationNamesByStereotype(Introduction.class)
                        .toArray()


                Object[] interceptorTypes = ArrayUtils.concat(aroundInterceptors, introductionInterceptors)

                boolean isSingleton = annotationMetadata.hasDeclaredStereotype(Singleton)
                boolean isInterface = node.isInterface()
                AopProxyWriter aopProxyWriter = new AopProxyWriter(
                        packageName,
                        beanClassName,
                        scopeType,
                        isInterface,
                        isSingleton,
                        annotationMetadata,
                        interceptorTypes)
                populateProxyWriterConstructor(node, aopProxyWriter)
                beanDefinitionWriters.put(node, aopProxyWriter)
                visitIntroductionTypePublicMethods(aopProxyWriter, node)
            } else {

                ClassNode superClass = node.getSuperClass()
                List<ClassNode> superClasses = []
                while (superClass != null) {
                    superClasses.add(superClass)
                    superClass = superClass.getSuperClass()
                }
                superClasses = superClasses.reverse()
                for (classNode in superClasses) {
                    if (classNode.name != ClassHelper.OBJECT_TYPE.name && classNode.name != GroovyObjectSupport.name && classNode.name != Script.name) {
                        classNode.visitContents(this)
                    }
                }
                super.visitClass(node)
            }
        }

        boolean isConfigurationProperties(AnnotationMetadata annotationMetadata) {
            if (annotationMetadata.hasDeclaredStereotype(ConfigurationReader)) {
                return true
            }
            return false
        }

        protected void visitIntroductionTypePublicMethods(AopProxyWriter aopProxyWriter, ClassNode node) {
            AnnotationMetadata typeAnnotationMetadata = aopProxyWriter.getAnnotationMetadata()
            PublicMethodVisitor publicMethodVisitor = new PublicMethodVisitor(sourceUnit) {

                @Override
                void accept(MethodNode methodNode) {
                    Map<String, Object> targetMethodParamsToType = [:]
                    Map<String, Object> targetMethodQualifierTypes = [:]
                    Map<String, Map<String, Object>> targetMethodGenericTypeMap = [:]
                    Object resolvedReturnType = AstGenericUtils.resolveTypeReference(methodNode.returnType)
                    Map<String, Object> resolvedGenericTypes = AstGenericUtils.buildGenericTypeInfo(methodNode.returnType)
                    populateParameterData(
                            methodNode.parameters,
                            targetMethodParamsToType,
                            targetMethodQualifierTypes,
                            targetMethodGenericTypeMap)


                    AnnotationMetadata annotationMetadata
                    if (AstAnnotationUtils.isAnnotated(methodNode)) {
                        annotationMetadata = AstAnnotationUtils.getAnnotationMetadata(node, methodNode);
                    } else {
                        annotationMetadata = new AnnotationMetadataReference(
                                aopProxyWriter.getBeanDefinitionName() + BeanDefinitionReferenceWriter.REF_SUFFIX,
                                typeAnnotationMetadata
                        )
                    }
                    aopProxyWriter.visitAroundMethod(
                            AstGenericUtils.resolveTypeReference(methodNode.declaringClass),
                            resolvedReturnType,
                            resolvedGenericTypes,
                            methodNode.name,
                            targetMethodParamsToType,
                            targetMethodQualifierTypes,
                            targetMethodGenericTypeMap,
                            annotationMetadata
                    )
                }

                @Override
                protected boolean isAcceptable(MethodNode methodNode
                ) {
                    return methodNode.isAbstract() && !methodNode.isFinal() && !methodNode.isStatic() && !methodNode.isSynthetic()
                }
            }
            publicMethodVisitor.accept(node)
        }


        @Override
        protected void visitConstructorOrMethod(MethodNode methodNode, boolean isConstructor) {
            String methodName = methodNode.name
            ClassNode declaringClass = methodNode.declaringClass
            AnnotationMetadata methodAnnotationMetadata = AstAnnotationUtils.getAnnotationMetadata(methodNode)
            if (isFactoryClass && !isConstructor && methodAnnotationMetadata.hasDeclaredStereotype(Bean, Scope)) {
                methodAnnotationMetadata = new GroovyAnnotationMetadataBuilder().buildForMethod(methodNode)
                ClassNode producedType = methodNode.returnType
                String scopeAnn = methodAnnotationMetadata.getAnnotationNameByStereotype(Scope).orElse(null)
                String beanDefinitionPackage = concreteClass.packageName
                String upperCaseMethodName = NameUtils.capitalize(methodNode.getName())
                String factoryMethodBeanDefinitionName =
                        beanDefinitionPackage + '.$' + concreteClass.nameWithoutPackage + '$' + upperCaseMethodName + "Definition"

                BeanDefinitionWriter beanMethodWriter = new BeanDefinitionWriter(
                        producedType.packageName,
                        producedType.nameWithoutPackage,
                        factoryMethodBeanDefinitionName,
                        producedType.name,
                        producedType.isInterface(),
                        scopeAnn,
                        methodAnnotationMetadata.hasDeclaredStereotype(Singleton),
                        methodAnnotationMetadata
                )

                Map<String, Object> paramsToType = [:]
                Map<String, Object> qualifierTypes = [:]
                Map<String, Map<String, Object>> genericTypeMap = [:]
                populateParameterData(methodNode.parameters, paramsToType, qualifierTypes, genericTypeMap)

                beanMethodWriter.visitBeanFactoryMethod(AstGenericUtils.resolveTypeReference(concreteClass), methodName, paramsToType, qualifierTypes, genericTypeMap)

                if (methodAnnotationMetadata.hasStereotype(AROUND_TYPE)) {
                    Object[] interceptorTypeReferences = methodAnnotationMetadata.getAnnotationNamesByStereotype(Around).toArray()
                    OptionalValues<Boolean> aopSettings = methodAnnotationMetadata.getValues(AROUND_TYPE, Boolean)
                    Map<CharSequence, Object> finalSettings = [:]
                    for (key in aopSettings) {
                        finalSettings.put(key, aopSettings.get(key).get())
                    }
                    finalSettings.put(Interceptor.PROXY_TARGET, true)

                    AopProxyWriter proxyWriter = new AopProxyWriter(
                            beanMethodWriter,
                            OptionalValues.of(Boolean.class, finalSettings),
                            interceptorTypeReferences)
                    if (producedType.isInterface()) {
                        proxyWriter.visitBeanDefinitionConstructor()
                    } else {
                        populateProxyWriterConstructor(producedType, proxyWriter)
                    }

                    new PublicMethodVisitor(sourceUnit) {


                        @Override
                        void accept(MethodNode targetBeanMethodNode) {
                            Map<String, Object> targetMethodParamsToType = [:]
                            Map<String, Object> targetMethodQualifierTypes = [:]
                            Map<String, Map<String, Object>> targetMethodGenericTypeMap = [:]
                            Object resolvedReturnType = AstGenericUtils.resolveTypeReference(targetBeanMethodNode.returnType)
                            Map<String, Object> returnTypeGenerics = AstGenericUtils.buildGenericTypeInfo(targetBeanMethodNode.returnType)
                            populateParameterData(
                                    targetBeanMethodNode.parameters,
                                    targetMethodParamsToType,
                                    targetMethodQualifierTypes,
                                    targetMethodGenericTypeMap)
                            AnnotationMetadata annotationMetadata
                            if (AstAnnotationUtils.isAnnotated(methodNode)) {
                                annotationMetadata = AstAnnotationUtils.getAnnotationMetadata(methodNode, targetBeanMethodNode);
                            } else {
                                annotationMetadata = new AnnotationMetadataReference(
                                        beanMethodWriter.getBeanDefinitionName() + BeanDefinitionReferenceWriter.REF_SUFFIX,
                                        methodAnnotationMetadata
                                )
                            }

                            ExecutableMethodWriter writer = beanMethodWriter.visitExecutableMethod(
                                    AstGenericUtils.resolveTypeReference(targetBeanMethodNode.declaringClass),
                                    resolvedReturnType,
                                    returnTypeGenerics,
                                    targetBeanMethodNode.name,
                                    targetMethodParamsToType,
                                    targetMethodQualifierTypes,
                                    targetMethodGenericTypeMap,
                                    annotationMetadata
                            )

                            proxyWriter.visitAroundMethod(
                                    AstGenericUtils.resolveTypeReference(targetBeanMethodNode.declaringClass),
                                    resolvedReturnType,
                                    returnTypeGenerics,
                                    targetBeanMethodNode.name,
                                    targetMethodParamsToType,
                                    targetMethodQualifierTypes,
                                    targetMethodGenericTypeMap,
                                    new AnnotationMetadataReference(writer.getClassName(), annotationMetadata)
                            )
                        }
                    }.accept(methodNode.getReturnType())
                    beanDefinitionWriters.put(new AnnotatedNode(), proxyWriter)

                }
                Optional<String> preDestroy = methodAnnotationMetadata.getValue(Bean, "preDestroy", String.class)
                if (preDestroy.isPresent()) {
                    String destroyMethodName = preDestroy.get()
                    MethodNode destroyMethod = producedType.getMethod(destroyMethodName)
                    if (destroyMethod != null) {
                        beanMethodWriter.visitPreDestroyMethod(destroyMethod.declaringClass.name, destroyMethodName)
                    }
                }
                beanDefinitionWriters.put(methodNode, beanMethodWriter)
            } else if (methodAnnotationMetadata.hasStereotype(Inject, PostConstruct, PreDestroy)) {
                defineBeanDefinition(concreteClass)

                if (!isConstructor) {
                    if (!methodNode.isStatic() && !methodNode.isAbstract()) {
                        boolean isParent = declaringClass != concreteClass
                        MethodNode overriddenMethod = isParent ? concreteClass.getMethod(methodName, methodNode.parameters) : methodNode
                        boolean overridden = isParent && overriddenMethod.declaringClass != declaringClass

                        boolean isPackagePrivate = isPackagePrivate(methodNode, methodNode.modifiers)
                        boolean isPrivate = methodNode.isPrivate()

                        if (isParent && !isPrivate && !isPackagePrivate) {

                            if (overridden) {
                                // bail out if the method has been overridden, since it will have already been handled
                                return
                            }
                        }
                        boolean packagesDiffer = overriddenMethod.declaringClass.packageName != declaringClass.packageName
                        boolean isPackagePrivateAndPackagesDiffer = overridden && packagesDiffer && isPackagePrivate
                        boolean requiresReflection = isPrivate || isPackagePrivateAndPackagesDiffer
                        boolean overriddenInjected = overridden && AstAnnotationUtils.hasStereotype(overriddenMethod, Inject)

                        if (isParent && isPackagePrivate && !isPackagePrivateAndPackagesDiffer && overriddenInjected) {
                            // bail out if the method has been overridden by another method annotated with @INject
                            return
                        }
                        if (isParent && overridden && !overriddenInjected && !isPackagePrivateAndPackagesDiffer && !isPrivate) {
                            // bail out if the overridden method is package private and in the same package
                            // and is not annotated with @Inject
                            return
                        }
                        if (!requiresReflection && isInheritedAndNotPublic(methodNode, declaringClass, methodNode.modifiers)) {
                            requiresReflection = true
                        }

                        Map<String, Object> paramsToType = [:]
                        Map<String, Object> qualifierTypes = [:]
                        Map<String, Map<String, Object>> genericTypeMap = [:]
                        populateParameterData(methodNode.parameters, paramsToType, qualifierTypes, genericTypeMap)

                        if (methodAnnotationMetadata.hasStereotype(PostConstruct.name)) {
                            beanWriter.visitPostConstructMethod(
                                    AstGenericUtils.resolveTypeReference(declaringClass),
                                    requiresReflection,
                                    AstGenericUtils.resolveTypeReference(methodNode.returnType),
                                    methodName,
                                    paramsToType,
                                    qualifierTypes,
                                    genericTypeMap)
                        } else if (methodAnnotationMetadata.hasStereotype(PreDestroy.name)) {
                            beanWriter.visitPreDestroyMethod(
                                    AstGenericUtils.resolveTypeReference(declaringClass),
                                    requiresReflection,
                                    AstGenericUtils.resolveTypeReference(methodNode.returnType),
                                    methodName,
                                    paramsToType,
                                    qualifierTypes,
                                    genericTypeMap)
                        } else {
                            beanWriter.visitMethodInjectionPoint(
                                    AstGenericUtils.resolveTypeReference(declaringClass),
                                    requiresReflection,
                                    AstGenericUtils.resolveTypeReference(methodNode.returnType),
                                    methodName,
                                    paramsToType,
                                    qualifierTypes,
                                    genericTypeMap)
                        }


                    }
                }
            } else if (!isConstructor) {
                boolean hasInvalidModifiers = methodNode.isStatic() || methodNode.isAbstract() || methodNode.isSynthetic() || methodAnnotationMetadata.hasAnnotation(Internal) || methodNode.isPrivate()
                boolean isPublic = methodNode.isPublic() && !hasInvalidModifiers
                boolean isExecutable = ((isExecutableType && isPublic) || methodAnnotationMetadata.hasStereotype(Executable)) && !hasInvalidModifiers
                if (isExecutable) {
                    if (declaringClass != ClassHelper.OBJECT_TYPE) {

                        defineBeanDefinition(concreteClass)
                        Map<String, Object> returnTypeGenerics = AstGenericUtils.buildGenericTypeInfo(methodNode.returnType)

                        Map<String, Object> paramsToType = [:]
                        Map<String, Object> qualifierTypes = [:]
                        Map<String, Map<String, Object>> genericTypeMap = [:]
                        populateParameterData(methodNode.parameters, paramsToType, qualifierTypes, genericTypeMap)

                        ExecutableMethodWriter executableMethodWriter = beanWriter.visitExecutableMethod(
                                AstGenericUtils.resolveTypeReference(methodNode.declaringClass),
                                AstGenericUtils.resolveTypeReference(methodNode.returnType),
                                returnTypeGenerics,
                                methodName,
                                paramsToType,
                                qualifierTypes,
                                genericTypeMap, methodAnnotationMetadata)

                        if ((isAopProxyType && isPublic) || methodAnnotationMetadata.hasStereotype(AROUND_TYPE)) {

                            Object[] interceptorTypeReferences = methodAnnotationMetadata.getAnnotationNamesByStereotype(Around).toArray()
                            OptionalValues<Boolean> aopSettings = methodAnnotationMetadata.getValues(AROUND_TYPE, Boolean)
                            AopProxyWriter proxyWriter = resolveProxyWriter(
                                    aopSettings,
                                    false,
                                    interceptorTypeReferences
                            )


                            if (proxyWriter != null && !methodNode.isFinal()) {

                                proxyWriter.visitInterceptorTypes(interceptorTypeReferences)
                                proxyWriter.visitAroundMethod(
                                        AstGenericUtils.resolveTypeReference(methodNode.declaringClass),
                                        AstGenericUtils.resolveTypeReference(methodNode.returnType),
                                        returnTypeGenerics,
                                        methodName,
                                        paramsToType,
                                        qualifierTypes,
                                        genericTypeMap,
                                        new AnnotationMetadataReference(executableMethodWriter.getClassName(), methodAnnotationMetadata)
                                )
                            }
                        }
                    }
                }
                if (isConfigurationProperties && isPublic && NameUtils.isSetterName(methodNode.name) && methodNode.parameters.length == 1) {
                    if (declaringClass.getField(NameUtils.getPropertyNameForSetter(methodNode.name)) == null) {

                        Parameter parameter = methodNode.parameters[0]

                        beanWriter.visitSetterValue(
                                AstGenericUtils.resolveTypeReference(methodNode.declaringClass),
                                resolveQualifier(parameter),
                                false,
                                resolveParameterType(parameter),
                                methodNode.name,
                                resolveGenericTypes(parameter),
                                true
                        )
                    }
                }
            }

        }

        private AopProxyWriter resolveProxyWriter(
                OptionalValues<Boolean> aopSettings,
                boolean isFactoryType,
                Object[] interceptorTypeReferences) {
            AopProxyWriter proxyWriter = (AopProxyWriter) aopProxyWriter
            if (proxyWriter == null) {

                proxyWriter = new AopProxyWriter(
                        (BeanDefinitionWriter) beanWriter,
                        aopSettings,
                        interceptorTypeReferences)


                ClassNode targetClass = concreteClass
                populateProxyWriterConstructor(targetClass, proxyWriter)
                String beanDefinitionName = beanWriter.getBeanDefinitionName()
                if (isFactoryType) {
                    proxyWriter
                            .visitSuperFactoryType(beanDefinitionName)
                } else {
                    proxyWriter
                            .visitSuperType(beanDefinitionName)
                }


                this.aopProxyWriter = proxyWriter

                def node = new AnnotatedNode()
                beanDefinitionWriters.put(node, proxyWriter)
            }
            proxyWriter
        }

        protected void populateProxyWriterConstructor(ClassNode targetClass, AopProxyWriter proxyWriter) {
            List<ConstructorNode> constructors = targetClass.getDeclaredConstructors()
            if (constructors.isEmpty()) {
                proxyWriter.visitBeanDefinitionConstructor()
            } else {
                ConstructorNode constructorNode = findConcreteConstructor(constructors)

                if (constructorNode != null) {
                    Map<String, Object> constructorParamsToType = [:]
                    Map<String, Object> constructorQualifierTypes = [:]
                    Map<String, Map<String, Object>> constructorGenericTypeMap = [:]
                    Parameter[] parameters = constructorNode.parameters
                    populateParameterData(parameters,
                            constructorParamsToType,
                            constructorQualifierTypes,
                            constructorGenericTypeMap)
                    proxyWriter.visitBeanDefinitionConstructor(constructorParamsToType, constructorQualifierTypes, constructorGenericTypeMap)


                } else {
                    addError("Class must have at least one public constructor in order to be a candidate for dependency injection", targetClass)
                }

            }
        }

        protected boolean isPackagePrivate(AnnotatedNode annotatedNode, int modifiers) {
            return ((!Modifier.isProtected(modifiers) && !Modifier.isPublic(modifiers) && !Modifier.isPrivate(modifiers)) || !annotatedNode.getAnnotations(makeCached(PackageScope)).isEmpty())
        }


        @Override
        void visitField(FieldNode fieldNode) {
            if (fieldNode.name == 'metaClass') return
            int modifiers = fieldNode.modifiers
            if (Modifier.isFinal(modifiers) || Modifier.isStatic(modifiers) || fieldNode.isSynthetic()) {
                return
            }
            ClassNode declaringClass = fieldNode.declaringClass
            AnnotationMetadata fieldAnnotationMetadata = AstAnnotationUtils.getAnnotationMetadata(fieldNode)
            boolean isInject = fieldAnnotationMetadata.hasStereotype(Inject)
            boolean isValue = !isInject && (fieldAnnotationMetadata.hasStereotype(Value) || isConfigurationProperties)

            if ((isInject || isValue) && declaringClass.getProperty(fieldNode.getName()) == null) {
                defineBeanDefinition(concreteClass)
                if (!fieldNode.isStatic()) {
                    Object qualifierRef = resolveQualifier(fieldNode)


                    boolean isPrivate = Modifier.isPrivate(modifiers)
                    boolean requiresReflection = isPrivate || isInheritedAndNotPublic(fieldNode, fieldNode.declaringClass, modifiers)
                    if (!beanWriter.isValidated()) {
                        if (fieldAnnotationMetadata.hasStereotype("javax.validation.Constraint")) {
                            beanWriter.setValidated(true)
                        }
                    }
                    if (isValue) {
                        beanWriter.visitFieldValue(
                                declaringClass.isResolved() ? declaringClass.typeClass : declaringClass.name, qualifierRef,
                                requiresReflection,
                                fieldNode.type.isResolved() ? fieldNode.type.typeClass : fieldNode.type.name,
                                fieldNode.name,
                                isConfigurationProperties
                        )
                    } else {
                        beanWriter.visitFieldInjectionPoint(
                                declaringClass.isResolved() ? declaringClass.typeClass : declaringClass.name, qualifierRef,
                                requiresReflection,
                                fieldNode.type.isResolved() ? fieldNode.type.typeClass : fieldNode.type.name,
                                fieldNode.name
                        )
                    }
                }
            }
        }

        Object resolveQualifier(AnnotatedNode annotatedNode) {
            return AstAnnotationUtils.getAnnotationMetadata(annotatedNode).getAnnotationNameByStereotype(Qualifier).orElse(null)
        }

        Object resolveParameterType(Parameter parameter) {
            ClassNode parameterType = parameter.type
            if (parameterType.isResolved()) {
                parameterType.typeClass
            } else {
                parameterType.name
            }
        }

        Map<String, Object> resolveGenericTypes(Parameter parameter) {
            ClassNode parameterType = parameter.type
            GenericsType[] genericsTypes = parameterType.genericsTypes
            if (genericsTypes != null && genericsTypes.length > 0) {
                AstGenericUtils.extractPlaceholders(parameterType)
            } else if (parameterType.isArray()) {
                Map<String, Object> genericTypeList = [:]
                genericTypeList.put('E', AstGenericUtils.resolveTypeReference(parameterType.componentType))
                genericTypeList
            }
        }

        @Override
        void visitProperty(PropertyNode propertyNode) {
            FieldNode fieldNode = propertyNode.field
            if (fieldNode.name == 'metaClass') return
            def modifiers = propertyNode.getModifiers()
            if (Modifier.isFinal(modifiers) || Modifier.isStatic(modifiers)) {
                return
            }
            AnnotationMetadata fieldAnnotationMetadata = AstAnnotationUtils.getAnnotationMetadata(fieldNode)
            boolean isInject = fieldNode != null && fieldAnnotationMetadata.hasStereotype(Inject)
            boolean isValue = !isInject && fieldNode != null && (fieldAnnotationMetadata.hasStereotype(Value) || isConfigurationProperties)
            if (!propertyNode.isStatic() && (isInject || isValue)) {
                defineBeanDefinition(concreteClass)
                Object qualifier = resolveQualifier(fieldNode)

                ClassNode fieldType = fieldNode.type

                GenericsType[] genericsTypes = fieldType.genericsTypes
                Map<String, Object> genericTypeList = null
                if (genericsTypes != null && genericsTypes.length > 0) {
                    genericTypeList = AstGenericUtils.buildGenericTypeInfo(fieldType)
                } else if (fieldType.isArray()) {
                    genericTypeList = [:]
                    genericTypeList.put(fieldNode.name, AstGenericUtils.resolveTypeReference(fieldType.componentType))
                }
                ClassNode declaringClass = fieldNode.declaringClass
                if (!beanWriter.isValidated()) {
                    if (fieldAnnotationMetadata.hasStereotype("javax.validation.Constraint")) {
                        beanWriter.setValidated(true)
                    }
                }

                if (isInject) {

                    beanWriter.visitSetterInjectionPoint(
                            AstGenericUtils.resolveTypeReference(declaringClass),
                            qualifier,
                            false,
                            AstGenericUtils.resolveTypeReference(fieldType),
                            fieldNode.name,
                            getSetterName(propertyNode.name),
                            genericTypeList
                    )
                } else if (isValue) {
                    beanWriter.visitSetterValue(
                            AstGenericUtils.resolveTypeReference(declaringClass),
                            qualifier,
                            false,
                            AstGenericUtils.resolveTypeReference(fieldType),
                            fieldNode.name,
                            getSetterName(propertyNode.name),
                            genericTypeList,
                            isConfigurationProperties
                    )
                }
            } else if (isAopProxyType && !propertyNode.isStatic()) {
                AopProxyWriter aopWriter = (AopProxyWriter) aopProxyWriter
                if (aopProxyWriter != null) {
                    Map<String, Map<String, Object>> resolvedGenericTypes = [(propertyNode.name): AstGenericUtils.extractPlaceholders(propertyNode.type)]
                    Map<String, Object> resolvedArguments = [(propertyNode.name): AstGenericUtils.resolveTypeReference(propertyNode.type)]
                    Object qualifier = resolveQualifier(propertyNode.field)
                    Map<String, Object> resolvedQualifiers
                    if (qualifier != null) {
                        resolvedQualifiers = [(propertyNode.name): qualifier]
                    } else {
                        resolvedQualifiers = Collections.emptyMap()
                    }
                    aopWriter.visitAroundMethod(
                            propertyNode.getDeclaringClass().name,
                            void.class,
                            Collections.emptyMap(),
                            getSetterName(propertyNode.name),
                            resolvedArguments,
                            resolvedQualifiers,
                            resolvedGenericTypes,
                            fieldAnnotationMetadata
                    )
                }
            }
        }


        protected boolean isInheritedAndNotPublic(AnnotatedNode annotatedNode, ClassNode declaringClass, int modifiers) {
            return declaringClass != concreteClass &&
                    declaringClass.packageName != concreteClass.packageName &&
                    ((Modifier.isProtected(modifiers) || !Modifier.isPublic(modifiers)) || !annotatedNode.getAnnotations(makeCached(PackageScope)).isEmpty())
        }

        @Override
        protected SourceUnit getSourceUnit() {
            return sourceUnit
        }

        private void defineBeanDefinition(ClassNode classNode) {
            if (!beanDefinitionWriters.containsKey(classNode) && !classNode.isAbstract()) {
                ClassNode providerGenericType = AstGenericUtils.resolveInterfaceGenericType(classNode, Provider)
                boolean isProvider = providerGenericType != null
                AnnotationMetadata annotationMetadata = AstAnnotationUtils.getAnnotationMetadata(classNode)

                if (isProvider) {
                    beanWriter = new BeanDefinitionWriter(
                            classNode.packageName,
                            classNode.nameWithoutPackage,
                            providerGenericType.name,
                            classNode.isInterface(),
                            annotationMetadata.getAnnotationNameByStereotype(Scope).orElse(null),
                            annotationMetadata.hasDeclaredStereotype(Singleton), annotationMetadata)
                } else {

                    beanWriter = new BeanDefinitionWriter(
                            classNode.packageName,
                            classNode.nameWithoutPackage,
                            annotationMetadata.getAnnotationNameByStereotype(Scope).orElse(null),
                            annotationMetadata.hasDeclaredStereotype(Singleton), annotationMetadata)
                }
                beanDefinitionWriters.put(classNode, beanWriter)



                List<ConstructorNode> constructors = classNode.getDeclaredConstructors()

                if (constructors.isEmpty()) {
                    beanWriter.visitBeanDefinitionConstructor(Collections.emptyMap(), null, null)

                } else {
                    ConstructorNode constructorNode = findConcreteConstructor(constructors)
                    if (constructorNode != null) {
                        Map<String, Object> paramsToType = [:]
                        Map<String, Object> qualifierTypes = [:]
                        Map<String, Map<String, Object>> genericTypeMap = [:]
                        Parameter[] parameters = constructorNode.parameters
                        populateParameterData(parameters, paramsToType, qualifierTypes, genericTypeMap)
                        beanWriter.visitBeanDefinitionConstructor(paramsToType, qualifierTypes, genericTypeMap)
                    } else {
                        addError("Class must have at least one public constructor in order to be a candidate for dependency injection", classNode)
                    }
                }

                if (isAopProxyType) {
                    Object[] interceptorTypeReferences = annotationMetadata.getAnnotationNamesByStereotype(Around).toArray()
                    resolveProxyWriter(aopSettings, false, interceptorTypeReferences)
                }

            } else if (!classNode.isAbstract()) {
                beanWriter = beanDefinitionWriters.get(classNode)
            }
        }

        private ConstructorNode findConcreteConstructor(List<ConstructorNode> constructors) {
            List<ConstructorNode> publicConstructors = findPublicConstructors(constructors)

            ConstructorNode constructorNode
            if (publicConstructors.size() == 1) {
                constructorNode = publicConstructors[0]
            } else {
                constructorNode = publicConstructors.find() { it.getAnnotations(makeCached(Inject)) }
            }
            constructorNode
        }

        private void populateParameterData(Parameter[] parameters, Map<String, Object> paramsToType, Map<String, Object> qualifierTypes, Map<String, Map<String, Object>> genericTypeMap) {
            for (param in parameters) {
                String parameterName = param.name

                paramsToType.put(parameterName, resolveParameterType(param))

                Object qualifier = resolveQualifier(param)
                if (qualifier != null) {
                    qualifierTypes.put(parameterName, qualifier)
                }

                genericTypeMap.put(parameterName, resolveGenericTypes(param))
            }
        }


        private List<ConstructorNode> findPublicConstructors(List<ConstructorNode> constructorNodes) {
            List<ConstructorNode> publicConstructors = []
            for (node in constructorNodes) {
                if (Modifier.isPublic(node.modifiers)) {
                    publicConstructors.add(node)
                }
            }
            return publicConstructors
        }
    }

}