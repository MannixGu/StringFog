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

import com.github.megatronking.stringfog.plugin.utils.Log;
import com.github.megatronking.stringfog.plugin.utils.TextUtils;

import org.apache.commons.io.IOUtils;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

/**
 * Output StringFog mapping to file.
 *
 * @author Megatron King
 * @since 2018/9/21 11:07
 */
/* package */ final class StringFogMappingPrinter {

    private final File mMappingFile;
    private BufferedWriter mWriter;

    private String mCurrentClassName;

    /* package */ StringFogMappingPrinter(File mappingFile) {
        this.mMappingFile = mappingFile;
    }

    /* package */ void startMappingOutput(String implementation, StringFogMode mode) {
        try {
            if (mMappingFile.exists() && !mMappingFile.delete()) {
                throw new IOException("delete stringfog mappingFile failed");
            }
            File dir = mMappingFile.getParentFile();
            if (dir.exists() || dir.mkdirs()) {
                mWriter = new BufferedWriter(new FileWriter(mMappingFile));
            } else {
                throw new IOException("Failed to create dir: " + dir.getPath());
            }
            output("stringfog impl: " + implementation);
            output("stringfog mode: " + mode);
        } catch (IOException e) {
            Log.e("Create stringfog mapping file failed.");
        }
    }

    /* package */ void output(String line) {
        try {
            mWriter.write(line);
            if (!line.endsWith("\n")) {
                mWriter.newLine();
            }
        } catch (IOException e) {
            // Ignore
        }
    }

    /* package */ void output(String className, String originValue, String encryptValue) {
        if (TextUtils.isEmpty(className)) {
            return;
        }
        if (!className.equals(mCurrentClassName)) {
            output("");
            output("[" + className + "]");
            mCurrentClassName = className;
        }
        output(originValue + " -> " + encryptValue);
    }

    void outputMapNode(String className, String name, String descriptor, String to) {
        output("Map " + className, name + descriptor, to);
    }

    void outputInsnNode(String classMethodName, String insnOwn, String to) {
        output("Insn " + classMethodName, insnOwn, to);
    }


    /* package */ void endMappingOutput() {
        if (mWriter != null) {
            IOUtils.closeQuietly(mWriter);
        }
    }

}
