package io.micronaut.gradle.docker;

import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.AbstractExecTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.TaskAction;
import javax.annotation.Nullable;
import javax.inject.Inject;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * A task for building a docker image. This task
 * extends from {@link AbstractExecTask} to allow customizing the java command
 * used to execute the application within the docker file.
 *
 * @author graemerocher
 * @since 1.0.0
 */
public class DockerBuildTask extends AbstractExecTask<DockerBuildTask> {

    private final MapProperty<String, Object> systemProperties;
    private final ListProperty<String> jvmArgs;
    private final Property<String> defaultCharacterEncoding;
    private final Property<String> maxHeapSize;
    private final Property<String> minHeapSize;
    private final Property<String> baseImage;
    private final Property<String> tag;
    private final Property<Integer> port;

    public DockerBuildTask() {
        super(DockerBuildTask.class);
        setExecutable("java");

        ObjectFactory objectFactory = getObjectFactory();
        this.jvmArgs = objectFactory.listProperty(String.class);
        this.systemProperties = objectFactory.mapProperty(String.class, Object.class);
        this.defaultCharacterEncoding = objectFactory.property(String.class)
                                                     .convention(StandardCharsets.UTF_8.name());
        this.maxHeapSize = objectFactory.property(String.class);
        this.minHeapSize = objectFactory.property(String.class);
        this.baseImage = objectFactory.property(String.class)
                                      .convention("openjdk:14-alpine");
        this.tag = objectFactory.property(String.class);
        this.port = objectFactory.property(Integer.class).convention(8080);
    }

    /**
     * @return The tag to use
     */
    @Input
    public Property<String> getTag() {
        return tag;
    }

    /**
     * @return The base image
     */
    @Input
    public Property<String> getBaseImage() {
        return baseImage;
    }

    /**
     *
     * @return The exposed port. Defaults to 8080.
     */
    @Input
    public Property<Integer> getPort() {
        return port;
    }

    @Override
    public List<String> getCommandLine() {
        Map<String, Object> sysProps = getSystemProperties();
        sysProps.forEach((n, v) -> {
            if (v != null) {
                args("-D" + n + "=\"" + v + "\"");
            } else {
                args("-D" + n);
            }
        });
        String maxHeap = maxHeapSize.getOrElse("128m");
        args("-Xmx" + maxHeap);
        String minHeap = minHeapSize.getOrNull();
        if (minHeap != null) {
            args("-Xms" + minHeap);
        }
        args(getJvmArgs());
        args("-jar", "/home/app/application.jar");
        return super.getCommandLine();
    }

    @Inject
    protected ObjectFactory getObjectFactory() {
        throw new UnsupportedOperationException();
    }

    @Override
    @TaskAction
    public void exec() {
        String javaCmd = getCommandLine().stream().map(s -> '"' + s + '"')
                                         .collect(Collectors.joining(","));
        try {
            URL resource = getClass().getResource("/docker/Dockerfile.template");
            Map<String, String> tokens = new HashMap<>(5);
            tokens.put("@base.image@", baseImage.get());
            tokens.put("@command.line@", javaCmd);
            tokens.put("@application.port@", String.valueOf(port.get()));
            if (resource != null) {
                File buildDir = getProject().getBuildDir();
                File dockerTemplate = new File(buildDir, "layers/Dockerfile");
                dockerTemplate.getParentFile().mkdirs();
                Path path = dockerTemplate.toPath();
                List<String> lines = new ArrayList<>();
                try( BufferedReader reader = new BufferedReader(
                        new InputStreamReader(resource.openStream(), StandardCharsets.UTF_8)
                )) {
                    String line = reader.readLine();
                    while (line != null) {
                        lines.add(replaceTokens(line, tokens));
                        line = reader.readLine();
                    }
                }

                Files.write(path, lines);

                getProject().exec(execSpec -> {
                    execSpec.setExecutable("docker");
                    execSpec.args(
                            "build",
                            "-f",
                            dockerTemplate.getAbsolutePath(),
                            "-t",
                            tag.get(),
                            "."
                    );
                });
            } else {
                throw new IllegalStateException("Docker templates not found");
            }
        } catch (IOException e) {
            throw new RuntimeException("Error building Docker image" + e.getMessage(), e);
        }
    }

