package io.micronaut.gradle;

import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.plugins.ExtensionContainer;
import org.gradle.api.plugins.PluginContainer;
import org.gradle.api.tasks.TaskContainer;
import org.jetbrains.kotlin.allopen.gradle.AllOpenExtension;
import org.jetbrains.kotlin.gradle.dsl.KotlinCompile;
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmOptions;
import org.jetbrains.kotlin.gradle.plugin.KaptExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class MicronautKotlinSupport {

    /**
     * Check whether Kotlin support is present.
     *
     * @return True if Kotlin support is present
     */
    public static boolean isKotlinSupportPresent() {
        try {
            //noinspection ConstantConditions
            return KotlinCompile.class != null;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * Check whether Kotlin support is present.
     *
     * @return True if Kotlin support is present
     */
    public static boolean isKotlinJvmPresent() {
        try {
            //noinspection ConstantConditions
            return KotlinJvmOptions.class != null;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * Configure Kotlin.
     */
    public static void configureKotlin(Project project) {
        final PluginContainer plugins = project.getPlugins();
        plugins.apply("org.jetbrains.kotlin.plugin.allopen");
        final Plugin<?> kaptPlugin = plugins.apply("org.jetbrains.kotlin.kapt");

        // configure all open
        final AllOpenExtension allOpen = project.getExtensions()
                .getByType(AllOpenExtension.class);
        allOpen.annotation("io.micronaut.aop.Around");

        // add inject-java to kapt scopes
        List<String> kaptConfigs = Arrays.asList("kapt", "kaptTest");
        final DependencyHandler dependencies = project.getDependencies();
        for (String kaptConfig : kaptConfigs) {
            dependencies.add(
                    kaptConfig,
                    "io.micronaut:micronaut-inject-java"
            );
        }

        project.afterEvaluate(p -> {
            final MicronautExtension micronautExtension = p
                    .getExtensions()
                    .getByType(MicronautExtension.class);
            final String micronautVersion = MicronautLibraryPlugin.getMicronautVersion(
                    p,
                    micronautExtension
            );
            final DependencyHandler dependencyHandler = p.getDependencies();
            final Dependency platform = dependencyHandler.platform("io.micronaut:micronaut-bom:" + micronautVersion);

            for (String kaptConfig : kaptConfigs) {
                dependencyHandler.add(
                        kaptConfig,
                        platform
                );
            }
        });

        final TaskContainer tasks = project.getTasks();
        tasks.withType(KotlinCompile.class, kotlinCompile -> {
            final KotlinJvmOptions kotlinOptions = (KotlinJvmOptions) kotlinCompile.getKotlinOptions();
            kotlinOptions.setJavaParameters(true);
        });

        if (isKotlinJvmPresent()) {
            final ExtensionContainer extensions = project.getExtensions();
            extensions.configure(KaptExtension.class, kaptExtension -> {
                final MicronautExtension micronautExtension = extensions.getByType(MicronautExtension.class);
                final MicronautExtension.AnnotationProcessingConfig processingConfig = micronautExtension.getProcessingConfig();
                final boolean isIncremental = processingConfig.isIncremental().getOrElse(true);
                final String group = processingConfig.getGroup().getOrElse(project.getGroup().toString());
                final String module = processingConfig.getModule().getOrElse(project.getName());
                if (isIncremental) {

                    kaptExtension.arguments(options -> {
                        options.arg("micronaut.processing.incremental", true);
                        final List<String> annotations = processingConfig.getAnnotations().getOrElse(Collections.emptyList());
                        if (!annotations.isEmpty()) {
                            options.arg("micronaut.processing.annotations", String.join(",", annotations));
                        } else {
                            if (group.length() > 0) {
                                options.arg("micronaut.processing.annotations", group + ".*");
                            }
                        }

                        if (group.length() > 0) {
                            options.arg("micronaut.processing.group,", group);
                        }
                        options.arg("micronaut.processing.module", module);

                        return null;
                    });
                }
            });

        }
    }
}