package com.alibaba.testable.agent.transformer;

import com.alibaba.testable.agent.constant.ConstPool;
import com.alibaba.testable.agent.handler.SourceClassHandler;
import com.alibaba.testable.agent.handler.TestClassHandler;
import com.alibaba.testable.agent.model.CachedMockParameter;
import com.alibaba.testable.agent.tool.ImmutablePair;
import com.alibaba.testable.agent.model.MethodInfo;
import com.alibaba.testable.agent.tool.ComparableWeakRef;
import com.alibaba.testable.agent.util.AnnotationUtil;
import com.alibaba.testable.agent.util.ClassUtil;
import com.alibaba.testable.core.util.LogUtil;
import com.alibaba.testable.core.model.MockDiagnose;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.net.URLClassLoader;
import java.security.ProtectionDomain;
import java.util.*;

import static com.alibaba.testable.agent.util.ClassUtil.toDotSeparateFullClassName;

/**
 * @author flin
 */
public class TestableClassTransformer implements ClassFileTransformer {

    private static final String FIELD_DIAGNOSE = "diagnose";
    private final Map<ComparableWeakRef<String>, CachedMockParameter> loadedClass =
        new WeakHashMap<ComparableWeakRef<String>, CachedMockParameter>();

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classFileBuffer) {
        if (isSystemClass(loader, className) || loadedClass.containsKey(new ComparableWeakRef<String>(className))) {
            // Ignore system class and reloaded class
            return null;
        }
        byte[] bytes = null;
        try {
            if (shouldTransformAsSourceClass(className)) {
                // it's a source class with testable enabled
                LogUtil.diagnose("Handling source class %s", className);
                List<MethodInfo> injectMethods = getTestableMockMethods(ClassUtil.getTestClassName(className));
                bytes = new SourceClassHandler(injectMethods).getBytes(classFileBuffer);
                resetMockContext();
            } else if (shouldTransformAsTestClass(className)) {
                // it's a test class with testable enabled
                LogUtil.diagnose("Handling test class %s", className);
                bytes = new TestClassHandler().getBytes(classFileBuffer);
                resetMockContext();
            }
        } catch (IOException e) {
            LogUtil.warn("Failed to transform class " + className);
        }
        return bytes;
    }

    private boolean shouldTransformAsSourceClass(String className) {
        return hasMockAnnotation(ClassUtil.getTestClassName(className));
    }

    private boolean shouldTransformAsTestClass(String className) {
        return className.endsWith(ConstPool.TEST_POSTFIX) && hasMockAnnotation(className);
    }

    private boolean isSystemClass(ClassLoader loader, String className) {
        // className can be null for Java 8 lambdas
        return !(loader instanceof URLClassLoader) || null == className || className.startsWith("jdk/");
    }

    private List<MethodInfo> getTestableMockMethods(String className) {
        try {
            List<MethodInfo> methodInfos = new ArrayList<MethodInfo>();
            ClassNode cn = new ClassNode();
            new ClassReader(className).accept(cn, 0);
            for (MethodNode mn : cn.methods) {
                checkMethodAnnotation(cn, methodInfos, mn);
            }
            LogUtil.diagnose("  Found %d mock methods", methodInfos.size());
            return methodInfos;
        } catch (Exception e) {
            return new ArrayList<MethodInfo>();
        }
    }

    private void checkMethodAnnotation(ClassNode cn, List<MethodInfo> methodInfos, MethodNode mn) {
        ImmutablePair<String, String> methodDescPair = extractFirstParameter(mn.desc);
        if (methodDescPair == null || mn.visibleAnnotations == null) {
            return;
        }
        for (AnnotationNode an : mn.visibleAnnotations) {
            if (toDotSeparateFullClassName(an.desc).equals(ConstPool.TESTABLE_MOCK)) {
                String targetClass = ClassUtil.toSlashSeparateFullClassName(methodDescPair.left);
                String targetMethod = AnnotationUtil.getAnnotationParameter(
                    an, ConstPool.FIELD_TARGET_METHOD, mn.name, String.class);
                if (targetMethod.equals(ConstPool.CONSTRUCTOR)) {
                    String sourceClassName = ClassUtil.getSourceClassName(cn.name);
                    methodInfos.add(new MethodInfo(sourceClassName, targetMethod, mn.name, mn.desc));
                } else {
                    methodInfos.add(new MethodInfo(targetClass, targetMethod, mn.name, methodDescPair.right));
                }
                break;
            }
        }
    }

    /**
     * Check whether any method in specified class has specified annotation
     * @param className class that need to explore
     * @return found annotation or not
     */
    private boolean hasMockAnnotation(String className) {
        CachedMockParameter cache = loadedClass.get(new ComparableWeakRef<String>(className));
        if (cache != null) {
            setupMockContext(cache.getMockWith());
            return cache.isClassExist();
        }
        try {
            ClassNode cn = new ClassNode();
            new ClassReader(className).accept(cn, 0);
            if (cn.visibleAnnotations != null) {
                for (AnnotationNode an : cn.visibleAnnotations) {
                    if (toDotSeparateFullClassName(an.desc).equals(ConstPool.MOCK_WITH)) {
                        setupMockContext(an);
                        loadedClass.put(new ComparableWeakRef<String>(className), CachedMockParameter.exist(an));
                        return true;
                    }
                }
            }
            for (MethodNode mn : cn.methods) {
                if (mn.visibleAnnotations != null) {
                    for (AnnotationNode an : mn.visibleAnnotations) {
                        if (toDotSeparateFullClassName(an.desc).equals(ConstPool.TESTABLE_MOCK)) {
                            loadedClass.put(new ComparableWeakRef<String>(className), CachedMockParameter.exist());
                            return true;
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Usually class not found, return without record
            return false;
        }
        loadedClass.put(new ComparableWeakRef<String>(className), CachedMockParameter.notExist());
        return false;
    }

    private void setupMockContext(AnnotationNode an) {
        MockDiagnose diagnose = AnnotationUtil.getAnnotationParameter(an, FIELD_DIAGNOSE, null, MockDiagnose.class);
        if (diagnose != null) {
            LogUtil.enableDiagnose(diagnose == MockDiagnose.ENABLE);
        }
    }

    private void resetMockContext() {
        LogUtil.resetLogLevel();
    }

    /**
     * Split desc to "first parameter" and "desc of rest parameters"
     * @param desc method desc
     */
    private ImmutablePair<String, String> extractFirstParameter(String desc) {
        // assume first parameter is a class
        int pos = desc.indexOf(";");
        return pos < 0 ? null : ImmutablePair.of(desc.substring(1, pos + 1), "(" + desc.substring(pos + 1));
    }

}