    /**
     * @return The system properties to pass to the java command
     */
    @Input
    public Map<String, Object> getSystemProperties() {
        return this.systemProperties.get();
    }

    /**
     * Sets the system properties to pass to the java command
     * @param map A map of properties
     */
    public void setSystemProperties(Map<String, ?> map) {
        HashMap<String, Object> newMap = new HashMap<>();
        map.forEach((key, v) -> {
            if (v == null) {
                newMap.put(key, null);
            } else {
                newMap.put(key, v.toString());
            }
        });
        this.systemProperties.empty();
        this.systemProperties.putAll(newMap);
    }

    /**
     * Sets the system properties to pass to the java command
     * @param map A map of properties
     */
    public DockerBuildTask systemProperties(Map<String, ?> map) {
        map.forEach((key, v) -> {
            if (v == null) {
                systemProperties.put(key, (Object) null);
            } else {
                systemProperties.put(key, v.toString());
            }
        });
        return this;
    }

    /**
     * Set a the system property to pass to the java command
     * @param name The name of the property
     * @param value The value
     */
    public DockerBuildTask systemProperty(String name, Object value) {
        systemProperties(Collections.singletonMap(name, value));
        return this;
    }

    @Nullable
    @Input
    public String getDefaultCharacterEncoding() {
        return this.defaultCharacterEncoding.getOrElse(StandardCharsets.UTF_8.name());
    }

    public void setDefaultCharacterEncoding(@Nullable String s) {
        this.defaultCharacterEncoding.set(s);
    }

    @Nullable
    @Input
    @Optional
    public String getMinHeapSize() {
        return minHeapSize.getOrNull();
    }

    public void setMinHeapSize(@Nullable String s) {
        minHeapSize.set(s);
    }

    @Nullable
    @Input
    @Optional
    public String getMaxHeapSize() {
        return maxHeapSize.getOrNull();
    }

    public void setMaxHeapSize(@Nullable String s) {
        maxHeapSize.set(s);
    }

    @Nullable
    @Input
    @Optional
    public List<String> getJvmArgs() {
        return jvmArgs.getOrElse(Collections.emptyList());
    }

    public void setJvmArgs(@Nullable List<String> list) {
        jvmArgs.set(list);
    }

    public void setJvmArgs(@Nullable Iterable<?> iterable) {
        jvmArgs.set(iterableToList(iterable));
    }

    public DockerBuildTask jvmArgs(Iterable<?> iterable) {
        List<String> list = iterableToList(iterable);
        if (list != null) {
            this.jvmArgs.addAll(list);
        }
        return this;
    }

    public DockerBuildTask jvmArgs(Object... objects) {
        jvmArgs(Arrays.asList(objects));
        return this;
    }

    @Input
    @Optional
    public List<String> getAllJvmArgs() {
        return jvmArgs.getOrElse(Collections.emptyList());
    }

    public void setAllJvmArgs(List<String> list) {
        this.jvmArgs.set(list);
    }

    public void setAllJvmArgs(Iterable<?> iterable) {
        List<String> list = iterableToList(iterable);
        setAllJvmArgs(list);
    }


    private List<String> iterableToList(Iterable<?> iterable) {
        if (iterable == null) {
            return null;
        }
        List<String> list = new ArrayList<>();
        for (Object o : iterable) {
            if (o != null) {
                list.add(o.toString());
            }
        }
        return list;
    }

    private String replaceTokens(String v, Map<String, String> tokens) {
        for (Map.Entry<String, String> entry : tokens.entrySet()) {
            v = v.replace(entry.getKey(), entry.getValue());
        }
        return v;
    }
}
