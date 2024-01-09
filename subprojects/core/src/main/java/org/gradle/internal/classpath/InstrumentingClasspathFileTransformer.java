/*
 * Copyright 2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.classpath;

import org.gradle.api.UncheckedIOException;
import org.gradle.api.file.RelativePath;
import org.gradle.api.internal.file.archive.ZipEntry;
import org.gradle.api.internal.file.archive.ZipInput;
import org.gradle.api.internal.file.archive.impl.FileZipInput;
import org.gradle.cache.FileLock;
import org.gradle.cache.FileLockManager;
import org.gradle.internal.Pair;
import org.gradle.internal.classanalysis.AsmConstants;
import org.gradle.internal.file.FileException;
import org.gradle.internal.file.FileType;
import org.gradle.internal.hash.HashCode;
import org.gradle.internal.hash.Hasher;
import org.gradle.internal.hash.Hashing;
import org.gradle.internal.snapshot.FileSystemLocationSnapshot;
import org.gradle.util.internal.GFileUtils;
import org.gradle.util.internal.JarUtil;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.OptionalInt;

import static java.lang.String.format;
import static org.gradle.cache.internal.filelock.LockOptionsBuilder.mode;

class InstrumentingClasspathFileTransformer implements ClasspathFileTransformer {
    private static final Logger LOGGER = LoggerFactory.getLogger(InstrumentingClasspathFileTransformer.class);
    private static final int CACHE_FORMAT = 5;

    private final FileLockManager fileLockManager;
    private final ClasspathWalker classpathWalker;
    private final ClasspathBuilder classpathBuilder;
    private final CachedClasspathTransformer.Transform transform;
    private final HashCode configHash;

    /**
     * A "pending" transformation of the original file/directory.
     */
    private interface Transformation {
        /**
         * Transform the file/directory into destination. The destination should be a JAR file name.
         *
         * @param destination the destination file
         */
        void transform(File destination);
    }

    public InstrumentingClasspathFileTransformer(
        FileLockManager fileLockManager,
        ClasspathWalker classpathWalker,
        ClasspathBuilder classpathBuilder,
        CachedClasspathTransformer.Transform transform
    ) {
        this.fileLockManager = fileLockManager;
        this.classpathWalker = classpathWalker;
        this.classpathBuilder = classpathBuilder;
        this.transform = transform;
        this.configHash = configHashFor(transform);
    }

    private HashCode configHashFor(CachedClasspathTransformer.Transform transform) {
        Hasher hasher = Hashing.defaultFunction().newHasher();
        hasher.putInt(CACHE_FORMAT);
        hasher.putInt(AsmConstants.MAX_SUPPORTED_JAVA_VERSION);
        transform.applyConfigurationTo(hasher);
        return hasher.hash();
    }

    @Override
    public File transform(File source, FileSystemLocationSnapshot sourceSnapshot, File cacheDir) {
        String destDirName = hashOf(sourceSnapshot);
        File destDir = new File(cacheDir, destDirName);
        String destFileName = sourceSnapshot.getType() == FileType.Directory ? source.getName() + ".jar" : source.getName();
        File receipt = new File(destDir, destFileName + ".receipt");
        File transformed = new File(destDir, destFileName);

        // Avoid file locking overhead by checking for the receipt first.
        if (receipt.isFile()) {
            return transformed;
        }

        final File lockFile = new File(destDir, destFileName + ".lock");
        final FileLock fileLock = exclusiveLockFor(lockFile);
        try {
            if (receipt.isFile()) {
                // Lock was acquired after a concurrent writer had already finished.
                return transformed;
            }
            transform(source, transformed);
            try {
                receipt.createNewFile();
            } catch (IOException e) {
                throw new UncheckedIOException(
                    format("Failed to create receipt for instrumented classpath file '%s/%s'.", destDirName, destFileName),
                    e
                );
            }
            return transformed;
        } finally {
            fileLock.close();
        }
    }

    private FileLock exclusiveLockFor(File file) {
        return fileLockManager.lock(
            file,
            mode(FileLockManager.LockMode.Exclusive).useCrossVersionImplementation(),
            "instrumented jar cache"
        );
    }

    private String hashOf(FileSystemLocationSnapshot sourceSnapshot) {
        Hasher hasher = Hashing.defaultFunction().newHasher();
        hasher.putHash(configHash);
        // TODO - apply runtime classpath normalization?
        hasher.putHash(sourceSnapshot.getHash());
        return hasher.hash().toString();
    }

    private void transform(File source, File dest) {
        createTransformer(source).transform(dest);
    }

    private Transformation createTransformer(File source) {
        Boolean isMultiReleaseJar = null;

        if (source.isFile()) {
            // Walk a file to figure out if it is signed and if it is a multi-release JAR.
            try (ZipInput entries = FileZipInput.create(source)) {
                for (ZipEntry entry : entries) {
                    String entryName = entry.getName();
                    if (isJarSignatureFile(entryName)) {
                        // TODO(mlopatkin) Manifest of the signed JAR contains signature information and must be the first entry in the JAR.
                        //  Looking into the manifest here should be more effective.
                        // This policy doesn't transform signed JARs so no further checks are necessary.
                        return new SkipTransformation(source);
                    }
                    if (isMultiReleaseJar == null && JarUtil.isManifestName(entryName)) {
                        isMultiReleaseJar = JarUtil.isMultiReleaseJarManifest(JarUtil.readManifest(entry.getContent()));
                    }
                }
            } catch (FileException e) {
                // Ignore malformed archive, let the transformation handle it.
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return new InstrumentingTransformation(source, isMultiReleaseJar != null && isMultiReleaseJar);
    }

    private boolean isJarSignatureFile(String entryName) {
        return entryName.startsWith("META-INF/") && entryName.endsWith(".SF");
    }

    /**
     * A no-op transformation that copies the original file verbatim. Can be used if the original cannot be instrumented under policy.
     */
    private static class SkipTransformation implements Transformation {
        private final File source;

        public SkipTransformation(File source) {
            this.source = source;
        }

        @Override
        public void transform(File destination) {
            LOGGER.debug("Signed archive '{}'. Skipping instrumentation.", source.getName());
            GFileUtils.copyFile(source, destination);
        }
    }

    /**
     * Base class for the transformations. Note that the order in which entries are visited is not defined.
     */
    private class InstrumentingTransformation implements Transformation {
        protected final File source;
        private final boolean isMultiReleaseJar;

        public InstrumentingTransformation(File source, boolean isMultiReleaseJar) {
            this.source = source;
            this.isMultiReleaseJar = isMultiReleaseJar;
        }

        @Override
        public final void transform(File destination) {
            classpathBuilder.jar(destination, builder -> {
                try {
                    visitEntries(builder);
                } catch (FileException e) {
                    // Badly formed archive, so discard the contents and produce an empty JAR
                    LOGGER.debug("Malformed archive '{}'. Discarding contents.", source.getName(), e);
                }
            });
        }

        private void visitEntries(ClasspathBuilder.EntryBuilder builder) throws IOException, FileException {
            classpathWalker.visit(source, entry -> {
                visitEntry(builder, entry);
            });
        }

        private void visitEntry(ClasspathBuilder.EntryBuilder builder, ClasspathEntryVisitor.Entry entry) throws IOException {
            try {
                if (isClassFile(entry)) {
                    processClassFile(builder, entry);
                } else if (isManifest(entry)) {
                    processManifest(builder, entry);
                } else {
                    processResource(builder, entry);
                }
            } catch (Throwable e) {
                throw new IOException("Failed to process the entry '" + entry.getName() + "' from '" + source + "'", e);
            }
        }

        /**
         * Processes a class file. The type of file is determined solely by name, so it may not be a well-formed class file.
         * Base class implementation applies the {@link InstrumentingClasspathFileTransformer#transform} to the code.
         *
         * @param builder the builder for the transformed output
         * @param classEntry the entry to process
         * @throws IOException if reading or writing entry fails
         */
        protected void processClassFile(ClasspathBuilder.EntryBuilder builder, ClasspathEntryVisitor.Entry classEntry) throws IOException {
            if (isMultiReleaseJar && isInUnsupportedMrJarVersionedDirectory(classEntry)) {
                // The entries should only be filtered out if we're transforming the proper multi-release JAR.
                // Otherwise, even if the entry path looks like it is inside the versioned directory, it may still be accessed as a
                // resource.
                // Of course, user code can try to access resources inside versioned directories with full paths anyway, but that's
                // a tradeoff we're making.
                return;
            }

            ClassReader reader = new ClassReader(classEntry.getContent());
            ClassWriter classWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            Pair<RelativePath, ClassVisitor> chain = transform.apply(classEntry, classWriter);
            reader.accept(chain.right, 0);
            byte[] bytes = classWriter.toByteArray();
            builder.put(chain.left.getPathString(), bytes, classEntry.getCompressionMethod());
        }

        /**
         * Processes a JAR Manifest. Base class implementation copies the manifest unchanged.
         *
         * @param builder the builder for the transformed output
         * @param manifestEntry the entry to process
         * @throws IOException if reading or writing entry fails
         */
        protected void processManifest(ClasspathBuilder.EntryBuilder builder, ClasspathEntryVisitor.Entry manifestEntry) throws IOException {
            copyResource(builder, manifestEntry);
        }

        /**
         * Processes a resource entry. Base class implementation copies the resource unchanged.
         *
         * @param builder the builder for the transformed output
         * @param resourceEntry the entry to process
         * @throws IOException if reading or writing entry fails
         */
        protected void processResource(ClasspathBuilder.EntryBuilder builder, ClasspathEntryVisitor.Entry resourceEntry) throws IOException {
            if (isMultiReleaseJar && isInUnsupportedMrJarVersionedDirectory(resourceEntry)) {
                // The entries should only be filtered out if we're transforming the proper multi-release JAR.
                // Otherwise, even if the entry path looks like it is inside the versioned directory, it may still be accessed as a
                // resource.
                // Of course, user code can try to access resources inside versioned directories with full paths anyway, but that's
                // a tradeoff we're making.
                return;
            }
            copyResource(builder, resourceEntry);
        }

        private void copyResource(ClasspathBuilder.EntryBuilder builder, ClasspathEntryVisitor.Entry resourceEntry) throws IOException {
            builder.put(resourceEntry.getName(), resourceEntry.getContent(), resourceEntry.getCompressionMethod());
        }

        private boolean isClassFile(ClasspathEntryVisitor.Entry entry) {
            return entry.getName().endsWith(".class");
        }

        private boolean isManifest(ClasspathEntryVisitor.Entry entry) {
            return JarUtil.isManifestName(entry.getName());
        }

        private boolean isInUnsupportedMrJarVersionedDirectory(ClasspathEntryVisitor.Entry entry) {
            OptionalInt version = JarUtil.getVersionedDirectoryMajorVersion(entry.getName());
            if (version.isPresent()) {
                return !isSupportedVersion(version.getAsInt());
            }
            // The entry is not in the versioned directory at all.
            return false;
        }

        private boolean isSupportedVersion(int javaMajorVersion) {
            return javaMajorVersion <= AsmConstants.MAX_SUPPORTED_JAVA_VERSION;
        }
    }
}
