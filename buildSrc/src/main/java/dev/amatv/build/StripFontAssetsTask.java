package dev.amatv.build;

import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/** Copy merged assets without fonts for the system-font APK. */
public abstract class StripFontAssetsTask extends DefaultTask {
    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getInputDirectory();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDirectory();

    @Inject
    protected abstract FileSystemOperations getFileSystemOperations();

    @TaskAction
    public void stripFonts() {
        getFileSystemOperations().sync(spec -> {
            spec.from(getInputDirectory());
            spec.into(getOutputDirectory());
            spec.setCaseSensitive(false);
            spec.exclude("**/*.ttf", "**/*.otf", "**/*.ttc", "**/*.woff", "**/*.woff2");
        });
    }
}
