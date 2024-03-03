# Copy All Dependencies

A plugin that copies one or more artifacts and all of their discoverable transitive dependencies
to an output directory.

This is bit like a cross between `maven-dependency-plugin`'s goals `copy` and `get`. The former
does not copy the transitive dependencies, and the later only gets a single artifact.

## Obtaining    

Available on Maven Central. Adjust for your build system.

```xml
<artifact>
	<groupId>com.sshtools</groupId>
	<artifactId>copy-all-dependencies-maven-plugin</artifactId>
	<version>0.9.0-SNAPSHOT</version>
</artifact>
```

## Usage

There is no other documentation for this plugin, but the following should give you a good idea how to use
it.

```xml
<plugin>
	<groupId>com.sshtools</groupId>
	<artifactId>copy-all-dependencies-maven-plugin</artifactId>
	<version>0.9.0-SNAPSHOT</version>
	<configuration>
	</configuration>
</plugin>
```
