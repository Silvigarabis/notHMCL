/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2019  huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl.download.forge;

import org.jackhuang.hmcl.download.DefaultDependencyManager;
import org.jackhuang.hmcl.download.game.GameLibrariesTask;
import org.jackhuang.hmcl.game.*;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.util.StringUtils;
import org.jackhuang.hmcl.util.function.ExceptionalFunction;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jackhuang.hmcl.util.io.ChecksumMismatchException;
import org.jackhuang.hmcl.util.io.CompressingUtils;
import org.jackhuang.hmcl.util.io.FileUtils;
import org.jackhuang.hmcl.util.platform.CommandBuilder;
import org.jackhuang.hmcl.util.platform.JavaVersion;
import org.jackhuang.hmcl.util.platform.OperatingSystem;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

import static org.jackhuang.hmcl.util.DigestUtils.digest;
import static org.jackhuang.hmcl.util.Hex.encodeHex;
import static org.jackhuang.hmcl.util.Logging.LOG;

public class ForgeNewInstallTask extends Task<Version> {

    private final DefaultDependencyManager dependencyManager;
    private final DefaultGameRepository gameRepository;
    private final Version version;
    private final Path installer;
    private final List<Task<?>> dependents = new LinkedList<>();
    private final List<Task<?>> dependencies = new LinkedList<>();

    private ForgeNewInstallProfile profile;
    private Version forgeVersion;

    ForgeNewInstallTask(DefaultDependencyManager dependencyManager, Version version, Path installer) {
        this.dependencyManager = dependencyManager;
        this.gameRepository = dependencyManager.getGameRepository();
        this.version = version;
        this.installer = installer;
    }

    private <E extends Exception> String parseLiteral(String literal, Map<String, String> var, ExceptionalFunction<String, String, E> plainConverter) throws E {
        if (StringUtils.isSurrounded(literal, "{", "}"))
            return var.get(StringUtils.removeSurrounding(literal, "{", "}"));
        else if (StringUtils.isSurrounded(literal, "'", "'"))
            return StringUtils.removeSurrounding(literal, "'");
        else if (StringUtils.isSurrounded(literal, "[", "]"))
            return gameRepository.getArtifactFile(version, new Artifact(StringUtils.removeSurrounding(literal, "[", "]"))).toString();
        else
            return plainConverter.apply(literal);
    }

    @Override
    public Collection<Task<?>> getDependents() {
        return dependents;
    }

    @Override
    public Collection<Task<?>> getDependencies() {
        return dependencies;
    }

    @Override
    public boolean doPreExecute() {
        return true;
    }

    @Override
    public void preExecute() throws Exception {
        try (FileSystem fs = CompressingUtils.createReadOnlyZipFileSystem(installer)) {
            profile = JsonUtils.fromNonNullJson(FileUtils.readText(fs.getPath("install_profile.json")), ForgeNewInstallProfile.class);
            forgeVersion = JsonUtils.fromNonNullJson(FileUtils.readText(fs.getPath(profile.getJson())), Version.class);

            for (Library library : profile.getLibraries()) {
                Path file = fs.getPath("maven").resolve(library.getPath());
                if (Files.exists(file)) {
                    Path dest = gameRepository.getLibraryFile(version, library).toPath();
                    FileUtils.copyFile(file, dest);
                }
            }
        }

        dependents.add(new GameLibrariesTask(dependencyManager, version, profile.getLibraries()));
    }

