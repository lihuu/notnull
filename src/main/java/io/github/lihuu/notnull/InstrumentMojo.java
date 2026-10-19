package io.github.lihuu.notnull;

import java.io.File;
import java.io.IOException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Enforces the {@code @NotNull} contracts a module already declared, by rewriting
 * this module's compiled classes in place.
 *
 * <p>{@code org.jetbrains.annotations.NotNull} has {@code CLASS} retention, so
 * nothing enforces it at runtime. Binding this goal to {@code process-classes}
 * puts the checks into the classes the build just produced, before anything
 * packages or runs them.
 *
 * <p>Declaring the plugin once is enough for every module that inherits the
 * declaration. There is no failure mode in which a module "has" the plugin but
 * silently skips the pass: a module that declares no plugin gets no
 * instrumentation and its {@code @NotNull} annotations do nothing, so a build
 * that spans several modules should declare it where they all inherit it.
 */
@Mojo(name = "instrument",
        defaultPhase = LifecyclePhase.PROCESS_CLASSES,
        threadSafe = true)
public final class InstrumentMojo extends AbstractMojo {

    /** The classes directory to rewrite, i.e. what the compiler produced. */
    @Parameter(defaultValue = "${project.build.outputDirectory}", required = true)
    private File classesDirectory;

    /** Skips the pass without removing the declaration, for a module that opts out. */
    @Parameter(property = "notnull.skip", defaultValue = "false")
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("notnull: skipped (notnull.skip=true)");
            return;
        }
        if (classesDirectory == null || !classesDirectory.isDirectory()) {
            // No compiled classes: nothing this goal can do, and nothing to report as a failure.
            getLog().debug("notnull: no classes directory at " + classesDirectory + ", nothing to do");
            return;
        }
        NotNullInstrumenter.Result result;
        try {
            result = NotNullInstrumenter.instrument(classesDirectory.toPath());
        } catch (IOException e) {
            throw new MojoExecutionException(
                    "notnull: cannot instrument " + classesDirectory, e);
        }
        if (result.rewritten() == 0) {
            getLog().debug(result.describe(classesDirectory.toPath()));
        } else {
            getLog().info(result.describe(classesDirectory.toPath()));
        }
    }
}
