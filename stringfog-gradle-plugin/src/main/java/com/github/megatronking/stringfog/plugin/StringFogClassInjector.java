/*
 * Copyright (C) 2017, Megatron King
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.github.megatronking.stringfog.plugin;

import com.github.megatronking.stringfog.IKeyGenerator;
import com.github.megatronking.stringfog.IStringFog;
import com.github.megatronking.stringfog.StringFogWrapper;
import com.github.megatronking.stringfog.plugin.utils.Log;
import com.github.megatronking.stringfog.plugin.utils.TextUtils;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import kotlin.Pair;

public final class StringFogClassInjector {

    private final String[] mFogPackages;
    private String mFogClassName;
    private final String mPackage;
    private final IKeyGenerator mKeyGenerator;
    private final IStringFog mStringFogImpl;
    private final StringFogMode mMode;
    private final StringFogMappingPrinter mMappingPrinter;
    private final Map<ClassNode, File> classNodeMap = new HashMap<>();

    public StringFogClassInjector(String[] fogPackages, IKeyGenerator kg, String implementation,
                                  StringFogMode mode, String fogClassName, StringFogMappingPrinter mappingPrinter) {
        this.mFogPackages = fogPackages;
        this.mKeyGenerator = kg;
        this.mStringFogImpl = new StringFogWrapper(implementation);
        this.mMode = mode;
        this.mFogClassName = fogClassName;
        this.mPackage = fogClassName.substring(0, fogClassName.lastIndexOf(".")).replace(".", File.separator);
        this.mMappingPrinter = mappingPrinter;
    }

    public void doFog2Class(File fileIn, File fileOut) throws IOException {
        InputStream is = null;
        OutputStream os = null;
        try {
            is = new BufferedInputStream(new FileInputStream(fileIn));
            os = new BufferedOutputStream(new FileOutputStream(fileOut));
            processClass(is, os);
        } finally {
            closeQuietly(os);
            closeQuietly(is);
        }
    }

    public void startDoFog2Class(File fileIn, File fileOut) throws IOException {
        InputStream is = null;
        try {
            is = new BufferedInputStream(new FileInputStream(fileIn));

            ClassReader cr = new ClassReader(is);
            ClassNode cv = new ClassNode();
            processClass(cr, cv);

            classNodeMap.put(cv, fileOut);
        } finally {
            closeQuietly(is);
        }
    }


    private Random random = new Random();

    public int minNum = 3;
    public int maxNum = minNum + 5;
    public boolean enableProguard = true;

    public String[] proguardSourcePackages;
    public String[] proguardTargetPackages;

    private String getOwnName(String own, String name, String descriptor) {
        return own + "#" + name + "#" + descriptor;
    }

    private static boolean isInPackages(String[] packages, String className) {
        if (TextUtils.isEmpty(className)) {
            return false;
        }
        if (packages == null || packages.length == 0) {
            // default we fog all packages.
            return true;
        }

        String replace = className.replace('/', '.');
        for (String pkg : packages) {
            if (replace.equals(pkg) || replace.startsWith(pkg + ".") || replace.matches(pkg)) {
                return true;
            }
        }
        return false;
    }

    private <K, V> V getMutexValueFromMap(Map<K, V> map, K... keys) {
        for (K key : keys) {
            if (map.containsKey(key)) {
                return map.get(key);
            }
        }

        return null;
    }

    public void endDoFog2Class(File dicOut) {
        if (!enableProguard) {
            writeClassNodeToFile(classNodeMap, null);
            return;
        }

        // 1. 根据配置随机生成几个temp classNode
        if (maxNum < minNum) {
            maxNum += minNum;
        }
        int cwCount = randomRange(minNum, maxNum);
        Map<ClassNode, File> tempClassNodeMap = new HashMap();
        Map<ClassNode, File> allClassNodeMap = new HashMap();
        for (int i = 0; i < cwCount; i++) {
            String fileName = mPackage + File.separator + "TempClass" + i;
            File file = new File(dicOut, fileName + ".class");
            ClassNode classNode = TemplateClassNodeFactory.create(fileName);
            tempClassNodeMap.put(classNode, file);
            allClassNodeMap.put(classNode, file);
        }

        // 2. 遍历静态方法，将方法预分配
        // 目前直接将所有静态方法往其他地方塞
        Map<String, Pair<String, String>> remapNodeMap = new HashMap();

        classNodeMap.forEach((classNode, file) -> {
            ClassNode newNode;
            if (!isInPackages(proguardSourcePackages, classNode.name)) {
                Log.v("isExcluePackage classNode.name = " + classNode.name);
                newNode = classNode;
            } else {
                newNode = new ClassNode();

                //需要加上signature 唯一确定一个方法和变量
                //类本身的 static  类型的 <clinit>不应该移调
                //static field如果有赋值，会发生在 <clinit> 方法中，注意要根据field切换位置，还要对应类里 <clinit>
                classNode.accept(new ClassVisitor(Opcodes.ASM7, newNode) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                        if ((access & Opcodes.ACC_STATIC) != 0 && !name.equals("<clinit>")) {
                            access |= Opcodes.ACC_PUBLIC;
                            access &= ~Opcodes.ACC_PRIVATE & ~Opcodes.ACC_PROTECTED;
                            ClassNode tempNode = randomGet(new ArrayList<>(tempClassNodeMap.keySet()));


                            String ownName = getOwnName(classNode.name, name, descriptor);
                            Pair<String, String> toPair = new Pair<>(tempNode.name, name);
                            remapNodeMap.put(ownName, toPair);
                            Log.v(ownName + " => " + toPair);
                            mMappingPrinter.outputMapNode(classNode.name, name, descriptor, toPair.toString());
                            return tempNode.visitMethod(access, name, descriptor, signature, exceptions);
                        }

                        return super.visitMethod(access, name, descriptor, signature, exceptions);
                    }

                    @Override
                    public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                        if ((access & Opcodes.ACC_STATIC) != 0) {
                            access |= Opcodes.ACC_PUBLIC;
                            access &= ~Opcodes.ACC_PRIVATE & ~Opcodes.ACC_PROTECTED;
                        }
                        //todo 记录field的操作

                        return super.visitField(access, name, descriptor, signature, value);
                    }
                });
            }

            if (isInPackages(proguardTargetPackages, classNode.name)) {
                tempClassNodeMap.put(newNode, file);
            }
            allClassNodeMap.put(newNode, file);
        });

        String replace = mFogClassName.replace(".", "/");
        // 后续jar包的字符串加密还涉及到该方法，暂时提前映射掉
        Pair<String, String> pair = getMutexValueFromMap(remapNodeMap, replace + "#decrypt#(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", replace + "#decrypt#([B[B)Ljava/lang/String;");
        if (pair != null) {
            Log.v("replace fogClassName " + pair);
            mFogClassName = pair.getFirst();
        }

        // 3. 遍历所有ClassNode => ClassWriter, 更改visitMethodInsn
        // 4. 遍历CLassWriter => 写入File
        writeClassNodeToFile(allClassNodeMap, remapNodeMap);

    }

    private void writeClassNodeToFile(Map<ClassNode, File> tempClassNodeMap, Map<String, Pair<String, String>> remapNodeMap) {
        tempClassNodeMap.forEach((classNode, file) -> {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            classNode.accept(new ClassVisitor(Opcodes.ASM7, cw) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    MethodVisitor methodVisitor = super.visitMethod(access, name, descriptor, signature, exceptions);
                    String classMethod = classNode.name + "#" + name + descriptor;
                    Log.v(classMethod);
                    return new MethodVisitor(Opcodes.ASM7, methodVisitor) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                            String ownName = getOwnName(owner, name, descriptor);
                            if (remapNodeMap != null && remapNodeMap.containsKey(ownName)) {
                                Pair<String, String> pair = remapNodeMap.get(ownName);
                                Log.v("\tinsn " + ownName + " => " + pair);
                                mMappingPrinter.outputInsnNode(classMethod, ownName, pair.toString());
                                owner = pair.getFirst();
                                name = pair.getSecond();
                            }

                            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                        }
                    };
                }
            });

            FileOutputStream fos = null;
            try {
                fos = new FileOutputStream(file);
                fos.write(cw.toByteArray());
                fos.flush();
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                closeQuietly(fos);
            }
        });
    }

    private int randomRange(int min, int max) {
        return random.nextInt(max - min + 1) + min;
    }

    private <T> T randomGet(List<T> list) {
        return list.get(random.nextInt(list.size()));
    }

    public void doFog2Jar(File jarIn, File jarOut) throws IOException {
        boolean shouldExclude = shouldExcludeJar(jarIn);
        ZipInputStream zis = null;
        ZipOutputStream zos = null;
        try {
            zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(jarIn)));
            zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(jarOut)));
            ZipEntry entryIn;
            Map<String, Integer> processedEntryNamesMap = new HashMap<>();
            while ((entryIn = zis.getNextEntry()) != null) {
                final String entryName = entryIn.getName();
                if (!processedEntryNamesMap.containsKey(entryName)) {
                    ZipEntry entryOut = new ZipEntry(entryIn);
                    // Set compress method to default, fixed #12
                    if (entryOut.getMethod() != ZipEntry.DEFLATED) {
                        entryOut.setMethod(ZipEntry.DEFLATED);
                    }
                    entryOut.setCompressedSize(-1);
                    zos.putNextEntry(entryOut);
                    if (!entryIn.isDirectory()) {
                        if (entryName.endsWith(".class") && !shouldExclude) {
                            processClass(zis, zos);
                        } else {
                            copy(zis, zos);
                        }
                    }
                    zos.closeEntry();
                    processedEntryNamesMap.put(entryName, 1);
                }
            }
        } finally {
            closeQuietly(zos);
            closeQuietly(zis);
        }
    }

    private void processClass(InputStream classIn, OutputStream classOut) throws IOException {
        ClassReader cr = new ClassReader(classIn);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        processClass(cr, cw);
        classOut.write(cw.toByteArray());
        classOut.flush();
    }

    private void processClass(ClassReader cr, ClassVisitor cw) {
        final ClassVisitor cv;
        // skip module-info class, fixed #38
        if ("module-info".equals(cr.getClassName())) {
            cv = cw;
        } else {
            cv = ClassVisitorFactory.create(mStringFogImpl, mMappingPrinter, mFogPackages, mKeyGenerator, mFogClassName, cr.getClassName(), mMode, cw);
        }
        cr.accept(cv, 0);
    }

    private boolean shouldExcludeJar(File jarIn) throws IOException {
        ZipInputStream zis = null;
        try {
            zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(jarIn)));
            ZipEntry entryIn;
            while ((entryIn = zis.getNextEntry()) != null) {
                final String entryName = entryIn.getName();
                if (entryName.contains("StringFog")) {
                    return true;
                }
            }
        } finally {
            closeQuietly(zis);
        }
        return false;
    }

    private void closeQuietly(Closeable target) {
        if (target != null) {
            try {
                target.close();
            } catch (Exception e) {
                // Ignored.
            }
        }
    }

    private void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        int c;
        while ((c = in.read(buffer)) != -1) {
            out.write(buffer, 0, c);
        }
    }

}
