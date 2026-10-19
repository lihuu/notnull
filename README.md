# notnull-maven-plugin

Enforces `org.jetbrains.annotations.NotNull` at runtime, at build time.

The annotation has `CLASS` retention: it is a claim in the bytecode and nothing checks it. This plugin
reads the claim after compilation and injects the checks into the classes your build just produced —
a null argument for a `@NotNull` parameter throws `IllegalArgumentException` naming the parameter, and
a `null` where a `@NotNull` return value was promised throws `IllegalStateException` naming the method.

```sh
mvn notnull:instrument
```

## Why this exists

The checks used to come from JetBrains' `javac2` Ant task. Loading it required the IntelliJ GUI
Designer toolchain (`java-gui-forms-compiler`, `util-jdom`, `jaxen`, `com.jgoodies:forms`) on the build
class path, and it was not idempotent: a build that reused a classes directory without `clean`
injected every check again.

This plugin does the same job with ASM as its only dependency, and it is idempotent by construction. A
class it has rewritten carries one of the check methods it generated, so a second pass over the same
directory finds it and leaves the class alone.

Behaviour is aligned class by class with `javac2`, including the rule for when a return value is
checked: a value the compiler can see was just constructed is not checked again, and an
`invokedynamic` result still is.

## Use

```xml
<plugin>
    <groupId>io.github.lihuu</groupId>
    <artifactId>notnull-maven-plugin</artifactId>
    <version>1.0.0</version>
    <executions>
        <execution>
            <goals>
                <goal>instrument</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

`instrument` binds to `process-classes` by default, so the execution above is the whole configuration.
Declare it in the parent pom of a multi-module build so every module that declares `@NotNull` contracts
inherits the pass; a module that does not inherit it does not fail, its annotations simply do nothing.

Requirements: Java 17+ for the plugin itself, Maven 3.9.9+. The classes being instrumented can be newer
than either — ASM reads them.

| Parameter | Property | Default |
| --- | --- | --- |
| `classesDirectory` | — | `${project.build.outputDirectory}` |
| `skip` | `notnull.skip` | `false` |

The pass adds no runtime dependency to the classes it touches. Each check is a call to a `private
static` method appended to the instrumented class itself, the way `javac2` emitted its
`$$$reportNull$$$` methods, so an instrumented class keeps to itself and native-image builds gain no
new reachable type.

## What it does not do

- It matches `org.jetbrains.annotations.NotNull` only. `javax.annotation.Nonnull`, JSpecify,
  `jakarta.annotation.Nonnull` and Lombok's `@NonNull` are not read.
- It checks parameters and return values. Not fields, not local variables, and not type arguments
  nested in a type such as `List<@NotNull String>`.
- It rewrites a classes directory in place. It does not read or write jars.

## Building

```sh
mvn -B verify
```

## Releasing

A tag is the release; publishing to Maven Central is one command afterwards:

```sh
./scripts/publish-central.sh --publish
```

See [docs/publishing.md](docs/publishing.md) for the prerequisites — a GPG key without a passphrase,
the `central` server in `~/.m2/settings.xml`, and the `io.github.lihuu` namespace on the Central Portal.

## License

Apache License 2.0.