    @Override
    public void execute() throws Exception {
        Path temp  = Files.createTempDirectory("forge_installer");
        int finished = 0;
        try (FileSystem fs = CompressingUtils.createReadOnlyZipFileSystem(installer)) {
            List<ForgeNewInstallProfile.Processor> processors = profile.getProcessors();
            Map<String, String> data = profile.getData();

            updateProgress(0, processors.size());

            for (Map.Entry<String, String> entry : data.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();

                data.put(key, parseLiteral(value,
                        Collections.emptyMap(),
                        str -> {
                            Path dest = temp.resolve(str);
                            FileUtils.copyFile(fs.getPath(str), dest);
                            return dest.toString();
                        }));
            }

            data.put("SIDE", "client");
            data.put("MINECRAFT_JAR", gameRepository.getVersionJar(version).getAbsolutePath());

            for (ForgeNewInstallProfile.Processor processor : processors) {
                Map<String, String> outputs = new HashMap<>();
                boolean miss = false;

                for (Map.Entry<String, String> entry : processor.getOutputs().entrySet()) {
                    String key = entry.getKey();
                    String value = entry.getValue();

                    key = parseLiteral(key, data, ExceptionalFunction.identity());
                    value = parseLiteral(value, data, ExceptionalFunction.identity());

                    if (key == null || value == null) {
                        throw new Exception("Invalid forge installation configuration");
                    }

                    outputs.put(key, value);

                    Path artifact = Paths.get(key);
                    if (Files.exists(artifact)) {
                        String code;
                        try (InputStream stream = Files.newInputStream(artifact)) {
                            code = encodeHex(digest("SHA-1", stream));
                        }

                        if (!Objects.equals(code, value)) {
                            Files.delete(artifact);
                            LOG.info("Found existing file is not valid: " + artifact);

                            miss = true;
                        }
                    } else {
                        miss = true;
                    }
                }

                if (!processor.getOutputs().isEmpty() && !miss) {
                    continue;
                }

                Path jar = gameRepository.getArtifactFile(version, processor.getJar());
                if (!Files.isRegularFile(jar))
                    throw new FileNotFoundException("Game processor file not found, should be downloaded in preprocess");

                String mainClass;
                try (JarFile jarFile = new JarFile(jar.toFile())) {
                    mainClass = jarFile.getManifest().getMainAttributes().getValue(Attributes.Name.MAIN_CLASS);
                }

                if (StringUtils.isBlank(mainClass))
                    throw new Exception("Game processor jar does not have main class " + jar);

                List<String> command = new ArrayList<>();
                command.add(JavaVersion.fromCurrentEnvironment().getBinary().toString());
                command.add("-cp");

                List<String> classpath = new ArrayList<>(processor.getClasspath().size() + 1);
                for (Artifact artifact : processor.getClasspath()) {
                    Path file = gameRepository.getArtifactFile(version, artifact);
                    if (!Files.isRegularFile(file))
                        throw new Exception("Game processor dependency missing");
                    classpath.add(file.toString());
                }
                classpath.add(jar.toString());
                command.add(String.join(OperatingSystem.PATH_SEPARATOR, classpath));

                command.add(mainClass);

                List<String> args = processor.getArgs().stream().map(arg -> {
                    String parsed = parseLiteral(arg, data, ExceptionalFunction.identity());
                    if (parsed == null)
                        throw new IllegalStateException("Invalid forge installation configuration");
                    return parsed;
                }).collect(Collectors.toList());

                command.addAll(args);

                LOG.info("Executing external processor " + processor.getJar().toString() + ", command line: " + new CommandBuilder().addAll(command).toString());
                Process process = new ProcessBuilder(command).start();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    for (String line; (line = reader.readLine()) != null;) {
                        System.out.println(line);
                    }
                }
                int exitCode = process.waitFor();
                if (exitCode != 0)
                    throw new IllegalStateException("Game processor exited abnormally");

                for (Map.Entry<String, String> entry : outputs.entrySet()) {
                    Path artifact = Paths.get(entry.getKey());
                    if (!Files.isRegularFile(artifact))
                        throw new FileNotFoundException("File missing: " + artifact);

                    String code;
                    try (InputStream stream = Files.newInputStream(artifact)) {
                        code = encodeHex(digest("SHA-1", stream));
                    }

                    if (!Objects.equals(code, entry.getValue())) {
                        Files.delete(artifact);
                        throw new ChecksumMismatchException("SHA-1", entry.getValue(), code);
                    }
                }

                updateProgress(++finished, processors.size());
            }
        }

        // resolve the version
        SimpleVersionProvider provider = new SimpleVersionProvider();
        provider.addVersion(version);

        setResult(forgeVersion
                .setInheritsFrom(version.getId())
                .resolve(provider).setJar(null)
                .setId(version.getId()).setLogging(Collections.emptyMap()));

        dependencies.add(dependencyManager.checkLibraryCompletionAsync(forgeVersion));

        FileUtils.deleteDirectory(temp.toFile());
    }
}
